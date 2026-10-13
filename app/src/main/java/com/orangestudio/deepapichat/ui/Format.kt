package com.orangestudio.deepapichat.ui

import com.orangestudio.deepapichat.data.model.Message
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** "14:05" today, "Yesterday 14:05", "3 Mar", or "3 Mar 2025". */
fun formatTimestamp(millis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    if (millis <= 0L) return ""
    val now = Calendar.getInstance()
    now.timeInMillis = nowMillis
    val then = Calendar.getInstance()
    then.timeInMillis = millis

    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
    if (sameDay) return clock

    val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (yesterday.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        yesterday.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    ) {
        return "Yesterday $clock"
    }

    val pattern = if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)) "d MMM" else "d MMM yyyy"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
}

/** One-line summary of a message for the conversation list. */
fun messagePreview(message: Message?): String {
    if (message == null) return "No messages yet"
    val text = message.content.replace(Regex("\\s+"), " ").trim()
    val body = when {
        text.isNotBlank() -> text
        !message.error.isNullOrBlank() -> message.error
        message.toolCalls.isNotEmpty() -> "Asked to use a tool"
        message.attachments.isNotEmpty() -> "Sent an image"
        else -> ""
    }
    return body.take(PREVIEW_LENGTH)
}

private const val PREVIEW_LENGTH = 90
