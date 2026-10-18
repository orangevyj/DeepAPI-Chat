package com.orangestudio.deepapichat.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.LruCache
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Bitmap decoding, normalization and encoding.
 *
 * Every path in and out of the app goes through here so that images are:
 *  - downsampled before decode (a 12 MP photo is 48 MB as an ARGB_8888 bitmap),
 *  - rotated according to EXIF ([BitmapFactory] ignores EXIF orientation),
 *  - re-encoded without EXIF, which also strips embedded GPS coordinates,
 *  - capped at a bounded edge length so API payloads stay sane.
 */
object Bitmaps {

    /** Longest edge kept on disk. */
    const val MAX_STORED_EDGE = 1600

    /** Longest edge sent to a vision model (matches OpenAI's own guidance). */
    const val MAX_API_EDGE = 1568

    private const val JPEG_QUALITY = 88

    /** Refuse absurd inputs rather than OOM on the byte[] read. */
    private const val MAX_SOURCE_BYTES = 48L * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    data class Normalized(val bytes: ByteArray, val mimeType: String, val width: Int, val height: Int) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    fun cached(key: String): Bitmap? = cache.get(key)

    fun cache(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }

    fun evict(key: String) {
        cache.remove(key)
    }

    /** Decodes a stored file at up to [maxEdge] on its longest side. */
    fun decode(file: File, maxEdge: Int = MAX_API_EDGE): Bitmap? {
        if (!file.exists()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
            scaleDown(decoded, maxEdge)
        } catch (_: Throwable) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    /**
     * Reads an image from a content Uri and writes a normalized copy to [dest].
     * Returns the stored metadata, or null if the Uri isn't a usable image.
     */
    fun normalizeTo(context: Context, uri: Uri, dest: File): Normalized? {
        val source = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (_: Throwable) {
            null
        } ?: return null
        if (source.isEmpty() || source.size > MAX_SOURCE_BYTES) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_STORED_EDGE)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bitmap = BitmapFactory.decodeByteArray(source, 0, source.size, opts) ?: return null

        val rotation = exifRotation(source)
        if (rotation != 0f) {
            val matrix = Matrix().apply { postRotate(rotation) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            bitmap = rotated
        }
        bitmap = scaleDown(bitmap, MAX_STORED_EDGE) ?: return null

        // Preserve transparency by keeping PNG for images that actually have alpha.
        val keepAlpha = bitmap.hasAlpha()
        val mime = if (keepAlpha) "image/png" else "image/jpeg"
        val bytes = if (keepAlpha) {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        } else {
            compressToJpeg(bitmap)
        }

        val result = Normalized(bytes, mime, bitmap.width, bitmap.height)
        if (keepAlpha) bitmap.recycle()
        return try {
            dest.parentFile?.mkdirs()
            dest.writeBytes(result.bytes)
            result
        } catch (_: Throwable) {
            null
        }
    }

    fun compressToJpeg(bitmap: Bitmap, quality: Int = JPEG_QUALITY): ByteArray =
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }

    /** Decodes already-stored bytes for the in-process cache. */
    fun decodeBytes(bytes: ByteArray, maxEdge: Int = MAX_API_EDGE): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) {
            null
        } else {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (decoded == null) null else scaleDown(decoded, maxEdge)
        }
    } catch (_: Throwable) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    /**
     * Largest power of two that keeps both edges at or above twice [maxEdge].
     * Deliberately conservative: it under-samples rather than ever exceeding
     * twice the target, so the exact scale afterwards is at most 2x.
     */
    internal fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }

    private fun scaleDown(bitmap: Bitmap, maxEdge: Int): Bitmap? {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / longest.toFloat()
        val targetW = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val targetH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        if (scaled != bitmap) bitmap.recycle()
        return scaled
    }

    private fun exifRotation(source: ByteArray): Float = try {
        val exif = ByteArrayInputStream(source).use { ExifInterface(it) }
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } catch (_: Throwable) {
        0f
    }
}
