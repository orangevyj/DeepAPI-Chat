package com.orangestudio.deepapichat.data.api

import com.orangestudio.deepapichat.core.stringValue
import com.orangestudio.deepapichat.data.model.Usage
import com.orangestudio.deepapichat.data.model.ToolCall
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * DTOs for the OpenAI-compatible chat-completions wire format.
 *
 * Every field is nullable with a default so a server can omit anything, and
 * unknown fields are ignored by the configured [com.orangestudio.deepapichat.core.AppJson]
 * instances. Serde names stay snake_case because that is what the API speaks.
 */

@Serializable
data class WireError(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null,
)

/** Envelope for `{"error": {...}}` responses. */
@Serializable
data class WireErrorEnvelope(val error: WireError? = null)

/**
 * One entry of `GET /models`. Only the id is used; the rest of an entry is metadata
 * that differs between providers and that the app has no opinion about.
 */
@Serializable
data class WireModel(val id: String = "")

/** The `GET /models` envelope: `{"object":"list","data":[…]}`. */
@Serializable
data class WireModelsEnvelope(val data: List<WireModel> = emptyList())

@Serializable
data class WireUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
) {
    fun toUsage(): Usage = Usage(promptTokens, completionTokens, totalTokens)
}

@Serializable
data class DeltaFunction(
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
data class DeltaToolCall(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: DeltaFunction? = null,
)

@Serializable
data class ChunkDelta(
    val role: String? = null,
    val content: String? = null,
    /** deepseek-reasoner streams its chain of thought here. */
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<DeltaToolCall>? = null,
)

@Serializable
data class ChunkChoice(
    val index: Int = 0,
    val delta: ChunkDelta = ChunkDelta(),
    @SerialName("finish_reason") val finishReason: String? = null,
)

/** One `data:` payload of a streaming response. */
@Serializable
data class ChatChunk(
    val id: String? = null,
    val model: String? = null,
    val choices: List<ChunkChoice> = emptyList(),
    val usage: WireUsage? = null,
    val error: WireError? = null,
)

@Serializable
data class ResponseToolCall(
    val id: String? = null,
    val type: String? = null,
    val function: DeltaFunction? = null,
)

@Serializable
data class ResponseMessage(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ResponseToolCall>? = null,
)

@Serializable
data class ResponseChoice(
    val index: Int = 0,
    val message: ResponseMessage = ResponseMessage(),
    @SerialName("finish_reason") val finishReason: String? = null,
)

/** A whole (non-streamed) chat-completions response. */
@Serializable
data class ChatResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<ResponseChoice> = emptyList(),
    val usage: WireUsage? = null,
    val error: WireError? = null,
)

/**
 * Reassembles a streamed response.
 *
 * Tool calls arrive as fragments: the first delta for an index carries the id and
 * function name, and every later delta carries an `arguments` string that must be
 * concatenated. Nothing here is transport-aware, which makes it directly testable.
 */
class DeltaAccumulator {

    private class PartialCall {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }

    private val contentBytes = StringBuilder()
    private val reasoningBytes = StringBuilder()
    private val partialCalls = sortedMapOf<Int, PartialCall>()

    var model: String? = null
        private set
    var finishReason: String? = null
        private set
    var usage: Usage? = null
        private set

    /** Set when the server reports an error inside an otherwise-200 stream. */
    var error: String? = null
        private set

    val content: String get() = contentBytes.toString()
    val reasoning: String get() = reasoningBytes.toString()

    fun apply(chunk: ChatChunk) {
        chunk.error?.let { error = it.message ?: it.type ?: "The server reported an error." }
        chunk.model?.let { model = it }
        chunk.usage?.let { usage = it.toUsage() }

        val choice = chunk.choices.firstOrNull() ?: return
        choice.finishReason?.let { finishReason = it }
        choice.delta.content?.let { contentBytes.append(it) }
        choice.delta.reasoningContent?.let { reasoningBytes.append(it) }
        choice.delta.toolCalls?.forEach { delta ->
            val partial = partialCalls.getOrPut(delta.index) { PartialCall() }
            delta.id?.takeIf { it.isNotBlank() }?.let { partial.id = it }
            delta.function?.name?.takeIf { it.isNotBlank() }?.let { partial.name = it }
            delta.function?.arguments?.let { partial.arguments.append(it) }
        }
    }

    fun toolCalls(): List<ToolCall> = partialCalls.entries.mapNotNull { (index, partial) ->
        val name = partial.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        ToolCall(
            // Some servers never send an id; synthesize a stable one so the tool
            // result can be matched back up.
            id = partial.id?.takeIf { it.isNotBlank() } ?: "call_$index",
            name = name,
            arguments = partial.arguments.toString().ifBlank { "{}" },
        )
    }

    fun hasText(): Boolean = contentBytes.isNotEmpty() || reasoningBytes.isNotEmpty()
}

/**
 * Pulls a human-readable message out of an error body, falling back through
 * `{"error":{"message":...}}`, then the raw body, then the transport exception.
 */
fun errorMessageFrom(body: String?, throwable: Throwable?, httpCode: Int?): String {
    val fromEnvelope = body
        ?.takeIf { it.isNotBlank() }
        ?.let { raw ->
            runCatching {
                com.orangestudio.deepapichat.core.AppJson.compact
                    .decodeFromString(WireErrorEnvelope.serializer(), raw)
                    .error
                    ?.message
            }.getOrNull()
        }
        ?.takeIf { it.isNotBlank() }

    val base = fromEnvelope
        ?: body?.takeIf { it.isNotBlank() && it.length <= 400 }
        ?: throwable?.message?.takeIf { it.isNotBlank() }
        ?: "The request failed."

    val prefix = httpCode?.let { "HTTP $it: " }.orEmpty()
    val hint = when (httpCode) {
        401, 403 -> " Check the API key in Settings."
        404 -> " Check the base URL in Settings."
        429 -> " Rate limited or out of credit."
        else -> ""
    }
    return "$prefix$base$hint"
}

/** Convenience for pulling a field out of tool-call arguments. */
fun JsonObject.argString(key: String): String? = this[key].stringValue()
