package com.orangestudio.deepapichat.domain

import com.orangestudio.deepapichat.core.AppJson
import com.orangestudio.deepapichat.core.jsonObject
import com.orangestudio.deepapichat.data.api.argString
import com.orangestudio.deepapichat.data.model.AppSettings
import com.orangestudio.deepapichat.data.model.Conversation
import com.orangestudio.deepapichat.data.model.MemorySource
import com.orangestudio.deepapichat.data.model.ToolCall
import com.orangestudio.deepapichat.data.store.MemoryStore
import com.orangestudio.deepapichat.domain.sandbox.SandboxTools
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Appended to every tool description. The API needs an answer to every call before
 * the next request can be made, so the model is asked not to batch them.
 */
private const val ONE_CALL_AT_A_TIME =
    "Ask for at most one tool call per turn, and wait for its result before deciding what to do next."

/**
 * Appended to the sandbox tools only. Without this the model treats `run_command`
 * as a bare shell and never reaches for the package manager: nothing tells it the
 * guest is a complete Alpine system with working network access.
 */
private const val SANDBOX_NOTES =
    "You are root in an Alpine Linux rootfs with working network access. /workspace is this " +
        "chat's own folder and the only part of the device you can see. The package manager " +
        "works, so `apk add <package>` can install extra tools when you need them."

/** JSON declarations handed to the model in the request's `tools` array. */
object ToolSpecs {

    const val SAVE_MEMORY = "save_memory"

    /**
     * The only tool today. Its shape is the template for the sandboxed-terminal
     * tools that come later: a name, a description, and a JSON-schema parameter
     * object.
     */
    fun saveMemory(): JsonObject = jsonObject {
        put("type", "function")
        obj("function") {
            put("name", SAVE_MEMORY)
            put(
                "description",
                "Store a durable fact about the user so it is available in future " +
                    "conversations: their preferences, ongoing projects, how they like " +
                    "answers written, or anything they ask you to remember. Do not use " +
                    "this for transient details of the current task, and do not store " +
                    "secrets, API keys or passwords. Call it at most once per fact.",
            )
            obj("parameters") {
                put("type", "object")
                obj("properties") {
                    obj("content") {
                        put("type", "string")
                        put(
                            "description",
                            "One self-contained sentence in the third person, e.g. " +
                                "\"Prefers Kotlin over Java for Android work.\"",
                        )
                    }
                }
                array("required", listOf(kotlinx.serialization.json.JsonPrimitive("content")))
            }
        }
    }

    fun all(): List<JsonObject> = listOf(saveMemory())

    // ------------------------------------------------------------ sandbox tools

    const val RUN_COMMAND = "run_command"
    const val READ_FILE = "read_file"
    const val WRITE_FILE = "write_file"
    const val LIST_FILES = "list_files"

    /** True for every tool that reaches the sandbox rather than the memory store. */
    fun isSandboxTool(name: String): Boolean = name in SANDBOX_NAMES

    private val SANDBOX_NAMES = setOf(RUN_COMMAND, READ_FILE, WRITE_FILE, LIST_FILES)

    /**
     * The terminal tools. They share one workspace per conversation, which proot
     * mounts at `/workspace`; nothing outside it is reachable, including other
     * chats' workspaces.
     */
    fun sandbox(): List<JsonObject> = listOf(runCommand(), readFile(), writeFile(), listFiles())

    private fun runCommand(): JsonObject = jsonObject {
        put("type", "function")
        obj("function") {
            put("name", RUN_COMMAND)
            put(
                "description",
                "Run a shell command in this chat's Linux sandbox (Alpine with a " +
                    "busybox userland) and get its output back. Commands run once and " +
                    "exit: there is no interactive session, and nothing can read from " +
                    "stdin, so never run editors, pagers or anything that waits for " +
                    "input. The chat's files are in /workspace, which is also the " +
                    "working directory; write results there and they persist for the " +
                    "rest of the conversation. Very long output is truncated and a " +
                    "command that runs too long is killed, so prefer targeted commands " +
                    "over dumping whole files. Long-running work should be split up or " +
                    "given a larger timeout_seconds.",
            )
            obj("parameters") {
                put("type", "object")
                obj("properties") {
                    obj("command") {
                        put("type", "string")
                        put("description", "The shell command to run, e.g. `ls -la /workspace`.")
                    }
                    obj("timeout_seconds") {
                        put("type", "integer")
                        put("description", "How long to allow, 1-${ShellLimit.MAX_SECONDS}. Default ${ShellLimit.DEFAULT_SECONDS}.")
                    }
                }
                array("required", listOf(kotlinx.serialization.json.JsonPrimitive("command")))
            }
        }
    }

