package com.orangestudio.deepapichat.data.api

import com.orangestudio.deepapichat.core.AppJson
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feeds the accumulator the exact JSON frames DeepSeek emits, including a tool
 * call split across two chunks the way the real API splits them.
 */
class DeltaAccumulatorTest {

    private val json: Json = AppJson.compact

    private fun chunk(raw: String): ChatChunk = json.decodeFromString(ChatChunk.serializer(), raw)

    @Test
    fun accumulatesTextReasoningModelAndUsage() {
        val accumulator = DeltaAccumulator()
        accumulator.apply(chunk("""{"model":"deepseek-reasoner","choices":[{"index":0,"delta":{"reasoning_content":"Let me think"},"finish_reason":null}]}"""))
        accumulator.apply(chunk("""{"choices":[{"index":0,"delta":{"content":"Hel"},"finish_reason":null}]}"""))
        accumulator.apply(chunk("""{"choices":[{"index":0,"delta":{"content":"lo"},"finish_reason":"stop"}]}"""))
        accumulator.apply(chunk("""{"choices":[],"usage":{"prompt_tokens":11,"completion_tokens":7,"total_tokens":18}}"""))

        assertEquals("Hello", accumulator.content)
        assertEquals("Let me think", accumulator.reasoning)
        assertEquals("deepseek-reasoner", accumulator.model)
        assertEquals("stop", accumulator.finishReason)
        assertEquals(18, accumulator.usage?.total)
        assertTrue(accumulator.hasText())
        assertTrue(accumulator.toolCalls().isEmpty())
        assertNull(accumulator.error)
    }

    @Test
    fun joinsToolCallArgumentsSplitAcrossChunks() {
        val accumulator = DeltaAccumulator()
        accumulator.apply(
            chunk(
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"save_memory","arguments":"{\"content\":\"like"}}]},"finish_reason":null}]}""",
            ),
        )
        accumulator.apply(
            chunk(
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"s tea\"}"}}]},"finish_reason":"tool_calls"}]}""",
            ),
        )

        val calls = accumulator.toolCalls()
        assertEquals(1, calls.size)
        assertEquals("call_1", calls.single().id)
        assertEquals("save_memory", calls.single().name)
        assertEquals("""{"content":"likes tea"}""", calls.single().arguments)
        assertEquals("tool_calls", accumulator.finishReason)
    }

    @Test
    fun toolCallsWithoutAnIdGetAStableFallback() {
        val accumulator = DeltaAccumulator()
        accumulator.apply(
            chunk(
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":2,"function":{"name":"save_memory","arguments":"{}"}}]},"finish_reason":null}]}""",
            ),
        )
        assertEquals("call_2", accumulator.toolCalls().single().id)
    }

    @Test
    fun errorEnvelopeIsCaptured() {
        val accumulator = DeltaAccumulator()
        accumulator.apply(chunk("""{"error":{"message":"Invalid API key"}}"""))
        assertTrue(accumulator.error?.contains("Invalid API key") == true)
    }
}
