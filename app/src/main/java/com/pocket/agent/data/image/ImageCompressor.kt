package com.pocket.agent.data.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.pocket.agent.data.chat.ImageAttachment
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.math.max

/** Long-edge ceiling. A phone photo is far larger than the API needs. */
const val MAX_IMAGE_LONG_EDGE = 2048

/** Compressed payload ceiling. Roughly 1 MB keeps the base64 body manageable. */
const val MAX_IMAGE_BYTES = 1 * 1024 * 1024

private const val START_QUALITY = 90
private const val MIN_QUALITY = 20
private const val QUALITY_STEP = 10

/**
 * Shrinks a picked photo down to a base64 payload small enough to attach.
 *
 * Decoding is sampled with [computeInSampleSize] so a 12 MP camera image never
 * becomes a full-size bitmap in memory, and JPEG quality is stepped down until
 * the encoded bytes fit under [MAX_IMAGE_BYTES]. Everything happens locally; no
 * third party ever sees the image. Returns null when the uri cannot be decoded
 * or will not fit the budget.
 */
class ImageCompressor(private val resolver: ContentResolver) {

    fun compress(uri: Uri): ImageAttachment? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "Could not read image bounds for $uri")
            return null
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(width, height)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: return null

        return try {
            val bytes = encodeWithinBudget(bitmap) ?: return null
            ImageAttachment(
                uri = uri.toString(),
                base64 = Base64.getEncoder().encodeToString(bytes),
                mimeType = "image/jpeg",
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun encodeWithinBudget(bitmap: Bitmap): ByteArray? {
        val stream = ByteArrayOutputStream()
        var quality = START_QUALITY
        while (true) {
            stream.reset()
            val compressed = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            if (!compressed) return null
            if (stream.size() <= MAX_IMAGE_BYTES || quality <= MIN_QUALITY) break
            quality -= QUALITY_STEP
        }
        return if (stream.size() <= MAX_IMAGE_BYTES) stream.toByteArray() else null
    }

    private companion object {
        const val TAG = "ImageCompressor"
    }
}

/**
 * The power-of-two sample size that decodes an image under [maxLongEdge].
 *
 * Kept as a pure function so the arithmetic is unit tested on the JVM without
 * touching an Android bitmap.
 */
internal fun computeInSampleSize(
    width: Int,
    height: Int,
    maxLongEdge: Int = MAX_IMAGE_LONG_EDGE,
): Int {
    if (width <= 0 || height <= 0 || maxLongEdge <= 0) return 1
    val longEdge = max(width, height)
    var sample = 1
    while (longEdge / sample > maxLongEdge) sample *= 2
    return sample
}
