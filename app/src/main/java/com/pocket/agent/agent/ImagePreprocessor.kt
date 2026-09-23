package com.pocket.agent.agent

import com.pocket.agent.data.chat.ChatMessage

/** Outcome of running the image preprocessor over one user turn. */
data class PreprocessResult(
    val message: ChatMessage,
    val notice: String? = null,
    val blocked: Boolean = false,
)

/**
 * Bridges image input and models that cannot see.
 *
 * A vision-capable model receives the attachments untouched. A text-only model
 * gets each image OCR'd first: the recognised text is appended to the prompt
 * and the attachments are dropped, so the model still has the content. When an
 * image carries no readable text and the model cannot see, the result is
 * [PreprocessResult.blocked] and the caller stops the turn, asking the user to
 * switch to a vision model.
 */
class ImagePreprocessor(
    private val extractor: ImageTextExtractor,
) {

    suspend fun prepare(message: ChatMessage, supportsVision: Boolean): PreprocessResult {
        if (message.images.isEmpty() || supportsVision) {
            return PreprocessResult(message)
        }
        val recognised = message.images
            .map { image -> runCatching { extractor.extract(image) }.getOrElse { "" }.trim() }
            .filter { it.isNotEmpty() }

        if (recognised.isEmpty()) {
            return PreprocessResult(message, blocked = true)
        }
        val ocrBlock = recognised.joinToString("\n") { "$OCR_PREFIX$it]" }
        val merged = listOf(message.content.trim(), ocrBlock)
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
        return PreprocessResult(
            message = message.copy(content = merged, images = emptyList()),
            notice = OCR_DOWNGRADE_NOTICE,
        )
    }

    private companion object {
        const val OCR_PREFIX = "[图片OCR结果："
        const val OCR_DOWNGRADE_NOTICE = "当前模型不支持视觉，已自动提取图片文字发送。"
    }
}