    private fun readFile(): JsonObject = jsonObject {
        put("type", "function")
        obj("function") {
            put("name", READ_FILE)
            put(
                "description",
                "Read a text file from this chat's workspace. Prefer this over `cat` " +
                    "when you want the contents verbatim, since the result is not mixed " +
                    "with shell noise. Binary files cannot be read this way.",
            )
            obj("parameters") {
                put("type", "object")
                obj("properties") {
                    obj("path") {
                        put("type", "string")
                        put("description", "File path, relative to /workspace.")
                    }
                }
                array("required", listOf(kotlinx.serialization.json.JsonPrimitive("path")))
            }
        }
    }

    private fun writeFile(): JsonObject = jsonObject {
        put("type", "function")
        obj("function") {
            put("name", WRITE_FILE)
            put(
                "description",
                "Create or overwrite a text file in this chat's workspace, making any " +
                    "missing folders on the way. Use this rather than shell redirection " +
                    "when you are writing the whole file.",
            )
            obj("parameters") {
                put("type", "object")
                obj("properties") {
                    obj("path") {
                        put("type", "string")
                        put("description", "File path, relative to /workspace.")
                    }
                    obj("content") {
                        put("type", "string")
                        put("description", "The full contents to write.")
                    }
                }
                array(
                    "required",
                    listOf(
                        kotlinx.serialization.json.JsonPrimitive("path"),
                        kotlinx.serialization.json.JsonPrimitive("content"),
                    ),
                )
            }
        }
    }

    private fun listFiles(): JsonObject = jsonObject {
        put("type", "function")
        obj("function") {
            put("name", LIST_FILES)
            put(
                "description",
                "List one folder of this chat's workspace, with each entry's type and " +
                    "size. Does not recurse; use run_command with `find` for that.",
            )
            obj("parameters") {
                put("type", "object")
                obj("properties") {
                    obj("path") {
                        put("type", "string")
                        put("description", "Folder path, relative to /workspace. Defaults to the workspace root.")
                    }
                }
                array("required", emptyList())
            }
        }
    }

    /** Keeps the advertised timeout range in step with what the runner enforces. */
    private object ShellLimit {
        const val DEFAULT_SECONDS = 60L
        const val MAX_SECONDS = 300L
    }
}

/** Result of running a tool, along with a short label for the approval card. */
data class ToolOutcome(
    val resultJson: String,
    val summary: String,
    val succeeded: Boolean,
) {
    companion object {
        fun success(summary: String, resultJson: String = "{\"ok\":true}") =
            ToolOutcome(resultJson, summary, succeeded = true)

        fun failure(summary: String, reason: String) = ToolOutcome(
            resultJson = jsonObject {
                put("ok", false)
                put("error", reason)
            }.toString(),
            summary = summary,
            succeeded = false,
        )
    }
}

/** Labels shown on the pending-approval card. Generic, so new tools just work. */
object ToolPresenter {

    fun title(call: ToolCall): String = when (call.name) {
        ToolSpecs.SAVE_MEMORY -> "Save to memory"
        ToolSpecs.RUN_COMMAND -> "Run a command"
        ToolSpecs.READ_FILE -> "Read a file"
        ToolSpecs.WRITE_FILE -> "Write a file"
        ToolSpecs.LIST_FILES -> "List files"
        else -> "Run ${call.name}"
    }

    fun detail(call: ToolCall): String {
        val args = parseArguments(call.arguments)
        return when (call.name) {
            ToolSpecs.SAVE_MEMORY -> args?.argString("content")?.trim().orEmpty()
            ToolSpecs.RUN_COMMAND -> args?.argString("command")?.trim().orEmpty()
            ToolSpecs.READ_FILE, ToolSpecs.LIST_FILES -> args?.argString("path")?.trim().orEmpty()
            ToolSpecs.WRITE_FILE -> listOfNotNull(
                args?.argString("path")?.trim()?.takeIf { it.isNotBlank() },
                args?.argString("content")?.take(PREVIEW_CHARS),
            ).joinToString("\n")
            else -> call.arguments
        }.ifBlank { call.arguments }
    }

    private const val PREVIEW_CHARS = 400

    /**
     * Sandbox calls always ask, whatever auto-approval says for memories. The
     * approval card reads this so it never offers a checkbox that would do nothing.
     */
    fun alwaysAsks(call: ToolCall): Boolean = ToolSpecs.isSandboxTool(call.name)

