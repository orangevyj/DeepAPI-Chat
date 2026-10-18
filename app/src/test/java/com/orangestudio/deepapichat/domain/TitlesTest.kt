package com.orangestudio.deepapichat.domain

import com.orangestudio.deepapichat.data.model.Defaults
import com.orangestudio.deepapichat.data.model.Message
import com.orangestudio.deepapichat.data.model.Role
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitlesTest {

    private fun message(role: Role, text: String) = Message(id = "m-$text", role = role, content = text)

    @Test
    fun sanitizeUnwrapsWhatModelsActuallyReturn() {
        assertEquals("DeepSeek streaming", Titles.sanitize("\"DeepSeek streaming\""))
        assertEquals("DeepSeek streaming", Titles.sanitize("Title: DeepSeek streaming"))
        assertEquals("DeepSeek streaming", Titles.sanitize("**DeepSeek streaming**."))
        assertEquals("DeepSeek streaming", Titles.sanitize("  DeepSeek   streaming\nand more prose"))
    }

    @Test
    fun sanitizeRejectsRefusalsAndRambles() {
        assertNull(Titles.sanitize(""))
        assertNull(Titles.sanitize("   "))
        assertNull(Titles.sanitize("\n\n"))
        assertNull(Titles.sanitize("I am sorry, but I cannot name this conversation for you."))
    }

    @Test
    fun sanitizeCapsTheLength() {
        val long = "a".repeat(80)
        assertEquals(Titles.MAX_LENGTH, Titles.sanitize(long)?.length)
    }

    @Test
    fun seedKeepsOnlyTheRecentSpokenTurns() {
        val messages = buildList {
            add(message(Role.System, "ignore me"))
            repeat(10) { index -> add(message(Role.User, "question $index")) }
            add(Message(id = "blank", role = Role.Assistant, content = "   "))
        }
        val seed = Titles.seed(messages, maxTurns = 6)
        assertFalse(seed.contains("ignore me"))
        assertFalse(seed.contains("question 3"))
        assertTrue(seed.contains("User: question 9"))
        assertTrue(seed.startsWith("User: question 4"))
    }

    @Test
    fun requestAsksForATitleAndNothingElse() {
        val body = Titles.requestBody("deepseek-chat", "User: hi")
        assertEquals("deepseek-chat", body["model"]!!.jsonPrimitive.content)
        assertEquals(24, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertFalse(body.containsKey("tools"))
        assertEquals(2, body["messages"]!!.jsonArray.size)
    }

    @Test
    fun reasoningModelsAreNotUsedForNaming() {
        assertEquals("deepseek-chat", Titles.titleModel("deepseek-reasoner"))
        assertEquals("deepseek-chat", Titles.titleModel(""))
        assertEquals("llama-3", Titles.titleModel("llama-3"))
        assertEquals(Defaults.MODEL, Titles.titleModel("some-reasoner-model"))
    }

    @Test
    fun theNamingPromptForbidsChatter() {
        val body = Titles.requestBody("deepseek-chat", "User: hi")
        val instructions = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(instructions.contains("title only"))
    }
}
