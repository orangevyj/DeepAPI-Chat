package com.orangestudio.deepapichat.data.transfer

import com.orangestudio.deepapichat.data.model.Attachment
import com.orangestudio.deepapichat.data.model.Conversation
import com.orangestudio.deepapichat.data.model.Memory
import com.orangestudio.deepapichat.data.model.MemorySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BundleImporterTest {

    private val bundleFormat = "deepapichat.export"

    private fun conversation(id: String, title: String = "Chat") =
        Conversation(id = id, title = title, createdAt = 1_000L, updatedAt = 1_000L)

    private fun bundle(
        conversations: List<Conversation> = emptyList(),
        memories: List<Memory> = emptyList(),
        attachments: List<AttachmentBundle> = emptyList(),
    ) = ExportBundle(
        format = bundleFormat,
        version = 1,
        exportedAt = 1_000L,
        appVersion = "test",
        conversations = conversations,
        memories = memories,
        attachments = attachments,
    )

    @Test
    fun relativePathIsRebuiltFromTheIdAndMimeType() {
        assertEquals("attachments/abc123.png", BundleImporter.relativePathFor("abc123", "image/png"))
        assertEquals("attachments/abc123.jpg", BundleImporter.relativePathFor("abc123", "application/octet-stream"))
    }

    @Test
    fun pathTraversalInAnAttachmentIsRejected() {
        assertNull(
            BundleImporter.sanitize(
                Attachment(id = "../../etc/passwd", mimeType = "image/png", relativePath = "../../etc/passwd"),
            ),
        )
        assertNull(BundleImporter.sanitize(Attachment(id = "a/b", mimeType = "image/png")))
        assertNull(BundleImporter.sanitize(Attachment(id = "no", mimeType = "image/png")))

        val clean = BundleImporter.sanitize(
            Attachment(id = "abc123", mimeType = "image/png", relativePath = "../../escape.png"),
        )
        assertEquals("attachments/abc123.png", clean?.relativePath)
    }

    @Test
    fun idsThatAlreadyExistGetANewIdAndAMarkedTitle() {
        val plan = BundleImporter.plan(
            bundle = bundle(conversations = listOf(conversation("existing", "Trip plan"))),
            existingConversationIds = setOf("existing"),
            existingMemoryContents = emptyList(),
            options = ImportOptions(),
            stamp = 2_000L,
        )

        val imported = plan.conversations.single()
        assertNotEquals("existing", imported.id)
        assertEquals("Trip plan${BundleImporter.IMPORTED_SUFFIX}", imported.title)
    }

    @Test
    fun freshIdsAreKeptAsTheyAre() {
        val plan = BundleImporter.plan(
            bundle = bundle(conversations = listOf(conversation("fresh", "Trip plan"))),
            existingConversationIds = emptySet(),
            existingMemoryContents = emptyList(),
            options = ImportOptions(),
            stamp = 2_000L,
        )

        assertEquals("fresh", plan.conversations.single().id)
        assertEquals("Trip plan", plan.conversations.single().title)
    }

    @Test
    fun duplicateMemoriesAreSkippedCaseInsensitively() {
        val incoming = listOf(Memory(id = "m1", content = "Prefers tea"), Memory(id = "m2", content = "Lives in Berlin"))

        val withDedupe = BundleImporter.plan(
            bundle = bundle(memories = incoming),
            existingConversationIds = emptySet(),
            existingMemoryContents = listOf("  prefers TEA "),
            options = ImportOptions(skipDuplicateMemories = true),
            stamp = 2_000L,
        )
        assertEquals(listOf("Lives in Berlin"), withDedupe.memories.map { it.content })

        val withoutDedupe = BundleImporter.plan(
            bundle = bundle(memories = incoming),
            existingConversationIds = emptySet(),
            existingMemoryContents = listOf("prefers TEA"),
            options = ImportOptions(skipDuplicateMemories = false),
            stamp = 2_000L,
        )
        assertEquals(2, withoutDedupe.memories.size)
    }

    @Test
    fun memoriesCanBeExcludedEntirely() {
        val plan = BundleImporter.plan(
            bundle = bundle(memories = listOf(Memory(id = "m1", content = "Prefers tea"))),
            existingConversationIds = emptySet(),
            existingMemoryContents = emptyList(),
            options = ImportOptions(importMemories = false),
            stamp = 2_000L,
        )
        assertTrue(plan.memories.isEmpty())
    }

    @Test
    fun importedMemoriesCarryTheImportedSource() {
        val plan = BundleImporter.plan(
            bundle = bundle(memories = listOf(Memory(id = "m1", content = "Prefers tea", source = MemorySource.User))),
            existingConversationIds = emptySet(),
            existingMemoryContents = emptyList(),
            options = ImportOptions(),
            stamp = 2_000L,
        )
        assertEquals(MemorySource.Imported, plan.memories.single().source)
    }

    @Test
    fun unsafeAttachmentBlobsAreDroppedBeforeAnythingIsWritten() {
        val plan = BundleImporter.plan(
            bundle = bundle(
                attachments = listOf(
                    AttachmentBundle(id = "../../escape", fileName = "e.png", mimeType = "image/png", base64 = "AAAA"),
                    AttachmentBundle(id = "safe1234", fileName = "s.png", mimeType = "image/png", base64 = "AAAA"),
                ),
            ),
            existingConversationIds = emptySet(),
            existingMemoryContents = emptyList(),
            options = ImportOptions(),
            stamp = 2_000L,
        )

        assertEquals(listOf("safe1234"), plan.attachments.map { it.id })
    }
}
