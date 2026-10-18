package com.orangestudio.deepapichat.domain.sandbox

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Runs one guest command and collects its output.
 *
 * One-shot only: there is no PTY and no state between calls beyond the workspace
 * directory, so anything interactive (editors, `top`, password prompts) is out of
 * scope by design.
 */
class ShellRunner(private val io: CoroutineDispatcher = Dispatchers.IO) {

    data class Output(
        val exitCode: Int,
        val text: String,
        val truncated: Boolean,
        val timedOut: Boolean,
    ) {
        val succeeded: Boolean get() = exitCode == 0 && !timedOut
    }

    suspend fun run(
        proot: ProotCommand,
        workspace: File,
        command: String,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    ): Output = withContext(io) {
        val builder = ProcessBuilder(proot.argv(workspace, command))
        builder.environment().putAll(proot.environment())
        builder.directory(workspace)
        builder.redirectErrorStream(true)

        val process = try {
            builder.start()
        } catch (t: Throwable) {
            return@withContext Output(
                exitCode = -1,
                text = "Could not start the sandbox: ${t.message ?: t::class.java.simpleName}",
                truncated = false,
                timedOut = false,
            )
        }

        val collected = StringBuilder()
        val truncated = AtomicBoolean(false)
        // Kept draining after the cap so a chatty process is never blocked on a full
        // pipe, but only the first MAX_OUTPUT_CHARS are kept.
        val reader = thread(name = "sandbox-output", isDaemon = true) {
            try {
                val buffer = CharArray(4096)
                process.inputStream.reader().use { stream ->
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        val room = MAX_OUTPUT_CHARS - collected.length
                        if (room > 0) collected.append(buffer, 0, minOf(read, room))
                        if (read > room) truncated.set(true)
                    }
                }
            } catch (_: Throwable) {
                // The stream closes when the process is killed, which is expected.
            }
        }

        val deadline = timeoutSeconds.coerceIn(1L, MAX_TIMEOUT_SECONDS)
        val finished = process.waitFor(deadline, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(KILL_GRACE_SECONDS, TimeUnit.SECONDS)
        }
        reader.join(READER_JOIN_MILLIS)

        Output(
            exitCode = if (finished) process.exitValue() else -1,
            text = collected.toString(),
            truncated = truncated.get(),
            timedOut = !finished,
        )
    }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 60L
        const val MAX_TIMEOUT_SECONDS = 300L
        const val MAX_OUTPUT_CHARS = 16_000
        private const val KILL_GRACE_SECONDS = 5L
        private const val READER_JOIN_MILLIS = 2_000L
    }
}
