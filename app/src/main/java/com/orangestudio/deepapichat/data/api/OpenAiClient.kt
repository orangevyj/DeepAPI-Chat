package com.orangestudio.deepapichat.data.api

import com.orangestudio.deepapichat.core.AppJson
import com.orangestudio.deepapichat.data.model.AppSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

sealed interface StreamEvent {
    data class Chunk(val chunk: ChatChunk) : StreamEvent
    data object Completed : StreamEvent
    data class Failed(val message: String, val httpCode: Int? = null) : StreamEvent
}

sealed interface CompleteResult {
    data class Success(val response: ChatResponse) : CompleteResult
    data class Failure(val message: String, val httpCode: Int? = null) : CompleteResult
}

/** Outcome of asking the endpoint which models it offers. */
sealed interface ModelsResult {
    data class Success(val ids: List<String>) : ModelsResult
    data class Failure(val message: String, val httpCode: Int? = null) : ModelsResult
}

/** Shared HTTP client. One instance, reused for every request. */
object HttpClientFactory {
    fun create(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // Applies per read, not to the whole call: an SSE connection that keeps
        // producing output stays alive indefinitely, while a silent one dies.
        .readTimeout(120, TimeUnit.SECONDS)
        // No overall cap; long generations are legitimate.
        .callTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}

/**
 * Transport for OpenAI-compatible chat completions.
 *
 * This class only moves bytes and parses JSON; all decisions about what to send
 * live in [com.orangestudio.deepapichat.domain.PromptBuilder] and all assembly of
 * a response lives in [DeltaAccumulator].
 */
class OpenAiClient(
    private val client: OkHttpClient = HttpClientFactory.create(),
    private val json: Json = AppJson.compact,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    fun stream(body: JsonObject, settings: AppSettings): Flow<StreamEvent> = callbackFlow {
        val request = buildRequest(body, settings, acceptStreaming = true)
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == DONE) {
                    trySendBlocking(StreamEvent.Completed)
                    return
                }
                val chunk = try {
                    json.decodeFromString(ChatChunk.serializer(), data)
                } catch (t: Throwable) {
                    trySendBlocking(StreamEvent.Failed("Malformed stream payload: ${t.message}"))
                    eventSource.cancel()
                    return
                }
                trySendBlocking(StreamEvent.Chunk(chunk))
            }

            override fun onClosed(eventSource: EventSource) {
                trySendBlocking(StreamEvent.Completed)
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val code = response?.code
                val body = try {
                    response?.body?.string()
                } catch (_: Throwable) {
                    null
                }
                trySendBlocking(StreamEvent.Failed(errorMessageFrom(body, t, code), code))
                close()
            }
        }
        val source = EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { source.cancel() }
    }

    /** Single-shot (non-streamed) completion. */
    suspend fun complete(body: JsonObject, settings: AppSettings): CompleteResult = withContext(io) {
        val request = buildRequest(body, settings, acceptStreaming = false)
        val call = client.newCall(request)
        val cancellation = kotlin.coroutines.coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                val raw = try {
                    response.body.string()
                } catch (t: Throwable) {
                    return@use CompleteResult.Failure("Could not read the response: ${t.message}", response.code)
                }
                if (!response.isSuccessful) {
                    return@use CompleteResult.Failure(errorMessageFrom(raw, null, response.code), response.code)
                }
                val parsed = runCatching {
                    json.decodeFromString(ChatResponse.serializer(), raw)
                }.getOrElse { t ->
                    return@use CompleteResult.Failure("Could not parse the response: ${t.message}")
                }
                parsed.error?.message?.let { message ->
                    return@use CompleteResult.Failure(message)
                }
                CompleteResult.Success(parsed)
            }
        } catch (t: Throwable) {
            CompleteResult.Failure(t.message ?: "The request failed.")
        } finally {
            cancellation?.dispose()
        }
    }

    /**
     * Asks the endpoint which models it offers, so the app can suggest the ones that
     * actually exist rather than a list baked in at build time.
     *
     * Failure is a returned value rather than an exception on purpose: a lookup is
     * only ever a convenience, and nothing about chatting should break because the
     * catalogue could not be read. The caller falls back to its built-in suggestions.
     */
    suspend fun listModels(baseUrl: String, apiKey: String): ModelsResult = withContext(io) {
        // Building the request is guarded as well as sending it: a base URL with a space
        // or no scheme makes Request.Builder.url throw, and the caller has no guard of
        // its own, so an exception here would crash the screen that asked instead of
        // coming back as a message.
        val request = runCatching {
            val builder = Request.Builder()
                .url(Endpoints.models(baseUrl))
                .get()
                .header("Content-Type", "application/json")
            if (apiKey.isNotBlank()) {
                builder.header("Authorization", "Bearer ${apiKey.trim()}")
            }
            builder.build()
        }.getOrElse { t ->
            return@withContext ModelsResult.Failure(t.message ?: "That base URL could not be used.")
        }
        val call = client.newCall(request)
        val cancellation = kotlin.coroutines.coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                val raw = try {
                    response.body.string()
                } catch (t: Throwable) {
                    return@use ModelsResult.Failure("Could not read the response: ${t.message}")
                }
                if (!response.isSuccessful) {
                    return@use ModelsResult.Failure(errorMessageFrom(raw, null, response.code), response.code)
                }
                val parsed = runCatching {
                    json.decodeFromString(WireModelsEnvelope.serializer(), raw)
                }.getOrElse { t ->
                    return@use ModelsResult.Failure("Could not parse the model list: ${t.message}")
                }
                val ids = parsed.data.map { it.id.trim() }.filter { it.isNotEmpty() }.distinct()
                if (ids.isEmpty()) {
                    ModelsResult.Failure("The endpoint listed no models.")
                } else {
                    ModelsResult.Success(ids)
                }
            }
        } catch (t: Throwable) {
            ModelsResult.Failure(t.message ?: "The request failed.")
        } finally {
            cancellation?.dispose()
        }
    }

    private fun buildRequest(body: JsonObject, settings: AppSettings, acceptStreaming: Boolean): Request {
        val builder = Request.Builder()
            .url(Endpoints.chatCompletions(settings.baseUrl))
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .header("Content-Type", "application/json")
        if (settings.apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer ${settings.apiKey.trim()}")
        }
        if (acceptStreaming) builder.header("Accept", "text/event-stream")
        return builder.build()
    }

    private companion object {
        const val DONE = "[DONE]"
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
