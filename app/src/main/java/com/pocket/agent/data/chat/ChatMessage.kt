package com.pocket.agent.data.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Role of a message inside an OpenAI-compatible conversation. */
@Serializable
enum class ChatRole(val wireName: String) {
    @SerialName("system")
    SYSTEM("system"),

    @SerialName("user")
    USER("user"),

    @SerialName("assistant")
    ASSISTANT("assistant"),

    @SerialName("tool")
    TOOL("tool"),
}

/**
 * A single tool invocation emitted by the model.
 *
 * [arguments] is the raw JSON argument string. While streaming it arrives in
 * fragments, so providers report it partially; only once the stream is finished
 * is it guaranteed to be parseable.
 */
@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

/**
 * One turn of the conversation sent to the model.
 *
 * [toolCallId] links a `tool` role message back to the invocation it answers.
 * [toolCalls] is only populated on `assistant` messages that requested tools.
 * [images] carries the attachments of a user turn; it is dropped before the
 * message is persisted so base64 payloads never bloat DataStore.
 */
@Serializable
data class ChatMessage(
    val role: ChatRole,
    val content: String,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val images: List<ImageAttachment> = emptyList(),
)
