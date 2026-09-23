package com.pocket.agent.agent

import android.content.Context
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.pocket.agent.data.chat.ImageAttachment
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Pulls text out of an attached image, locally and offline. */
interface ImageTextExtractor {
    suspend fun extract(image: ImageAttachment): String
}

/**
 * On-device OCR backed by Google ML Kit's Chinese model.
 *
 * The recognizer is loaded lazily by ML Kit (it rides on Play Services), so the
 * first call may download the model; after that recognition is fully offline.
 * A single [TextRecognizer] is reused because building one is expensive.
 */
class MlKitTextExtractor(context: Context) : ImageTextExtractor {

    @Suppress("UNUSED_PARAMETER")
    private val appContext = context.applicationContext

    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    override suspend fun extract(image: ImageAttachment): String {
        val bytes = Base64.getDecoder().decode(image.base64)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return ""
        val input = InputImage.fromBitmap(bitmap, 0)
        return try {
            suspendCancellableCoroutine { continuation ->
                recognizer.process(input)
                    .addOnSuccessListener { text -> continuation.resume(text.text) }
                    .addOnFailureListener { error -> continuation.resumeWithException(error) }
            }
        } finally {
            bitmap.recycle()
        }
    }
}
