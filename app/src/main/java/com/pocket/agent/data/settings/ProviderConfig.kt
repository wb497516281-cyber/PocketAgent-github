package com.pocket.agent.data.settings

import kotlinx.serialization.Serializable

/** Wire protocol of a configured endpoint. */
@Serializable
enum class ProviderType {
    /** `POST {baseUrl}/chat/completions` with Bearer auth and SSE streaming. */
    OPENAI_COMPATIBLE,
}

/**
 * A user-configured model endpoint.
 *
 * [apiKeyRef] is an opaque handle into the encrypted key store, never the key
 * itself, so this object is safe to persist in plain DataStore.
 *
 * [supportsVision] marks a multimodal model. A false value sends images down
 * the local OCR fallback instead of the wire format the model cannot read.
 */
@Serializable
data class ProviderConfig(
    val id: String,
    val name: String,
    val type: ProviderType,
    val baseUrl: String,
    val apiKeyRef: String,
    val model: String,
    val extraHeaders: Map<String, String> = emptyMap(),
    val supportsVision: Boolean = false,
) {
    /** Human readable one-liner used in pickers and cards. */
    val label: String get() = if (name.isBlank()) model else "$name - $model"
}