    /**
     * The readable part of a tool result, so the transcript shows what a command
     * printed rather than the JSON envelope it arrived in. Falls back to the raw
     * payload when there is nothing friendlier to say.
     */
    fun resultSummary(toolName: String?, resultJson: String): String {
        val args = parseArguments(resultJson) ?: return resultJson
        listOf("output", "content", "reason", "note")
            .firstNotNullOfOrNull { key -> args.argString(key)?.trim()?.takeIf { it.isNotEmpty() } }
            ?.let { return it }
        if (args.argString("ok") == "true") {
            return if (toolName == ToolSpecs.SAVE_MEMORY) "Saved." else "Done."
        }
        return resultJson
    }

    private fun parseArguments(raw: String): JsonObject? =
        runCatching { AppJson.compact.decodeFromString(JsonObject.serializer(), raw.ifBlank { "{}" }) }
            .getOrNull()
}

/**
 * Decides which tools to offer and executes the ones the user approves.
 *
 * Tools are offered only when memories are switched on both globally and for the
 * conversation, so turning memory off actually removes the model's ability to
 * write anything.
 */
class ToolRegistry(
    private val memoryStore: MemoryStore,
    private val sandbox: SandboxTools? = null,
) {

    fun availableTools(settings: AppSettings, conversation: Conversation): List<JsonObject> {
        val memoriesActive = settings.memoriesEnabled && conversation.memoryEnabled
        return buildList {
            if (memoriesActive && settings.memoryToolEnabled) {
                addAll(ToolSpecs.all().map { it.withNotes(null) })
            }
            if (settings.sandboxEnabled && sandbox != null) {
                addAll(ToolSpecs.sandbox().map { it.withNotes(SANDBOX_NOTES) })
            }
        }
    }

    /**
     * Appends instructions to a tool's description. Both sentences exist because of
     * something a model does not know by default: that the API wants every call
     * answered before the next request (so it should not batch them), and that the
     * sandbox is a whole Alpine system rather than a bare shell.
     */
    private fun JsonObject.withNotes(extra: String?): JsonObject {
        val function = this["function"] as? JsonObject ?: return this
        val text = (function["description"] as? JsonPrimitive)?.contentOrNull ?: return this
        val notes = listOfNotNull(ONE_CALL_AT_A_TIME, extra).joinToString(" ")
        return JsonObject(
            this + ("function" to JsonObject(function + ("description" to JsonPrimitive("$text $notes")))),
        )
    }

    /**
     * Tools that may run without asking. The sandbox is deliberately never on this
     * list: every command is code execution, so it is confirmed by the user each
     * time regardless of what auto-approval is set to for memories.
     */
    fun isAutoApprovable(call: ToolCall): Boolean = call.name == ToolSpecs.SAVE_MEMORY

    suspend fun execute(call: ToolCall, conversation: Conversation): ToolOutcome = when (call.name) {
        ToolSpecs.SAVE_MEMORY -> saveMemory(call)
        else -> if (ToolSpecs.isSandboxTool(call.name)) {
            sandbox?.execute(call, conversation) ?: ToolOutcome.failure(
                summary = "Sandbox unavailable",
                reason = "The sandbox is not available in this build.",
            )
        } else {
            ToolOutcome.failure(
                summary = "Unknown tool",
                reason = "The tool '${call.name}' is not available in this build.",
            )
        }
    }

    fun denialOutcome(): ToolOutcome = ToolOutcome(
        resultJson = jsonObject {
            put("ok", false)
            put("error", "The user denied this action. Do not try again unless they ask.")
        }.toString(),
        summary = "Denied",
        succeeded = false,
    )

    private suspend fun saveMemory(call: ToolCall): ToolOutcome {
        val args = runCatching {
            AppJson.compact.decodeFromString(JsonObject.serializer(), call.arguments.ifBlank { "{}" })
        }.getOrNull()
            ?: return ToolOutcome.failure("Invalid arguments", "The arguments were not valid JSON.")

        val content = args.argString("content")?.trim().orEmpty()
        if (content.isBlank()) {
            return ToolOutcome.failure("Invalid arguments", "The `content` field is required.")
        }

        val duplicate = memoryStore.items.value
            .firstOrNull { it.content.equals(content, ignoreCase = true) }
        if (duplicate != null) {
            return ToolOutcome(
                resultJson = jsonObject {
                    put("ok", true)
                    put("duplicate", true)
                }.toString(),
                summary = "Already remembered",
                succeeded = true,
            )
        }

        memoryStore.add(content, MemorySource.Assistant)
        return ToolOutcome(
            resultJson = jsonObject {
                put("ok", true)
                put("saved", content)
                put("total", memoryStore.items.value.size)
            }.toString(),
            summary = "Memory saved",
            succeeded = true,
        )
    }
}
