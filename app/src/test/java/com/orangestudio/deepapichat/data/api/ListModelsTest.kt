package com.orangestudio.deepapichat.data.api

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The safety property of the catalogue lookup: it reports failure, it does not throw.
 *
 * The Settings screen asks for this list without a guard of its own, so an exception
 * here would not show up as an error message - it would take the screen down.
 */
class ListModelsTest {

    @Test
    fun aMalformedBaseUrlIsReportedNotThrown() = runBlocking {
        // A space makes the URL unparseable, which throws from Request.Builder.url
        // rather than from the request itself.
        val result = OpenAiClient().listModels("http://not a url", "test-key")

        assertTrue("expected a failure, got $result", result is ModelsResult.Failure)
    }

    @Test
    fun anUnreachableEndpointIsReportedNotThrown() = runBlocking {
        // Loopback port 1: refused at once, so this needs no network and cannot flake.
        val result = OpenAiClient().listModels("http://127.0.0.1:1", "test-key")

        assertTrue("expected a failure, got $result", result is ModelsResult.Failure)
    }
}
