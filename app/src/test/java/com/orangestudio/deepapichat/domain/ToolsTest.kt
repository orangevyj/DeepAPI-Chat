package com.orangestudio.deepapichat.domain

import com.orangestudio.deepapichat.core.JsonStore
import com.orangestudio.deepapichat.data.model.AppSettings
import com.orangestudio.deepapichat.data.model.Conversation
import com.orangestudio.deepapichat.data.model.MemorySource
import com.orangestudio.deepapichat.data.model.ToolCall
import com.orangestudio.deepapichat.data.store.MemoryStore
import com.orangestudio.deepapichat.data.store.SandboxStore
import com.orangestudio.deepapichat.domain.sandbox.SandboxInstaller
import com.orangestudio.deepapichat.domain.sandbox.SandboxTools
import com.orangestudio.deepapichat.domain.sandbox.ShellRunner
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ToolsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun memories() = MemoryStore(JsonStore(File(temp.root, "data"))).also {
        runBlocking { it.load() }
    }

    /** No sandbox wired, which is the state every build before this one was in. */
    private fun registry(): Pair<ToolRegistry, MemoryStore> {
        val memories = memories()
        return ToolRegistry(memories) to memories
    }

    private fun sandboxed(): Pair<ToolRegistry, MemoryStore> {
        val memories = memories()
        val sandbox = SandboxTools(
            store = SandboxStore(JsonStore(File(temp.root, "data"))),
            installer = object : SandboxInstaller {
                override suspend fun ensureInstalled() = true
            },
            runner = ShellRunner(),
            nativeLibDir = temp.root.absolutePath,
        )
        return ToolRegistry(memories, sandbox) to memories
    }

    private fun saveMemoryCall(id: String, content: String) = ToolCall(
        id = id,
        name = ToolSpecs.SAVE_MEMORY,
        arguments = """{"content":"$content"}""",
    )

    private fun toolNames(registry: ToolRegistry, settings: AppSettings, chat: Conversation): List<String> =
        registry.availableTools(settings, chat).map { spec: JsonObject ->
            (spec["function"] as? JsonObject)?.get("name").toString().trim('"')
        }

    @Test
    fun saveMemoryWritesThroughToTheStore() = runBlocking {
        val (registry, memories) = registry()
        val call = saveMemoryCall("c1", "Prefers jasmine tea")
        val outcome = registry.execute(call, Conversation(id = "c1"))

        assertTrue(outcome.succeeded)
        assertEquals(1, memories.items.value.size)
        assertEquals("Prefers jasmine tea", memories.items.value.single().content)
        assertEquals(MemorySource.Assistant, memories.items.value.single().source)
    }

    @Test
    fun duplicateMemoriesAreNotStoredTwice() = runBlocking {
        val (registry, memories) = registry()
        val chat = Conversation(id = "c1")
        registry.execute(saveMemoryCall("c1", "Prefers jasmine tea"), chat)
        val again = registry.execute(saveMemoryCall("c2", "prefers JASMINE tea"), chat)

        assertEquals(1, memories.items.value.size)
        assertTrue("a duplicate should report success without adding a row", again.succeeded)
    }

    @Test
    fun blankContentIsRejected() = runBlocking {
        val (registry, memories) = registry()
        val outcome = registry.execute(saveMemoryCall("c1", "   "), Conversation(id = "c1"))

        assertFalse(outcome.succeeded)
        assertTrue(memories.items.value.isEmpty())
    }

    @Test
    fun malformedArgumentsFailInsteadOfCrashing() = runBlocking {
        val (registry, memories) = registry()
        val call = ToolCall(id = "c1", name = ToolSpecs.SAVE_MEMORY, arguments = "not json")
        val outcome = registry.execute(call, Conversation(id = "c1"))

        assertFalse(outcome.succeeded)
        assertTrue(memories.items.value.isEmpty())
    }

    @Test
    fun unknownToolsAreRefused() = runBlocking {
        val (registry, _) = registry()
        val call = ToolCall(id = "c1", name = "sudo_make_me_a_sandwich", arguments = "{}")
        assertFalse(registry.execute(call, Conversation(id = "c1")).succeeded)
    }

    @Test
    fun sandboxToolsAreRefusedWhenNoSandboxIsWired() = runBlocking {
        val (registry, _) = registry()
        val call = ToolCall(id = "c1", name = ToolSpecs.RUN_COMMAND, arguments = """{"command":"ls"}""")

        assertFalse(registry.execute(call, Conversation(id = "c1")).succeeded)
    }

    @Test
    fun everyTerminalToolIsRecognised() {
        for (name in listOf(ToolSpecs.RUN_COMMAND, ToolSpecs.READ_FILE, ToolSpecs.WRITE_FILE, ToolSpecs.LIST_FILES)) {
            assertTrue(name, ToolSpecs.isSandboxTool(name))
        }
        assertFalse(ToolSpecs.isSandboxTool(ToolSpecs.SAVE_MEMORY))
    }

    @Test
    fun toolsAreOfferedOnlyWhenMemoriesAreLive() {
        val (registry, _) = registry()
        val on = Conversation(id = "c1", memoryEnabled = true)
        val off = Conversation(id = "c2", memoryEnabled = false)

        assertTrue(registry.availableTools(AppSettings(), on).isNotEmpty())
        assertTrue(registry.availableTools(AppSettings(memoriesEnabled = false), on).isEmpty())
        assertTrue(registry.availableTools(AppSettings(), off).isEmpty())
        assertTrue(registry.availableTools(AppSettings(memoryToolEnabled = false), on).isEmpty())
    }

    /**
     * The sandbox switch is the only gate on code execution, checked in both
     * directions: off means the model never learns the tools exist, on adds them in
     * addition to the memory tool rather than replacing it.
     */
    @Test
    fun theSandboxSwitchGatesTheTerminalTools() {
        val (registry, _) = sandboxed()
        val chat = Conversation(id = "c1")

        val off = toolNames(registry, AppSettings(sandboxEnabled = false), chat)
        assertTrue(off.contains(ToolSpecs.SAVE_MEMORY))
        assertFalse(off.contains(ToolSpecs.RUN_COMMAND))

        val on = toolNames(registry, AppSettings(sandboxEnabled = true), chat)
        assertTrue(on.containsAll(ToolSpecs.sandbox().mapNotNull { (it["function"] as? JsonObject)?.get("name").toString().trim('"') }))
        assertTrue(on.contains(ToolSpecs.SAVE_MEMORY))
    }

    @Test
    fun onlyMemoryWritesAreAutoApprovable() {
        val (registry, _) = registry()
        assertTrue(registry.isAutoApprovable(saveMemoryCall("c1", "x")))
        for (name in listOf(ToolSpecs.RUN_COMMAND, ToolSpecs.READ_FILE, ToolSpecs.WRITE_FILE, ToolSpecs.LIST_FILES)) {
            assertFalse("$name must always be confirmed", registry.isAutoApprovable(ToolCall(id = "c2", name = name)))
        }
    }

    @Test
    fun denialProducesANonSuccessResult() {
        val (registry, _) = registry()
        val denial = registry.denialOutcome()
        assertFalse(denial.succeeded)
        assertTrue(denial.resultJson.isNotBlank())
    }

    @Test
    fun onlyEnabledMemoriesAreInjected() = runBlocking {
        val (_, memories) = registry()
        val kept = memories.add("Kept", MemorySource.User)
        memories.add("Paused", MemorySource.User)
        val paused = memories.items.value.first { it.content == "Paused" }
        memories.setEnabled(paused.id, false)

        assertEquals(listOf("Kept"), memories.enabledContents())
        assertEquals(kept.id, memories.items.value.first { it.content == "Kept" }.id)
    }
}
