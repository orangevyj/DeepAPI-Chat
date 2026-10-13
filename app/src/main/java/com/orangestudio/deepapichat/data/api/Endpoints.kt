package com.orangestudio.deepapichat.data.api

import com.orangestudio.deepapichat.data.model.Defaults

/**
 * Turns the user-entered base URL into a usable endpoint.
 *
 * The base URL is treated the same way the official OpenAI SDK treats `base_url`:
 * the path `/chat/completions` is appended to it. So both of these are correct:
 *   `https://api.deepseek.com`   -> `https://api.deepseek.com/chat/completions`
 *   `https://api.openai.com/v1`  -> `https://api.openai.com/v1/chat/completions`
 *
 * A `/v1` is never added automatically, because DeepSeek's own documentation uses
 * a bare hostname and adding a segment would break it.
 */
object Endpoints {

    private const val CHAT_PATH = "/chat/completions"
    private const val MODELS_PATH = "/models"

    fun chatCompletions(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        val base = trimmed.ifBlank { Defaults.BASE_URL }
        return if (base.endsWith(CHAT_PATH)) base else base + CHAT_PATH
    }

    /**
     * The model catalogue, which every OpenAI-compatible server exposes at `/models`.
     *
     * [chatCompletions] tolerates a base URL that already carries the full path, so
     * this strips it too: otherwise a base of `https://host/chat/completions` would
     * ask for `https://host/chat/completions/models`.
     */
    fun models(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
            .removeSuffix(CHAT_PATH)
            .trimEnd('/')
            .ifBlank { Defaults.BASE_URL }
        return if (base.endsWith(MODELS_PATH)) base else base + MODELS_PATH
    }
}
