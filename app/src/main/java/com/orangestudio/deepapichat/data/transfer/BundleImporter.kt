package com.orangestudio.deepapichat.data.transfer

import com.orangestudio.deepapichat.core.Images
import com.orangestudio.deepapichat.core.newId
import com.orangestudio.deepapichat.data.model.Attachment
import com.orangestudio.deepapichat.data.model.Conversation
import com.orangestudio.deepapichat.data.model.Memory
import com.orangestudio.deepapichat.data.model.MemorySource
import com.orangestudio.deepapichat.data.store.Storage

/**
 * Pure merge logic for import.
 *
 * Two safety rules, both about not trusting the file:
 *  1. an attachment's `relativePath` is never taken from the bundle — it is
 *     recomputed from a validated id, so a hand-edited export cannot make the app
 *     write outside its attachments folder;
 *  2. an incoming conversation id that collides with an existing one is replaced
 *     and the title marked, so import never overwrites what is already here.
 */
object BundleImporter {

    /** Attachments ids are UUIDs; anything else is not something we generated. */
    private val SAFE_ID = Regex("[A-Za-z0-9_-]{4,64}")

    const val IMPORTED_SUFFIX = " (imported)"

    data class MergePlan(
        val conversations: List<Conversation>,
        val memories: List<Memory>,
        /** Blobs whose ids passed validation, with bytes still base64 encoded. */
        val attachments: List<AttachmentBundle>,
    ) {
        val isEmpty: Boolean
            get() = conversations.isEmpty() && memories.isEmpty() && attachments.isEmpty()
    }

    fun plan(
        bundle: ExportBundle,
        existingConversationIds: Set<String>,
        existingMemoryContents: List<String>,
        options: ImportOptions,
        stamp: Long,
    ): MergePlan {
        val usableBlobs = bundle.attachments.filter { SAFE_ID.matches(it.id) }

        val usedIds = existingConversationIds.toMutableSet()
        val conversations = bundle.conversations.map { incoming ->
            val id = if (incoming.id.isBlank() || incoming.id in usedIds) newId() else incoming.id
            val renamed = id != incoming.id
            usedIds.add(id)
            incoming.copy(
                id = id,
                title = if (renamed) incoming.title + IMPORTED_SUFFIX else incoming.title,
                messages = incoming.messages.map { message ->
                    message.copy(attachments = message.attachments.mapNotNull { sanitize(it) })
                },
            )
        }

        val memories = if (!options.importMemories) {
            emptyList()
        } else {
            val seen = existingMemoryContents.map { it.trim().lowercase() }.toMutableSet()
            bundle.memories.mapNotNull { incoming ->
                val content = incoming.content.trim()
                if (content.isEmpty()) return@mapNotNull null
                if (options.skipDuplicateMemories && !seen.add(content.lowercase())) return@mapNotNull null
                incoming.copy(
                    id = newId(),
                    content = content,
                    source = MemorySource.Imported,
                    createdAt = if (incoming.createdAt > 0L) incoming.createdAt else stamp,
                    updatedAt = stamp,
                )
            }
        }

        return MergePlan(conversations = conversations, memories = memories, attachments = usableBlobs)
    }

    /** Rebuilds an attachment against its validated id. */
    fun sanitize(attachment: Attachment): Attachment? {
        if (!SAFE_ID.matches(attachment.id)) return null
        return attachment.copy(
            relativePath = relativePathFor(attachment.id, attachment.mimeType),
        )
    }

    fun relativePathFor(id: String, mimeType: String): String =
        "${Storage.ATTACHMENTS}/$id.${Images.extensionFor(mimeType)}"
}
