package com.orangestudio.deepapichat.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdsTest {

    @Test
    fun idsAreUniqueAndFilesystemSafe() {
        val ids = List(200) { newId() }
        assertEquals(200, ids.toSet().size)
        assertTrue(ids.all { Regex("[A-Za-z0-9-]{36}").matches(it) })
        assertNotEquals(newId(), newId())
    }

    @Test
    fun titleCollapsesWhitespace() {
        assertEquals("hello world", titleFrom("  hello \n\n world  "))
    }

    @Test
    fun blankTextFallsBackToPlaceholder() {
        assertEquals("New chat", titleFrom("   "))
    }

    @Test
    fun longTextIsTruncatedWithAnEllipsis() {
        val title = titleFrom("a".repeat(200))
        assertTrue(title.length <= 49)
        assertTrue(title.endsWith("…"))
        assertEquals("a".repeat(48), title.dropLast(1))
    }
}
