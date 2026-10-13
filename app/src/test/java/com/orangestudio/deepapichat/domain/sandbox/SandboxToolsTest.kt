package com.orangestudio.deepapichat.domain.sandbox

import com.orangestudio.deepapichat.core.JsonStore
import com.orangestudio.deepapichat.data.model.Conversation
import com.orangestudio.deepapichat.data.model.ToolCall
import com.orangestudio.deepapichat.data.store.SandboxStore
import com.orangestudio.deepapichat.domain.ToolSpecs
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Covers the file half of the sandbox, which needs no rootfs and no Android runtime.
 *
 * The point of these tools is that a chat can only ever touch its own workspace, so
 * most of what follows is an attempt to get out of it.
 */
class SandboxToolsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val store by lazy { SandboxStore(JsonStore(File(temp.root, "data"))) }

    private val tools by lazy {
        SandboxTools(
            store = store,
            installer = object : SandboxInstaller {
                // There is no rootfs on the JVM, and the file tools must not need one.
                override suspend fun ensureInstalled() = true
            },
            runner = ShellRunner(),
            nativeLibDir = temp.root.absolutePath,
        )
    }

    private fun chat(id: String) = Conversation(id = id)

    private fun call(name: String, arguments: String) =
        ToolCall(id = "call-1", name = name, arguments = arguments)

    private fun workspaceFile(id: String, path: String) = File(store.workspaceFor(id), path)

    @Test
    fun filesSurviveARoundTripThroughTheWorkspace() = runBlocking {
        val first = chat("chat-1")

        val written = tools.execute(
            call(ToolSpecs.WRITE_FILE, """{"path":"notes/today.txt","content":"hello sandbox"}"""),
            first,
        )
        assertTrue(written.summary, written.succeeded)
        assertTrue(workspaceFile("chat-1", "notes/today.txt").isFile)

        val read = tools.execute(call(ToolSpecs.READ_FILE, """{"path":"notes/today.txt"}"""), first)
        assertTrue(read.summary, read.succeeded)
        assertTrue(read.resultJson.contains("hello sandbox"))

        val listed = tools.execute(call(ToolSpecs.LIST_FILES, """{"path":"notes"}"""), first)
        assertTrue(listed.summary, listed.succeeded)
        assertTrue(listed.resultJson.contains("today.txt"))
    }

    @Test
    fun theWorkspaceRootIsListedWhenNoPathIsGiven() = runBlocking {
        val first = chat("chat-1")
        tools.execute(call(ToolSpecs.WRITE_FILE, """{"path":"a.txt","content":"a"}"""), first)

        val listed = tools.execute(call(ToolSpecs.LIST_FILES, "{}"), first)

        assertTrue(listed.summary, listed.succeeded)
        assertTrue(listed.resultJson.contains("a.txt"))
    }

    /** `/workspace/x` and `x` are the same file: the model thinks in guest paths. */
    @Test
    fun guestPathsAreMappedIntoTheWorkspace() = runBlocking {
        val first = chat("chat-1")

        val written = tools.execute(
            call(ToolSpecs.WRITE_FILE, """{"path":"/workspace/deep/file.txt","content":"x"}"""),
            first,
        )

        assertTrue(written.summary, written.succeeded)
        assertTrue(workspaceFile("chat-1", "deep/file.txt").isFile)
    }

    @Test
    fun climbingOutOfTheWorkspaceIsRefused() = runBlocking {
        val first = chat("chat-1")
        store.root.mkdirs()
        File(store.root, "escape.txt").writeText("secret")

        val climbed = tools.execute(call(ToolSpecs.READ_FILE, """{"path":"../escape.txt"}"""), first)
        val nested = tools.execute(call(ToolSpecs.READ_FILE, """{"path":"a/../../escape.txt"}"""), first)

        assertFalse(climbed.summary, climbed.succeeded)
        assertFalse(nested.summary, nested.succeeded)
        assertFalse(climbed.resultJson.contains("secret"))
        assertFalse(nested.resultJson.contains("secret"))
    }

    @Test
    fun writesCannotClimbOutEither() = runBlocking {
        val first = chat("chat-1")

        val escaped = tools.execute(
            call(ToolSpecs.WRITE_FILE, """{"path":"../pwned.txt","content":"x"}"""),
            first,
        )

        assertFalse(escaped.summary, escaped.succeeded)
        assertFalse(File(store.root, "pwned.txt").exists())
    }

    /**
     * An absolute path outside /workspace is read as workspace-relative, so asking
     * for the device's real /etc/passwd lands on a file that is not there instead of
     * on the system one.
     */
    @Test
    fun absolutePathsCannotReachTheRealFilesystem() = runBlocking {
        val first = chat("chat-1")

        val read = tools.execute(call(ToolSpecs.READ_FILE, """{"path":"/etc/passwd"}"""), first)

        assertFalse(read.summary, read.succeeded)
        assertTrue(workspaceFile("chat-1", "etc/passwd").exists().not())
    }

    @Test
    fun oneChatCannotSeeAnothersFiles() = runBlocking {
        tools.execute(call(ToolSpecs.WRITE_FILE, """{"path":"mine.txt","content":"only mine"}"""), chat("chat-1"))

        val read = tools.execute(call(ToolSpecs.READ_FILE, """{"path":"mine.txt"}"""), chat("chat-2"))

        assertFalse(read.summary, read.succeeded)
        assertTrue(workspaceFile("chat-1", "mine.txt").isFile)
        assertFalse(workspaceFile("chat-2", "mine.txt").exists())
    }

    @Test
    fun binaryFilesAreReportedRatherThanDumped() = runBlocking {
        val first = chat("chat-1")
        File(store.prepare("chat-1"), "blob.bin").writeBytes(byteArrayOf(0x41, 0x00, 0x42))

        val read = tools.execute(call(ToolSpecs.READ_FILE, """{"path":"blob.bin"}"""), first)

        assertFalse(read.summary, read.succeeded)
        assertTrue(read.summary, read.summary.contains("Binary"))
        assertFalse(read.resultJson.contains("A"))
    }

    @Test
    fun missingArgumentsFailInsteadOfCrashing() = runBlocking {
        val first = chat("chat-1")

        assertFalse(tools.execute(call(ToolSpecs.READ_FILE, "{}"), first).succeeded)
        assertFalse(tools.execute(call(ToolSpecs.WRITE_FILE, "not json"), first).succeeded)
        assertFalse(tools.execute(call(ToolSpecs.LIST_FILES, """{"path":"nope"}"""), first).succeeded)
        assertFalse(tools.execute(call("no_such_tool", "{}"), first).succeeded)
    }
}
