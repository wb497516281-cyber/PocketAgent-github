package com.pocket.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vision flag decides whether an image goes down the wire or through
 * local OCR, so the guess itself is pinned down here: a false positive only
 * costs a downgrade, but a false negative sends base64 to a model that will
 * reject the request.
 */
class VisionSupportTest {

    @Test
    fun `models that read images are recognised`() {
        listOf(
            "gpt-4o",
            "gpt-4o-2024-11-20",
            "gpt-4o-mini-audio-preview",
            "gpt-4.1",
            "gpt-4.1-mini",
            "gpt-5",
            "o3-mini",
            "claude-3-5-sonnet-20240620",
            "claude-3-opus-20240229",
            "gemini-1.5-pro",
            "gemini-2.0-flash",
            "qwen-vl-max",
            "qwen-omni-turbo",
        ).forEach { id ->
            assertTrue("expected $id to be treated as vision capable", guessVisionSupport(id))
        }
    }

    @Test
    fun `text-only models are recognised`() {
        listOf(
            "deepseek-chat",
            "deepseek-reasoner",
            "gpt-3.5-turbo",
            "claude-2.1",
            "claude-instant-1.2",
            "qwen-max",
            "qwen-plus",
            "glm-4-plus",
            "moonshot-v1-8k",
            "gpt-4-turbo",
        ).forEach { id ->
            assertFalse("expected $id to be treated as text only", guessVisionSupport(id))
        }
    }

    @Test
    fun `blank ids are never vision capable`() {
        assertFalse(guessVisionSupport(""))
        assertFalse(guessVisionSupport("   "))
    }

    @Test
    fun `the guess ignores case and surrounding whitespace`() {
        assertTrue(guessVisionSupport("  GPT-4O  "))
        assertTrue(guessVisionSupport("Claude-3-HaKuAI"))
        assertFalse(guessVisionSupport(" DeepSeek-Chat "))
    }

    /**
     * The catalogue filter drops `vision`-named entries before the guess ever
     * sees them, so the two functions must stay consistent: whatever survives
     * filtering is a chat model whose vision flag can still be guessed.
     */
    @Test
    fun `guesses line up with the filtered chat catalogue`() {
        val catalogue = listOf(
            "gpt-4o",
            "gpt-4o-vision-preview",
            "claude-3-5-sonnet-20240620",
            "deepseek-chat",
            "text-embedding-3-large",
            "qwen-vl-max",
            "whisper-1",
        )
        val chatModels = filterChatModelIds(catalogue)
        assertEquals(
            listOf("gpt-4o", "claude-3-5-sonnet-20240620", "deepseek-chat", "qwen-vl-max"),
            chatModels,
        )
        assertEquals(
            listOf(true, true, false, true),
            chatModels.map { guessVisionSupport(it) },
        )
    }
}
