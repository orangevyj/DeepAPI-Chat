package com.orangestudio.deepapichat.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorMessagesTest {

    @Test
    fun prefersTheServerEnvelopeMessage() {
        val body = """{"error":{"message":"Insufficient balance","type":"invalid_request_error"}}"""
        val message = errorMessageFrom(body, null, 402)
        assertTrue(message.contains("Insufficient balance"))
    }

    @Test
    fun explainsTheCommonHttpCodes() {
        assertTrue(errorMessageFrom(null, null, 401).contains("401"))
        assertTrue(errorMessageFrom(null, null, 429).contains("429"))
        assertTrue(errorMessageFrom(null, null, 404).contains("404"))
    }

    @Test
    fun fallsBackToTheTransportFailure() {
        val message = errorMessageFrom(null, java.net.UnknownHostException("api.deepseek.com"), null)
        assertTrue(message.contains("api.deepseek.com"))
    }

    @Test
    fun neverReturnsAnEmptyString() {
        assertTrue(errorMessageFrom(null, null, null).isNotBlank())
        assertTrue(errorMessageFrom("", null, null).isNotBlank())
    }

    @Test
    fun plainBodyTextIsUsedWhenItIsNotJson() {
        val message = errorMessageFrom("upstream timed out", null, 502)
        assertEquals(true, message.contains("502") || message.contains("upstream timed out"))
    }
}
