package com.orangestudio.deepapichat.data.transfer

import com.orangestudio.deepapichat.core.JsonStore
import com.orangestudio.deepapichat.data.model.Message
import com.orangestudio.deepapichat.data.model.Role
import com.orangestudio.deepapichat.data.store.AttachmentStore
import com.orangestudio.deepapichat.data.store.ConversationStore
import com.orangestudio.deepapichat.data.store.MemoryStore
import com.orangestudio.deepapichat.data.store.sortedForDisplay
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TransferServiceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class Rig(root: File) {
        val store = JsonStore(root)
        val conversations = ConversationStore(store)
        val memories = MemoryStore(store)
        val attachments = AttachmentStore(store)
        val service = TransferService(conversations, memories, attachments, appVersion = "test")
    }

    private fun rig(name: String) = Rig(File(temp.root, name))

    @Test
    fun exportThenImportRoundTripsEverything() = runBlocking {
        val source = rig("source")
        source.conversations.load()
        source.memories.load()

        val created = source.conversations.create("Trip planning")
        source.conversations.upsert(
            created.copy(
                messages = listOf(
                    Message(id = "m1", role = Role.User, content = "Plan a weekend in Kyoto"),
                    Message(id = "m2", role = Role.Assistant, content = "Here is a plan.", model = "deepseek-chat"),
                ),
            ),
        )
        source.memories.add("Prefers jasmine tea")

        val exported = source.service.export()
        assertTrue(exported.contains("deepapichat.export"))

        val summary = source.service.readSummary(exported)
        assertNotNull(summary)
        assertEquals(1, summary!!.conversations)
        assertEquals(1, summary.memories)

        val target = rig("target")
        target.conversations.load()
        target.memories.load()
        val report = target.service.import(exported, ImportOptions())

        assertNull(report.error)
        assertEquals(1, report.conversationsAdded)
        assertEquals(1, report.memoriesAdded)
        val restored = target.conversations.items.value.single()
        assertEquals("Trip planning", restored.title)
        assertEquals(2, restored.messages.size)
        assertEquals("Plan a weekend in Kyoto", restored.messages.first().content)
        assertEquals("Here is a plan.", restored.messages.last().content)
        assertEquals(listOf("Prefers jasmine tea"), target.memories.items.value.map { it.content })
    }

    @Test
    fun importingTheSameBundleTwiceBuildsOnTheExistingCopy() = runBlocking {
        val source = rig("source")
        source.conversations.load()
        source.memories.load()
        val created = source.conversations.create("Notes")
        source.conversations.upsert(created.copy(messages = listOf(Message(id = "m1", role = Role.User, content = "hi"))))
        val exported = source.service.export()

        val target = rig("target")
        target.conversations.load()
        target.memories.load()
        target.service.import(exported, ImportOptions())
        val second = target.service.import(exported, ImportOptions())

        assertEquals(1, second.conversationsAdded)
        assertEquals(2, target.conversations.items.value.size)
        assertTrue(target.conversations.items.value.any { it.title.endsWith(BundleImporter.IMPORTED_SUFFIX) })
    }

    @Test
    fun exportingASubsetLeavesOtherConversationsOut() = runBlocking {
        val rig = rig("subset")
        rig.conversations.load()
        rig.memories.load()
        val keep = rig.conversations.create("Keep")
        rig.conversations.create("Drop")

        val exported = rig.service.export(setOf(keep.id))
        val parsed = rig.service.parse(exported)
        assertEquals(listOf("Keep"), parsed!!.conversations.map { it.title })
    }

    @Test
    fun unknownFilesAreRejectedInsteadOfGuessedAt() {
        val rig = rig("garbage")
        assertNull(rig.service.parse("{\"hello\":true}"))
        assertNull(rig.service.parse("not json at all"))
        assertNull(rig.service.readSummary("{\"format\":\"something.else\"}"))
    }

    @Test
    fun importingGarbageReportsAnErrorAndChangesNothing() = runBlocking {
        val rig = rig("garbage2")
        rig.conversations.load()
        rig.memories.load()

        val report = rig.service.import("not a backup", ImportOptions())

        assertNotNull(report.error)
        assertEquals(0, report.conversationsAdded)
        assertTrue(rig.conversations.items.value.isEmpty())
    }

    @Test
    fun memoriesCanBeLeftOutOfAnExport() = runBlocking {
        val rig = rig("nomemories")
        rig.conversations.load()
        rig.memories.load()
        rig.memories.add("Secret")

        val exported = rig.service.export(includeMemories = false)
        val parsed = rig.service.parse(exported)
        assertTrue(parsed!!.memories.isEmpty())
    }

    @Test
    fun suggestedFileNameIsDatedAndJson() = runBlocking {
        val rig = rig("name")
        rig.conversations.load()
        val created = rig.conversations.create("Trip planning")
        val name = rig.service.suggestedFileName(created)

        assertTrue("unexpected name: $name", name.endsWith(".json"))
        assertTrue("unexpected name: $name", Regex("\\d{4}-\\d{2}-\\d{2}").containsMatchIn(name))
        assertTrue(rig.service.suggestedFileName(null).endsWith(".json"))
    }

    @Test
    fun pinnedConversationsSortFirst() {
        val older = com.orangestudio.deepapichat.data.model.Conversation(id = "a", title = "Old", updatedAt = 1L)
        val newer = com.orangestudio.deepapichat.data.model.Conversation(id = "b", title = "New", updatedAt = 5L)
        val pinned = com.orangestudio.deepapichat.data.model.Conversation(id = "c", title = "Pinned", updatedAt = 2L, pinned = true)

        assertEquals(listOf("c", "b", "a"), listOf(pinned, older, newer).sortedForDisplay().map { it.id })
    }
}
