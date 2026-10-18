package com.orangestudio.deepapichat.data.api

import com.orangestudio.deepapichat.core.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `/models` lookup is only ever a convenience and must never be able to break
 * chatting, so both halves are pinned here: the URL it asks for, and what it accepts
 * in reply.
 */
class ModelsEndpointTest {

    @Test
    fun theCatalogueSitsBesideTheChatEndpoint() {
        assertEquals("https://api.deepseek.com/models", Endpoints.models("https://api.deepseek.com"))
        assertEquals("https://api.deepseek.com/models", Endpoints.models("https://api.deepseek.com/"))
        assertEquals("https://api.openai.com/v1/models", Endpoints.models("https://api.openai.com/v1"))
        assertEquals("https://api.deepseek.com/models", Endpoints.models(""))
        assertEquals(
            "https://api.deepseek.com/models",
            Endpoints.models("  https://api.deepseek.com  "),
        )
    }

    @Test
    fun aBaseUrlThatAlreadyCarriesAPathIsNotStacked() {
        // chatCompletions deliberately tolerates a base URL carrying the full path, so
        // this must too, or the base it accepts would be the base this rejects.
        assertEquals(
            "https://api.deepseek.com/models",
            Endpoints.models("https://api.deepseek.com/chat/completions"),
        )
        assertEquals(
            "https://api.deepseek.com/models",
            Endpoints.models("https://api.deepseek.com/models"),
        )
    }

    @Test
    fun aRealCatalogueBodyParsesAndItsExtraFieldsAreIgnored() {
        // Trimmed from a real response: everything past `id` is provider metadata the
        // app has no use for, and must not stop the parse from succeeding.
        val body = """
            {"object":"list","data":[
              {"id":"deepseek-chat","object":"model","created":1700000000,"owned_by":"deepseek"},
              {"id":"deepseek-reasoner","object":"model","created":1700000000,"owned_by":"deepseek"}
            ]}
        """.trimIndent()

        val parsed = AppJson.compact.decodeFromString(WireModelsEnvelope.serializer(), body)

        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), parsed.data.map { it.id })
    }

    @Test
    fun aBodyWithNothingUsableYieldsNoModelsRatherThanThrowing() {
        val empty = AppJson.compact.decodeFromString(
            WireModelsEnvelope.serializer(),
            """{"object":"list","data":[]}""",
        )
        assertTrue(empty.data.isEmpty())

        // A server that answers with a bare object is "no models", not an exception.
        val odd = AppJson.compact.decodeFromString(WireModelsEnvelope.serializer(), "{}")
        assertTrue(odd.data.isEmpty())
    }
}
