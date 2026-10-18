package com.orangestudio.deepapichat.core

import java.util.UUID

/** Filesystem-safe unique id used for conversations, messages and attachments. */
fun newId(): String = UUID.randomUUID().toString()

fun now(): Long = System.currentTimeMillis()

/**
 * Turns arbitrary text into a single line suitable for a conversation title.
 */
fun titleFrom(text: String, maxLength: Int = 48): String {
    val collapsed = text.replace(Regex("\\s+"), " ").trim()
    if (collapsed.isEmpty()) return "New chat"
    return if (collapsed.length <= maxLength) collapsed else collapsed.take(maxLength).trimEnd() + "…"
}
