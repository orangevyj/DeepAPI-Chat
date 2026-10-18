package com.orangestudio.deepapichat.domain

import com.orangestudio.deepapichat.core.jsonObject
import com.orangestudio.deepapichat.data.model.Defaults
import com.orangestudio.deepapichat.data.model.Message
import com.orangestudio.deepapichat.data.model.Role
import kotlinx.serialization.json.JsonObject

/**
 * Everything the AI-written chat titles need, as pure functions: the prompt we
 * send and the clean-up we apply to what comes back. No Android types, no I/O,
 * so this is unit-testable without a device or a network.
 */
object Titles {

    /** Longest title we accept. Roughly a top-bar width. */
    const val MAX_LENGTH = 48

    /** How many messages may pile up before a fresh title is worth offering. */
    const val REFRESH_AFTER = 8

    private const val INSTRUCTIONS =
        "You name chat conversations. Reply with a title only: 2 to 5 words, " +
            "no quotes, no trailing full stop, no \"Title:\" prefix, no explanation. " +
            "Name the topic, not the act of chatting."

    /**
     * A short transcript of the conversation so far: enough for the model to know
     * what it is about, without sending — or paying for — the whole thing.
     */
    fun seed(
        messages: List<Message>,
        maxTurns: Int = 6,
        maxCharsPerTurn: Int = 400,
    ): String = messages.asSequence()
        .filter { it.role == Role.User || it.role == Role.Assistant }
        .filter { it.content.isNotBlank() }
        .toList()
        .takeLast(maxTurns)
        .joinToString("\n") { message ->
            val who = if (message.role == Role.User) "User" else "Assistant"
            "$who: ${message.content.trim().take(maxCharsPerTurn)}"
        }
        .trim()

    /**
     * The model that writes titles. A reasoning model would spend thinking tokens
     * on a five-word answer, so it falls back to the plain chat model.
     */
    fun titleModel(chatModel: String): String {
        val model = chatModel.trim().ifBlank { Defaults.MODEL }
        return if (model.contains("reasoner", ignoreCase = true)) Defaults.MODEL else model
    }

    /** A minimal non-streaming request: no tools, no history, a hard token cap. */
    fun requestBody(model: String, seed: String, maxTokens: Int = 24): JsonObject = jsonObject {
        put("model", titleModel(model))
        arr("messages") {
            addObject {
                put("role", "system")
                put("content", INSTRUCTIONS)
            }
            addObject {
                put("role", "user")
                put("content", "Conversation so far:\n\n$seed\n\nTitle:")
            }
        }
        put("max_tokens", maxTokens)
        put("stream", false)
        put("temperature", 0.3)
    }

    /**
     * Turns a raw completion into a usable title, or null when there is nothing
     * usable in it. Models routinely answer with quotes, a trailing full stop or
     * a whole sentence despite being told not to.
     */
    fun sanitize(raw: String): String? {
        val firstLine = raw.trim().lineSequence().firstOrNull().orEmpty().trim()
        if (firstLine.isEmpty()) return null
        var text = firstLine.removePrefix("Title:").removePrefix("title:").trim()
        // Strip the decoration models wrap titles in — quotes, backticks, bold
        // markers — together with any sentence punctuation left on the ends.
        text = text.trim { it in ".,:;!?\"'`*_ \t" }
        text = text.replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return null
        // A sentence or a paragraph is a refusal or a ramble, not a title.
        if (text.count { it == ' ' } > 6) return null
        if (text.length > MAX_LENGTH) text = text.take(MAX_LENGTH).trimEnd()
        return text.ifBlank { null }
    }
}
