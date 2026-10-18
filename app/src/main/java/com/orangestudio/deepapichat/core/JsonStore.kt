package com.orangestudio.deepapichat.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/**
 * JSON codecs used for everything on disk.
 *
 * [pretty] is for files a human might open (conversations, exports);
 * [compact] is for request bodies sent over the wire.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
object AppJson {
    val pretty: Json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
        // Importing a bundle written by a newer build must not hard-fail: an
        // unknown enum value falls back to the property's default instead.
        coerceInputValues = true
    }

    val compact: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
    }
}

/**
 * Reads and writes JSON documents under a single directory tree.
 *
 * Writes go to a sibling `.tmp` file which is fsync'd and then renamed over the
 * target, so a crash mid-write can never leave a half-written document behind.
 *
 * Reads never throw: a missing file, unreadable file or malformed document all
 * come back as `null`. A malformed document is *preserved* as `<name>.corrupt`
 * rather than being silently overwritten, so user data is recoverable.
 */
class JsonStore(val root: File) {

    fun ensureRoot() {
        if (!root.exists()) root.mkdirs()
    }

    fun file(vararg parts: String): File = parts.fold(root) { acc, part -> File(acc, part) }

    fun <T> read(file: File, serializer: KSerializer<T>): T? {
        if (!file.exists()) return null
        return try {
            AppJson.pretty.decodeFromString(serializer, file.readText(Charsets.UTF_8))
        } catch (_: Throwable) {
            quarantine(file)
            null
        }
    }

    fun <T> write(file: File, serializer: KSerializer<T>, value: T): Boolean =
        try {
            writeText(file, AppJson.pretty.encodeToString(serializer, value))
        } catch (_: Throwable) {
            false
        }

    fun writeText(file: File, text: String): Boolean {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        return try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (file.exists() && !file.delete()) return false
            if (tmp.renameTo(file)) true else tmp.copyTo(file, overwrite = true).let { tmp.delete(); true }
        } catch (_: Throwable) {
            tmp.delete()
            false
        }
    }

    fun delete(file: File): Boolean = !file.exists() || file.delete()

    /** Returns the `.json` files directly inside [dir], creating the dir if needed. */
    fun jsonFilesIn(dir: File): List<File> {
        if (!dir.exists()) dir.mkdirs()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.toList().orEmpty()
    }

    private fun quarantine(file: File) {
        val target = File(file.parentFile, file.name + ".corrupt")
        try {
            if (target.exists()) target.delete()
            file.renameTo(target)
        } catch (_: Throwable) {
            // Nothing more we can do; the read already failed.
        }
    }
}
