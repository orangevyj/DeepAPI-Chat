package com.orangestudio.deepapichat.data.transfer

import com.orangestudio.deepapichat.data.model.Conversation
import com.orangestudio.deepapichat.data.model.Memory
import kotlinx.serialization.Serializable

/**
 * The on-disk format for export and import.
 *
 * Export files are the same shape as the in-app stores, so an export is also a
 * readable backup. Images are embedded as base64 ([AttachmentBundle]) because a
 * single self-contained file the user can move anywhere is worth the size.
 */
@Serializable
data class ExportBundle(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val exportedAt: Long = 0L,
    val appVersion: String = "",
    val conversations: List<Conversation> = emptyList(),
    val memories: List<Memory> = emptyList(),
    val attachments: List<AttachmentBundle> = emptyList(),
) {
    companion object {
        const val FORMAT = "deepapichat.export"
        const val VERSION = 1
    }
}

/** One embedded image. [id] matches the referenced [com.orangestudio.deepapichat.data.model.Attachment]. */
@Serializable
data class AttachmentBundle(
    val id: String = "",
    val fileName: String = "",
    val mimeType: String = "image/jpeg",
    val base64: String = "",
)

/** Options shown in the import dialog. */
@Serializable
data class ImportOptions(
    /** Add the incoming conversations to what is already here. */
    val mergeConversations: Boolean = true,
    /** Import memories as well. */
    val importMemories: Boolean = true,
    /** Skip memories whose text already exists. */
    val skipDuplicateMemories: Boolean = true,
)
