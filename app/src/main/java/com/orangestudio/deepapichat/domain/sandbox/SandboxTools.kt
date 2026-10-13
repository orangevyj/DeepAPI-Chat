package com.orangestudio.deepapichat.domain.sandbox

import com.orangestudio.deepapichat.core.AppJson
import com.orangestudio.deepapichat.core.jsonObject
import com.orangestudio.deepapichat.data.api.argString
import com.orangestudio.deepapichat.data.model.Conversation
import com.orangestudio.deepapichat.data.model.ToolCall
import com.orangestudio.deepapichat.data.store.SandboxStore
import com.orangestudio.deepapichat.domain.ToolOutcome
import com.orangestudio.deepapichat.domain.ToolSpecs
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * The one thing the tools need from the rootfs: make sure it is on disk.
 *
 * A plain interface rather than the concrete [RootfsInstaller] so the file tools
 * can be exercised on the JVM, where there is no `Context` to extract the tarball
 * with - only [execute]'s command path ever needs the rootfs at all.
 */
interface SandboxInstaller {
    suspend fun ensureInstalled(): Boolean
}

/**
 * The model-facing half of the sandbox: three small file tools over the chat's own
 * workspace, and one that runs a command inside the Alpine rootfs.
 *
 * The file tools are plain Kotlin rather than shell commands, so they still work
 * before the rootfs has ever been installed, and every path is resolved inside the
 * workspace - a chat cannot reach another chat's files, or anything else under the
 * app's private directory.
 *
 * Being here is not a security boundary. proot confines which files the guest can
 * see; it does not confine the network, or the time and memory a command can burn
 * beyond the runner's timeout.
 */
