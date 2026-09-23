package com.pocket.agent.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelFilterTest {

    private val catalogue = listOf("gpt-4o", "GPT-4o-mini", "deepseek-chat", "qwen-max")

    @Test
    fun `blank query keeps the whole catalogue`() {
        assertEquals(catalogue, filterModelMatches(catalogue, ""))
        assertEquals(catalogue, filterModelMatches(catalogue, "   "))
    }

    @Test
    fun `matches substrings regardless of case`() {
        assertEquals(listOf("gpt-4o", "GPT-4o-mini"), filterModelMatches(catalogue, "GPT-4o"))
        assertEquals(listOf("deepseek-chat"), filterModelMatches(catalogue, "deep"))
    }

    @Test
    fun `unknown query matches nothing`() {
        assertEquals(emptyList<String>(), filterModelMatches(catalogue, "claude"))
    }
}
