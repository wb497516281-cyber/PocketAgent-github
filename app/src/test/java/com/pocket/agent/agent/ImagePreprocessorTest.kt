package com.pocket.agent.agent

import com.pocket.agent.data.chat.ChatMessage
import com.pocket.agent.data.chat.ChatRole
import com.pocket.agent.data.chat.ImageAttachment
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preprocessor is the seam between a photo and a model that cannot see,
 * so both directions are pinned down: a vision model gets the attachments
 * untouched, and a text-only one gets OCR text glued into the prompt.
 */
class ImagePreprocessorTest {

    @Test
    fun `a vision capable model receives the attachments untouched`() = runTest {
        val result = prepare(text = "这是什么", images = 2, supportsVision = true)
        assertEquals(2, result.message.images.size)
        assertEquals("这是什么", result.message.content)
        assertNull(result.notice)
        assertFalse(result.blocked)
    }

    @Test
    fun `a message without images passes through without ocr`() = runTest {
        val result = prepare(text = "你好", images = 0, supportsVision = false)
        assertTrue(result.message.images.isEmpty())
        assertEquals("你好", result.message.content)
        assertNull(result.notice)
        assertFalse(result.blocked)
    }

    @Test
    fun `recognised text is merged into the prompt and the attachments dropped`() = runTest {
        val result = prepare(text = "这是什么", images = 2, supportsVision = false)
        assertEquals(
            "这是什么\n\n[图片OCR结果：第一段文字]\n[图片OCR结果：第二段文字]",
            result.message.content,
        )
        assertTrue(result.message.images.isEmpty())
        assertEquals("当前模型不支持视觉，已自动提取图片文字发送。", result.notice)
        assertFalse(result.blocked)
    }

    @Test
    fun `an empty prompt leaves only the ocr blocks`() = runTest {
        val result = prepare(text = "   ", images = 1, supportsVision = false)
        assertEquals("[图片OCR结果：第一段文字]", result.message.content)
    }

    @Test
    fun `an image with no readable text blocks the turn`() = runTest {
        val preprocessor = ImagePreprocessor(FakeExtractor { "" })
        val result = preprocessor.prepare(message("这里没有字", 1), supportsVision = false)
        assertTrue(result.blocked)
        assertNull(result.notice)
        // The message is handed back untouched so the caller can resend it
        // after the user switches to a vision model.
        assertEquals(1, result.message.images.size)
    }

    @Test
    fun `an ocr failure is tolerated instead of crashing the turn`() = runTest {
        val preprocessor = ImagePreprocessor(FakeExtractor { throw IllegalStateException("模型加载失败") })
        val result = preprocessor.prepare(message("看图", 1), supportsVision = false)
        assertTrue(result.blocked)
    }

    @Test
    fun `one readable image is enough even when another carries nothing`() = runTest {
        // payload1 carries text, payload2 does not; the blank one is dropped.
        val preprocessor = ImagePreprocessor(
            FakeExtractor { base64 -> if (base64 == "payload1") "第一段文字" else "" },
        )
        val result = preprocessor.prepare(message("两张图", 2), supportsVision = false)
        assertEquals("两张图\n\n[图片OCR结果：第一段文字]", result.message.content)
        assertEquals(0, result.message.images.size)
        assertFalse(result.blocked)
    }

    private fun message(text: String, images: Int) = ChatMessage(
        role = ChatRole.USER,
        content = text,
        images = (1..images).map { index ->
            ImageAttachment(
                uri = "content://picks/$index",
                base64 = "payload$index",
                mimeType = "image/jpeg",
            )
        },
    )

    private suspend fun prepare(text: String, images: Int, supportsVision: Boolean): PreprocessResult {
        val preprocessor = ImagePreprocessor(
            FakeExtractor { base64 ->
                when (base64) {
                    "payload1" -> "第一段文字"
                    "payload2" -> "第二段文字"
                    else -> ""
                }
            },
        )
        return preprocessor.prepare(message(text, images), supportsVision)
    }

    private class FakeExtractor(private val answer: (String) -> String) : ImageTextExtractor {
        override suspend fun extract(image: ImageAttachment): String = answer(image.base64)
    }
}