class SandboxTools(
    private val store: SandboxStore,
    private val installer: SandboxInstaller,
    private val runner: ShellRunner,
    private val nativeLibDir: String,
) {

    suspend fun execute(call: ToolCall, conversation: Conversation): ToolOutcome {
        val args = arguments(call)
            ?: return ToolOutcome.failure("Invalid arguments", "The arguments were not valid JSON.")
        return when (call.name) {
            ToolSpecs.RUN_COMMAND -> runCommand(args, conversation)
            ToolSpecs.READ_FILE -> readFile(args, conversation)
            ToolSpecs.WRITE_FILE -> writeFile(args, conversation)
            ToolSpecs.LIST_FILES -> listFiles(args, conversation)
            else -> ToolOutcome.failure("Unknown tool", "`${call.name}` is not a sandbox tool.")
        }
    }

    // ------------------------------------------------------------------ commands

    private suspend fun runCommand(args: JsonObject, conversation: Conversation): ToolOutcome {
        val command = args.argString("command")?.trim().orEmpty()
        if (command.isBlank()) {
            return ToolOutcome.failure("Invalid arguments", "The `command` field is required.")
        }

        val workspace = store.prepare(conversation.id)
        if (!installer.ensureInstalled()) {
            return ToolOutcome.failure(
                summary = "Sandbox unavailable",
                reason = "The Linux sandbox could not be prepared on this device.",
            )
        }
        val timeout = args.argString("timeout_seconds")?.trim()?.toLongOrNull()
            ?.coerceIn(1L, ShellRunner.MAX_TIMEOUT_SECONDS)
            ?: ShellRunner.DEFAULT_TIMEOUT_SECONDS

        val proot = ProotCommand(
            nativeLibDir = nativeLibDir,
            rootfs = store.rootfs,
            tmpDir = store.tmp,
        )
        val output = runner.run(proot, workspace, command, timeout)

        val note = buildList {
            if (output.timedOut) add("killed after ${timeout}s, so the output may be incomplete")
            if (output.truncated) add("earlier output was dropped because it was too long")
        }.joinToString("; ")

        return ToolOutcome(
            resultJson = jsonObject {
                put("ok", output.succeeded)
                put("exit_code", output.exitCode)
                put("timed_out", output.timedOut)
                put("truncated", output.truncated)
                put("output", output.text)
                putIfPresent("note", note.ifBlank { null })
            }.toString(),
            summary = when {
                output.timedOut -> "Timed out after ${timeout}s"
                output.succeeded -> "Ran: ${command.lineSequence().first().take(SUMMARY_CHARS)}"
                else -> "Command failed (exit ${output.exitCode})"
            },
            succeeded = output.succeeded,
        )
    }

    // -------------------------------------------------------------------- files

    private suspend fun readFile(args: JsonObject, conversation: Conversation): ToolOutcome {
        val raw = args.argString("path")?.trim().orEmpty()
        if (raw.isBlank()) return ToolOutcome.failure("Invalid arguments", "The `path` field is required.")

        val file = resolve(store.prepare(conversation.id), raw) ?: return outside(raw)
        if (!file.isFile) {
            return ToolOutcome.failure("No such file", "`$raw` is not a file in this chat's workspace.")
        }
        val bytes = runCatching { file.readBytes() }.getOrNull()
            ?: return ToolOutcome.failure("Unreadable", "`$raw` could not be read.")
        if (bytes.any { it == 0.toByte() }) {
            return ToolOutcome.failure(
                summary = "Binary file",
                reason = "`$raw` is binary (${bytes.size} bytes); inspect it with run_command instead.",
            )
        }

        val text = String(bytes, Charsets.UTF_8)
        val clipped = text.length > READ_LIMIT_CHARS
        return ToolOutcome(
            resultJson = jsonObject {
                put("ok", true)
                put("path", raw)
                put("bytes", bytes.size)
                put("truncated", clipped)
                put("content", if (clipped) text.take(READ_LIMIT_CHARS) else text)
            }.toString(),
            summary = "Read ${file.name}",
            succeeded = true,
        )
    }

    private suspend fun writeFile(args: JsonObject, conversation: Conversation): ToolOutcome {
        val raw = args.argString("path")?.trim().orEmpty()
        if (raw.isBlank()) return ToolOutcome.failure("Invalid arguments", "The `path` field is required.")
        val content = args.argString("content").orEmpty()

        val file = resolve(store.prepare(conversation.id), raw) ?: return outside(raw)
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(content)
        }.fold(
            onSuccess = {
                ToolOutcome(
                    resultJson = jsonObject {
                        put("ok", true)
                        put("path", raw)
                        put("bytes", content.toByteArray().size)
                    }.toString(),
                    summary = "Wrote ${file.name}",
                    succeeded = true,
                )
            },
            onFailure = {
                ToolOutcome.failure(
                    summary = "Write failed",
                    reason = "`$raw` could not be written: ${it.message ?: "unknown error"}.",
                )
            },
        )
    }

    private suspend fun listFiles(args: JsonObject, conversation: Conversation): ToolOutcome {
        val raw = args.argString("path")?.trim().orEmpty().ifBlank { "." }

        val dir = resolve(store.prepare(conversation.id), raw) ?: return outside(raw)
        if (!dir.isDirectory) {
            return ToolOutcome.failure("No such folder", "`$raw` is not a folder in this chat's workspace.")
        }
        val entries = runCatching { dir.listFiles()?.sortedBy { it.name } }.getOrNull().orEmpty()
        val shown = entries.take(MAX_LIST_ENTRIES)

        return ToolOutcome(
            resultJson = jsonObject {
                put("ok", true)
                put("path", raw)
                put("count", entries.size)
                put("truncated", entries.size > shown.size)
                arr("entries") {
                    for (entry in shown) {
                        addObject {
                            put("name", entry.name)
                            put("type", if (entry.isDirectory) "directory" else "file")
                            if (entry.isFile) put("bytes", entry.length())
                        }
                    }
                }
            }.toString(),
            summary = "Listed ${shown.size} of ${entries.size} in ${if (raw == ".") "the workspace" else raw}",
            succeeded = true,
        )
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Resolves a model-supplied path inside this chat's workspace, or null if it
     * tries to leave. Guest paths are accepted, so `/workspace/notes.txt` and
     * `notes.txt` mean the same file; any other absolute path is read as relative
     * to the workspace, so `/etc/passwd` is just `etc/passwd` in there.
     */
    private fun resolve(workspace: File, raw: String): File? {
        val guest = ProotCommand.GUEST_WORKSPACE
        val relative = when {
            raw == guest -> ""
            raw.startsWith("$guest/") -> raw.removePrefix("$guest/")
            else -> raw.trimStart('/', '\\')
        }
        val root = runCatching { workspace.canonicalFile }.getOrNull() ?: return null
        val candidate = runCatching { File(root, relative).canonicalFile }.getOrNull() ?: return null
        val rootPath = root.path
        return when {
            candidate.path == rootPath -> candidate
            candidate.path.startsWith(rootPath + File.separator) -> candidate
            else -> null
        }
    }

    private fun outside(path: String) = ToolOutcome.failure(
        summary = "Outside the workspace",
        reason = "`$path` is outside this chat's workspace. Only the files in /workspace are reachable.",
    )

    private fun arguments(call: ToolCall): JsonObject? = runCatching {
        AppJson.compact.decodeFromString(JsonObject.serializer(), call.arguments.ifBlank { "{}" })
    }.getOrNull()

    private companion object {
        const val SUMMARY_CHARS = 60
        const val READ_LIMIT_CHARS = 40_000
        const val MAX_LIST_ENTRIES = 200
    }
}
