package com.pocket.agent.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Asking an endpoint which models it exposes. */
sealed interface ModelFetchResult {

    /** Chat model ids, in the order the endpoint reported them. */
    data class Success(val models: List<String>) : ModelFetchResult

    /**
     * The endpoint answers, but has no `/models` catalogue at all - some
     * gateways return 404 for it. The caller keeps a manual model field.
     */
    data object Unsupported : ModelFetchResult

    /** Transport or auth problem; [message] is safe to show to the user. */
    data class Failure(val message: String) : ModelFetchResult
}

/**
 * Trims a raw catalogue down to the models worth chatting with.
 *
 * Provider catalogues are full of `embedding`, `vision`, `audio`, `dall-e` and
 * `whisper` entries that `/chat/completions` cannot use, and they crowd out the
 * models the user actually wants, so they are dropped here.
 */
internal fun filterChatModelIds(ids: List<String>): List<String> =
    ids.map { it.trim() }
        .filter { it.isNotEmpty() && !it.isNonTextModel() }
        .distinct()

private fun String.isNonTextModel(): Boolean {
    val lowered = lowercase()
    return NON_TEXT_MARKERS.any { marker -> lowered.contains(marker) }
}

/**
 * Guesses whether a chat model accepts image input.
 *
 * Model ids carry the tell in their name: OpenAI's `4o` line, Anthropic's
 * `claude-3` and up, Qwen's `vl` variants and Google's `gemini` models all
 * read images. The guess only pre-fills the settings checkbox - the user can
 * always correct it, because vendors rename models faster than any list.
 */
internal fun guessVisionSupport(modelId: String): Boolean {
    val lowered = modelId.trim().lowercase()
    if (lowered.isEmpty()) return false
    return VISION_MARKERS.any { marker -> lowered.contains(marker) }
}

private val VISION_MARKERS = listOf(
    "vision",
    "4o",
    "claude-3",
    "vl",
    "gemini",
    "omni",
    "gpt-4.1",
    "gpt-5",
    "o3",
    "o4",
)

private val NON_TEXT_MARKERS = listOf(
    "embedding",
    "embed",
    "moderation",
    "rerank",
    "whisper",
    "transcribe",
    "dall-e",
    "dalle",
    "diffusion",
    "vision",
    "image",
    "ocr",
    "audio",
    "speech",
    "tts",
    "asr",
    "realtime",
)

/**
 * Reads model ids out of whatever shape an OpenAI-compatible `/models` returns.
 *
 * The spec promises `{"data": [{"id": ...}]}`, but gateways in the wild also
 * answer with a bare array, a `models` array, or arrays of plain strings, so
 * all of them are read. A body that is not JSON yields an empty list instead of
 * throwing, because the caller turns that into readable text.
 */
internal fun parseModelIds(body: String): List<String> {
    if (body.isBlank()) return emptyList()
    val root = runCatching { MODELS_JSON.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
    val entries: JsonArray? = when (root) {
        is JsonArray -> root
        is JsonObject -> (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray)
        else -> null
    }
    return entries?.mapNotNull { element -> element.modelId() }.orEmpty()
}

private fun JsonElement.modelId(): String? = when (this) {
    is JsonPrimitive -> contentOrNull
    is JsonObject ->
        ((this["id"] as? JsonPrimitive) ?: (this["name"] as? JsonPrimitive))?.contentOrNull
    else -> null
}?.takeIf { it.isNotBlank() }

private val MODELS_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
}
