package com.pocket.agent.data.chat

import kotlinx.serialization.Serializable

/**
 * One image attached to a message.
 *
 * [base64] holds the already-compressed JPEG bytes with no data-URL prefix;
 * each provider wraps it into the wire shape it wants. [uri] exists only to
 * render a thumbnail and is dropped before the message is persisted, so the
 * base64 payload never bloats DataStore.
 */
@Serializable
data class ImageAttachment(
    val uri: String,
    val base64: String,
    val mimeType: String = "image/jpeg",
)
