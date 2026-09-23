package com.pocket.agent.agent.tools

import com.pocket.agent.data.chat.ImageAttachment
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** How many pictures one slide is asked to carry at most. */
internal const val MAX_IMAGES_PER_SLIDE = 3

/** Ceiling on a single picture; a phone download stays far below it. */
internal const val MAX_IMAGE_BYTES = 4 * 1024 * 1024

private const val FETCH_TIMEOUT_SECONDS = 20L
private const val ATTACHMENT_PREFIX = "attachment:"

/** A picture the deck could not use, reported so the model hears about it. */
internal data class SkippedImage(val slideNumber: Int, val source: String, val reason: String)

/** Every picture in a deck, resolved to bytes, plus what had to be dropped. */
internal class SlidePictures(
    val bySlide: Map<Int, List<ResolvedImage>>,
    val skipped: List<SkippedImage>,
)

/** One picture that will not become bytes; the message reaches the model. */
internal class SlideImageException(message: String) : Exception(message)

/** The result of taking one source: the picture, or why there is none. */
private class Attempt(val image: ResolvedImage? = null, val error: String? = null)

/**
 * Turns the pictures a deck asks for into bytes.
 *
 * A picture is either an http address the model found on the web, or a photo
 * the user attached to this conversation; both end up as the same resolved
 * image so the writer never learns the difference. Anything that will not
 * become a usable image - a non-http scheme, a 404, an unsupported container,
 * a file past the size cap - lands in [SlidePictures.skipped] rather than
 * being thrown: the deck still gets written, and the model is told exactly
 * which picture was left out and why.
 */
internal class SlideImageResolver(private val client: OkHttpClient) {

    suspend fun resolve(
        deck: SlideDeckSpec,
        attachments: List<ImageAttachment>,
    ): SlidePictures = withContext(Dispatchers.IO) {
        val cache = mutableMapOf<String, Attempt>()
        val bySlide = mutableMapOf<Int, List<ResolvedImage>>()
        val skipped = mutableListOf<SkippedImage>()

        deck.slides.forEachIndexed { index, spec ->
            val number = index + 1
            val kept = mutableListOf<ResolvedImage>()
            spec.images.forEach { image ->
                val source = sourceOf(image)
                if (source == null) {
                    skipped += SkippedImage(
                        slideNumber = number,
                        source = "未命名图片",
                        reason = "既没有给出 url，也没有给出 attachment 序号",
                    )
                    return@forEach
                }
                val attempt = cache.getOrPut(source) { attemptOf(source, attachments) }
                if (attempt.image == null) {
                    skipped += SkippedImage(number, source, attempt.error ?: "无法取用")
                } else {
                    // The caption is per slide, so it travels with the use.
                    kept += attempt.image.copy(caption = image.caption.trim())
                }
            }
            bySlide[index] = kept
        }
        SlidePictures(bySlide, skipped)
    }

    /** The label a picture is cached and reported under. */
    private fun sourceOf(image: SlideImage): String? = when {
        image.attachment > 0 -> "$ATTACHMENT_PREFIX${image.attachment}"
        image.url.isNotBlank() -> image.url.trim()
        else -> null
    }

    private fun attemptOf(source: String, attachments: List<ImageAttachment>): Attempt =
        runCatching { resolveOne(source, attachments) }.fold(
            onSuccess = { Attempt(image = it) },
            onFailure = { error ->
                Attempt(error = error.message ?: "取用失败")
            },
        )

    private fun resolveOne(source: String, attachments: List<ImageAttachment>): ResolvedImage =
        if (source.startsWith(ATTACHMENT_PREFIX)) {
            resolveAttachment(source, attachments)
        } else {
            toResolved(source, download(source))
        }

    private fun download(url: String): ByteArray {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw SlideImageException("图片地址只支持 http/https，收到「$url」")
        }
        val request = Request.Builder().url(url).header("Accept", "image/*").build()
        return client.newCall(request)
            .apply { timeout().timeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            .execute()
            .use { response ->
                if (!response.isSuccessful) {
                    throw SlideImageException("下载失败，服务器返回 ${response.code}")
                }
                val body = response.body ?: throw SlideImageException("下载失败，响应为空")
                // Pull one byte past the cap, so an oversized body is refused
                // without ever being buffered whole.
                val source = body.source()
                source.request(MAX_IMAGE_BYTES + 1L)
                if (source.buffer.size > MAX_IMAGE_BYTES) {
                    throw SlideImageException(
                        "图片超过 ${MAX_IMAGE_BYTES / 1024 / 1024} MB 上限",
                    )
                }
                source.buffer.readByteArray()
            }
    }

    private fun resolveAttachment(
        source: String,
        attachments: List<ImageAttachment>,
    ): ResolvedImage {
        val number = source.removePrefix(ATTACHMENT_PREFIX).toIntOrNull()
            ?: throw SlideImageException("attachment 序号「$source」不是一个数字")
        if (number < 1 || number > attachments.size) {
            throw SlideImageException(
                "本次对话只上传了 ${attachments.size} 张图片，取不到第 $number 张",
            )
        }
        val attachment = attachments[number - 1]
        val bytes = try {
            android.util.Base64.decode(attachment.base64, android.util.Base64.DEFAULT)
        } catch (error: IllegalArgumentException) {
            throw SlideImageException("第 $number 张上传图片解码失败")
        }
        return toResolved(source, bytes)
    }

    private fun toResolved(source: String, bytes: ByteArray): ResolvedImage {
        if (bytes.isEmpty()) throw SlideImageException("图片内容为空")
        if (bytes.size > MAX_IMAGE_BYTES) {
            throw SlideImageException(
                "图片 ${bytes.size / 1024} KB 超过 ${MAX_IMAGE_BYTES / 1024 / 1024} MB 上限",
            )
        }
        val format = ImageHeader.formatOf(bytes)
            ?: throw SlideImageException("不是受支持的图片，仅接受 PNG/JPEG/GIF/BMP/WebP/TIFF")
        val size = ImageHeader.sizeOf(bytes)
            ?: throw SlideImageException("读不到图片尺寸，文件可能已损坏")
        return ResolvedImage(
            source = source,
            caption = "",
            format = format,
            bytes = bytes,
            width = size.first,
            height = size.second,
        )
    }
}
