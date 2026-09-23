package com.pocket.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {

    @Test
    fun `reads a data payload with and without the space`() {
        assertEquals("{\"a\":1}", SseParser.payload("data: {\"a\":1}"))
        assertEquals("{\"a\":1}", SseParser.payload("data:{\"a\":1}"))
    }

    @Test
    fun `tolerates a trailing carriage return`() {
        assertEquals("{\"a\":1}", SseParser.payload("data: {\"a\":1}\r"))
    }

    @Test
    fun `recognises the done sentinel`() {
        assertEquals(SseParser.DONE, SseParser.payload("data: [DONE]"))
    }

    @Test
    fun `ignores blank lines and comments`() {
        assertTrue(SseParser.isIgnorable(""))
        assertTrue(SseParser.isIgnorable("   "))
        assertTrue(SseParser.isIgnorable(": keep-alive"))
        assertFalse(SseParser.isIgnorable("data: {}"))
    }

    @Test
    fun `returns null for other sse fields`() {
        assertNull(SseParser.payload("event: message"))
        assertNull(SseParser.payload("id: 42"))
        assertNull(SseParser.payload(""))
    }
}
