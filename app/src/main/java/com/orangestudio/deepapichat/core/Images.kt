package com.orangestudio.deepapichat.core

/** What MIME types we can send as vision input, and their file extensions. */
object Images {

    private val MIME_TO_EXT = mapOf(
        "image/jpeg" to "jpg",
        "image/jpg" to "jpg",
        "image/png" to "png",
        "image/webp" to "webp",
        "image/gif" to "gif",
        "image/heic" to "heic",
        "image/heif" to "heif",
    )

    private val EXT_TO_MIME = MIME_TO_EXT.entries.associate { (mime, ext) -> ext to mime }

    fun extensionFor(mimeType: String): String =
        MIME_TO_EXT[mimeType.lowercase().substringBefore(';').trim()] ?: "jpg"

    fun mimeForExtension(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return EXT_TO_MIME[ext] ?: "image/jpeg"
    }

    /**
     * Builds a `data:` URL. This is the only image form OpenAI-compatible
     * endpoints accept inline, and it deliberately avoids any server-side fetch.
     */
    fun dataUrl(mimeType: String, bytes: ByteArray): String {
        // java.util.Base64 (API 26+) rather than android.util.Base64, so this stays
        // a plain-JVM function unit tests can call directly.
        val base64 = java.util.Base64.getEncoder().encodeToString(bytes)
        return "data:$mimeType;base64,$base64"
    }

    fun isSupportedMime(mimeType: String): Boolean =
        MIME_TO_EXT.containsKey(mimeType.lowercase().substringBefore(';').trim())

    /**
     * Whether an attachment may be sent to the model inline. A file that lives in the
     * chat's sandbox workspace is never an inline image, whatever its type claims to
     * be: the assistant opens it with its own tools instead. This is the only place
     * that decides what travels inline - an attachment that is not inline is reported
     * to the model as a path it can read, never as a payload.
     */
    fun isInlineImage(mimeType: String, inWorkspace: Boolean): Boolean =
        !inWorkspace && isSupportedMime(mimeType)
}
