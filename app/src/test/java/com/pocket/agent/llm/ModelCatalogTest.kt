package com.pocket.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelCatalogTest {

    @Test
    fun `drops embedding vision audio and other non-text models`() {
        val ids = listOf(
            "gpt-4o",
            "text-embedding-3-large",
            "dall-e-3",
            "whisper-1",
            "gpt-4o-vision-preview",
            "tts-1-hd",
            "omni-moderation-latest",
            "gpt-4o-2024-11-20",
            "  ",
        )
        assertEquals(
            listOf("gpt-4o", "gpt-4o-2024-11-20"),
            filterChatModelIds(ids),
        )
    }

    @Test
    fun `parses the standard data array`() {
        val body = """{"object":"list","data":[{"id":"deepseek-chat"},{"id":"deepseek-reasoner"}]}"""
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), parseModelIds(body))
    }

    @Test
    fun `parses bare arrays string entries and model keys`() {
        assertEquals(listOf("a", "b"), parseModelIds("""["a","b"]"""))
        assertEquals(listOf("a"), parseModelIds("""{"models":["a"]}"""))
        assertEquals(listOf("m1"), parseModelIds("""{"data":[{"name":"m1"}]}"""))
    }

    @Test
    fun `unparsable body yields an empty list`() {
        assertEquals(emptyList<String>(), parseModelIds("<html>404</html>"))
        assertEquals(emptyList<String>(), parseModelIds(""))
    }
}
