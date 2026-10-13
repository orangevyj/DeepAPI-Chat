package com.orangestudio.deepapichat.data.transfer

import com.orangestudio.deepapichat.core.AppJson
import com.orangestudio.deepapichat.core.now
import com.orangestudio.deepapichat.core.stringValue
import com.orangestudio.deepapichat.data.model.Attachment
import com.orangestudio.deepapichat.data.store.AttachmentStore
import com.orangestudio.deepapichat.data.store.ConversationStore
import com.orangestudio.deepapichat.data.store.MemoryStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

data class ImportReport(
    val conversationsAdded: Int = 0,
    val memoriesAdded: Int = 0,
    val imagesWritten: Int = 0,
    val error: String? = null,
) {
    val ok: Boolean get() = error == null

    fun summary(): String = when {
        !ok -> error.orEmpty()
        conversationsAdded == 0 && memoriesAdded == 0 ->
            "Nothing new to import — everything in that file is already here."
        else -> buildString {
            append("Imported $conversationsAdded conversation")
            if (conversationsAdded != 1) append("s")
            if (memoriesAdded > 0) {
                append(" and $memoriesAdded memor")
                append(if (memoriesAdded == 1) "y" else "ies")
            }
            if (imagesWritten > 0) append(", plus $imagesWritten image")
            if (imagesWritten > 1) append("s")
            append(".")
        }
    }
}

/** A cheap preview shown before the user commits to an import. */
data class BundleSummary(
    val conversations: Int,
    val memories: Int,
    val images: Int,
    val exportedAt: Long,
) {
    companion object {
        fun of(bundle: ExportBundle) = BundleSummary(
            conversations = bundle.conversations.size,
            memories = bundle.memories.size,
            images = bundle.attachments.size,
            exportedAt = bundle.exportedAt,
        )
    }
}

/**
 * Export and import of conversations and memories as a single JSON document.
 *
 * The files are read and written by the caller through the Storage Access
 * Framework, so this class never touches `Uri`s and needs no storage permission.
 */
class TransferService(
    private val conversations: ConversationStore,
    private val memories: MemoryStore,
    private val attachments: AttachmentStore,
    private val appVersion: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Serializes conversations, optionally memories, and optionally the images
     * they reference, into one JSON document. Passing null for [conversationIds]
     * exports everything.
     */
    suspend fun export(
        conversationIds: Set<String>? = null,
        includeMemories: Boolean = true,
        includeAttachments: Boolean = true,
    ): String = withContext(io) {
        val selected = conversations.items.value
            .asSequence()
            .filter { conversationIds == null || it.id in conversationIds }
            .toList()

        val blobs = ArrayList<AttachmentBundle>()
        if (includeAttachments) {
            val seen = HashSet<String>()
            for (conversation in selected) {
                for (message in conversation.messages) {
                    for (attachment in message.attachments) {
                        if (attachment.id.isBlank() || !seen.add(attachment.id)) continue
                        val bytes = attachments.readBytes(attachment) ?: continue
                        blobs.add(
                            AttachmentBundle(
                                id = attachment.id,
                                fileName = attachment.fileName,
                                mimeType = attachment.mimeType,
                                base64 = java.util.Base64.getEncoder().encodeToString(bytes),
                            ),
                        )
                    }
                }
            }
        }

        val bundle = ExportBundle(
            exportedAt = now(),
            appVersion = appVersion,
            conversations = selected,
            memories = if (includeMemories) memories.items.value else emptyList(),
            attachments = blobs,
        )
        AppJson.pretty.encodeToString(ExportBundle.serializer(), bundle)
    }

    /** Parses and validates a document. Null means "not one of our exports". */
    fun parse(text: String): ExportBundle? = runCatching {
        val root = AppJson.pretty.decodeFromString(JsonElement.serializer(), text) as? JsonObject
            ?: return null
        if (root["format"].stringValue() != ExportBundle.FORMAT) return null
        AppJson.pretty.decodeFromString(ExportBundle.serializer(), text)
    }.getOrNull()

    fun readSummary(text: String): BundleSummary? = parse(text)?.let { BundleSummary.of(it) }

    suspend fun import(text: String, options: ImportOptions): ImportReport = withContext(io) {
        val bundle = parse(text)
            ?: return@withContext ImportReport(
                error = "That file is not a DeepAPI Chat export.",
            )

        val plan = BundleImporter.plan(
            bundle = bundle,
            existingConversationIds = conversations.items.value.map { it.id }.toSet(),
            existingMemoryContents = memories.items.value.map { it.content },
            options = options,
            stamp = now(),
        )

        var imagesWritten = 0
        for (blob in plan.attachments) {
            val bytes = runCatching {
                java.util.Base64.getDecoder().decode(blob.base64)
            }.getOrNull() ?: continue
            val attachment = Attachment(
                id = blob.id,
                fileName = blob.fileName,
                mimeType = blob.mimeType,
                relativePath = BundleImporter.relativePathFor(blob.id, blob.mimeType),
                sizeBytes = bytes.size.toLong(),
            )
            if (attachments.writeBytes(attachment, bytes)) imagesWritten++
        }

        if (plan.conversations.isNotEmpty()) {
            conversations.replaceAll(conversations.items.value + plan.conversations)
        }
        if (plan.memories.isNotEmpty()) {
            memories.replaceAll(memories.items.value + plan.memories)
        }

        ImportReport(
            conversationsAdded = plan.conversations.size,
            memoriesAdded = plan.memories.size,
            imagesWritten = imagesWritten,
        )
    }

    /** Filename offered in the create-document dialog. */
    fun suggestedFileName(conversation: com.orangestudio.deepapichat.data.model.Conversation?): String {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        val base = conversation?.title
            ?.replace(Regex("[^A-Za-z0-9 _-]"), "")
            ?.trim()
            ?.replace(Regex("\\s+"), "-")
            ?.take(40)
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: "deepapichat"
        return "$base-$stamp.json"
    }
}
