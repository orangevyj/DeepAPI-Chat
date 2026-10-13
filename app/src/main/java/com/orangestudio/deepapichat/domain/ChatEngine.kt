package com.orangestudio.deepapichat.domain

import com.orangestudio.deepapichat.core.Images
import com.orangestudio.deepapichat.core.LruCacheMap
import com.orangestudio.deepapichat.core.newId
import com.orangestudio.deepapichat.core.now
import com.orangestudio.deepapichat.data.api.CompleteResult
import com.orangestudio.deepapichat.data.api.DeltaAccumulator
import com.orangestudio.deepapichat.data.api.OpenAiClient
import com.orangestudio.deepapichat.data.api.StreamEvent
import com.orangestudio.deepapichat.data.model.AppSettings
import com.orangestudio.deepapichat.data.model.Conversation
import com.orangestudio.deepapichat.data.model.Message
import com.orangestudio.deepapichat.data.model.Role
import com.orangestudio.deepapichat.data.model.ToolCall
import com.orangestudio.deepapichat.data.store.AttachmentStore
import kotlinx.coroutines.flow.collect

/**
 * Runs exactly one assistant turn: build the request, stream it, hand back the
 * resulting message.
 *
 * The engine holds no conversation state. The caller owns the message list, the
 * approval loop and persistence. That split is what keeps the tool-approval flow
 * in one place when the sandboxed terminal arrives.
 *
 * [onProgress] is called with an entire snapshot of the message in flight, not a
 * delta, so the UI never has to reassemble anything.
 */
class ChatEngine(
    private val client: OpenAiClient,
    private val attachments: AttachmentStore,
    private val tools: ToolRegistry,
) {
    /**
     * Base64 data URLs are expensive to build (read + encode a few hundred KB),
     * so they are cached per attachment id. Attachments are immutable once
     * written, which makes the id a valid cache key.
     */
    private val payloadCache = LruCacheMap<String, String>(PAYLOAD_CACHE_ENTRIES)

    suspend fun imagePayloads(messages: List<Message>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (message in messages) {
            for (attachment in message.attachments) {
                // Only an image the app stored for the model goes out inline. A file in
                // the chat's workspace is opened by the assistant's own tools; sending
                // one as an image_url is what the API rejects as an unsupported image.
                if (!Images.isInlineImage(attachment.mimeType, attachment.isWorkspaceFile)) continue
                if (attachment.id.isBlank() || result.containsKey(attachment.id)) continue
                payloadCache.get(attachment.id)?.let { cached ->
                    result[attachment.id] = cached
                    continue
                }
                val bytes = attachments.readBytes(attachment) ?: continue
                val dataUrl = Images.dataUrl(attachment.mimeType, bytes)
                payloadCache.put(attachment.id, dataUrl)
                result[attachment.id] = dataUrl
            }
        }
        return result
    }

    /**
     * Asks the model for a short name for [conversation]. Null when the request
     * fails or the answer is unusable, in which case the caller keeps the title it
     * already has — naming a chat is never worth an error message.
     */
    suspend fun suggestTitle(conversation: Conversation, settings: AppSettings): String? {
        val seed = Titles.seed(conversation.messages)
        if (seed.isBlank()) return null
        val body = Titles.requestBody(
            model = PromptBuilder.resolvedModel(conversation, settings),
            seed = seed,
        )
        return when (val result = client.complete(body, settings)) {
            is CompleteResult.Failure -> null
            is CompleteResult.Success -> Titles.sanitize(
                result.response.choices.firstOrNull()?.message?.content.orEmpty(),
            )
        }
    }

    /**
     * [restatement] is set only for the one turn a regenerate asks for: the prompt
     * the user has just chosen to apply, repeated after the history. An ordinary
     * send leaves it null, so a prompt edit cannot reach the model by itself.
     */
    suspend fun runTurn(
        conversation: Conversation,
        settings: AppSettings,
        memories: List<String>,
        restatement: String? = null,
        onProgress: (Message) -> Unit,
    ): Message {
        val messageId = newId()
        val started = now()
        val dataUrls = imagePayloads(conversation.messages)
        val toolSpecs = tools.availableTools(settings, conversation)
        val streaming = settings.streaming

        val body = PromptBuilder.buildRequestBody(
            conversation = conversation,
            settings = settings,
            memories = memories,
            dataUrls = dataUrls,
            tools = toolSpecs,
            stream = streaming,
            restatement = restatement,
        )

        if (!streaming) return runBlockingTurn(body, settings, messageId, started, onProgress)

        val accumulator = DeltaAccumulator()
        var transportFailure: String? = null
        client.stream(body, settings).collect { event ->
            when (event) {
                is StreamEvent.Chunk -> {
                    accumulator.apply(event.chunk)
                    onProgress(accumulator.snapshot(messageId, started))
                }
                is StreamEvent.Failed -> transportFailure = event.message
                StreamEvent.Completed -> Unit
            }
        }
        val message = accumulator.snapshot(messageId, started, transportFailure)
        onProgress(message)
        return message
    }

    private suspend fun runBlockingTurn(
        body: kotlinx.serialization.json.JsonObject,
        settings: AppSettings,
        messageId: String,
        started: Long,
        onProgress: (Message) -> Unit,
    ): Message {
        val message = when (val result = client.complete(body, settings)) {
            is CompleteResult.Failure -> Message(
                id = messageId,
                role = Role.Assistant,
                createdAt = started,
                error = result.message,
            )

            is CompleteResult.Success -> {
                val response = result.response
                val choice = response.choices.firstOrNull()
                Message(
                    id = messageId,
                    role = Role.Assistant,
                    content = choice?.message?.content.orEmpty(),
                    reasoning = choice?.message?.reasoningContent.orEmpty(),
                    toolCalls = choice?.message?.toolCalls.orEmpty()
                        .mapIndexedNotNull { index, call ->
                            val name = call.function?.name.orEmpty()
                            if (name.isBlank()) {
                                null
                            } else {
                                ToolCall(
                                    id = call.id?.takeIf { it.isNotBlank() } ?: "call_$index",
                                    name = name,
                                    arguments = call.function?.arguments.orEmpty().ifBlank { "{}" },
                                    createdAt = started,
                                )
                            }
                        },
                    model = response.model,
                    usage = response.usage?.toUsage(),
                    createdAt = started,
                    error = response.error?.message,
                )
            }
        }
        onProgress(message)
        return message
    }

    private companion object {
        const val PAYLOAD_CACHE_ENTRIES = 16
    }
}

/** Builds the immutable snapshot the UI and the store both consume. */
private fun DeltaAccumulator.snapshot(
    messageId: String,
    startedAt: Long,
    transportFailure: String? = null,
): Message = Message(
    id = messageId,
    role = Role.Assistant,
    content = content,
    reasoning = reasoning,
    createdAt = startedAt,
    toolCalls = toolCalls().map { it.copy(createdAt = startedAt) },
    model = model,
    usage = usage,
    error = transportFailure ?: error,
)
