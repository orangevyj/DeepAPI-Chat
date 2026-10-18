package com.orangestudio.deepapichat.data.store

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.orangestudio.deepapichat.core.Bitmaps
import com.orangestudio.deepapichat.core.Images
import com.orangestudio.deepapichat.core.JsonStore
import com.orangestudio.deepapichat.core.newId
import com.orangestudio.deepapichat.data.model.Attachment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns the image blobs referenced by [Attachment.relativePath].
 *
 * Conversations store only metadata, so deleting a conversation does not
 * implicitly delete images that an export bundle may still be referencing.
 */
class AttachmentStore(
    private val store: JsonStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val dir: File get() = store.file(Storage.ATTACHMENTS)

    fun fileFor(attachment: Attachment): File = File(store.root, attachment.relativePath)

    fun exists(attachment: Attachment): Boolean = fileFor(attachment).let { it.isFile && it.length() > 0L }

    fun delete(attachment: Attachment): Boolean = store.delete(fileFor(attachment))

    /**
     * Copies an image out of a content Uri into private storage, normalizing
     * size, orientation and format on the way (see [Bitmaps.normalizeTo]).
     */
    suspend fun importFromUri(context: Context, uri: Uri, originalName: String?): Attachment? =
        withContext(io) {
            val id = newId()
            // Guess a starting extension from the display name; the real MIME is
            // whatever normalizeTo decides after looking at the pixels.
            val guess = originalName?.let { Images.mimeForExtension(it) } ?: "image/jpeg"
            val pending = File(dir, "$id.${Images.extensionFor(guess)}")
            val normalized = Bitmaps.normalizeTo(context, uri, pending) ?: return@withContext null

            val finalExt = Images.extensionFor(normalized.mimeType)
            val target = File(dir, "$id.$finalExt")
            if (target.absolutePath != pending.absolutePath && pending.exists()) {
                if (!pending.renameTo(target)) {
                    pending.copyTo(target, overwrite = true)
                    pending.delete()
                }
            }

            val name = originalName?.substringBeforeLast('.').orEmpty().takeIf { it.isNotBlank() }
                ?: "image"
            Attachment(
                id = id,
                fileName = "$name.$finalExt",
                mimeType = normalized.mimeType,
                relativePath = "${Storage.ATTACHMENTS}/$id.$finalExt",
                width = normalized.width,
                height = normalized.height,
                sizeBytes = target.length(),
            )
        }

    /**
     * Copies any file out of a content Uri, byte for byte, into [into] - the
     * sandbox workspace the assistant can read. Images do not come through here:
     * they are normalized so the model can actually look at them, which is a
     * different job (see [importFromUri]).
     */
    suspend fun importFile(
        context: Context,
        uri: Uri,
        into: File,
        displayName: String?,
    ): Attachment? = withContext(io) {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val wanted = safeFileName(displayName ?: queryDisplayName(resolver, uri) ?: "file")
        into.mkdirs()
        val target = uniqueTarget(into, wanted)
        val copied = try {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } != null
        } catch (_: Throwable) {
            false
        }
        if (!copied) {
            target.delete()
            return@withContext null
        }
        Attachment(
            id = newId(),
            fileName = target.name,
            mimeType = mime,
            relativePath = target.relativeTo(store.root).invariantSeparatorsPath,
            sizeBytes = target.length(),
            inWorkspace = true,
        )
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()

    /**
     * Keeps the name recognisable but stops it pointing anywhere: a picked file
     * called `../../settings.json` must not land outside the chat's workspace.
     */
    private fun safeFileName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = base.filter { it.isLetterOrDigit() || it in "._- " }
        return cleaned.take(80).ifBlank { "file" }
    }

    /** Two files of the same name in one chat must not overwrite each other. */
    private fun uniqueTarget(dir: File, name: String): File {
        if (!File(dir, name).exists()) return File(dir, name)
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        var attempt = 2
        while (true) {
            val candidate = File(dir, "$stem-$attempt$ext")
            if (!candidate.exists()) return candidate
            attempt++
        }
    }

    suspend fun readBytes(attachment: Attachment): ByteArray? = withContext(io) {
        val file = fileFor(attachment)
        try {
            if (file.isFile && file.length() > 0L) file.readBytes() else null
        } catch (_: Throwable) {
            null
        }
    }

    /** Used when an export bundle embeds its images. */
    suspend fun writeBytes(attachment: Attachment, bytes: ByteArray): Boolean = withContext(io) {
        try {
            val file = fileFor(attachment)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
