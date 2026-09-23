package com.pocket.agent.llm

import com.pocket.agent.data.chat.ChatMessage
import kotlinx.serialization.json.JsonObject

/** A function the model may call, described with a JSON Schema object. */
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

/** One chat completion request. */
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val stream: Boolean = true,
    val maxTokens: Int? = null,
)

/**
 * A fragment of a tool call as reported by the wire.
 *
 * Providers stream tool calls by index: the first chunk carries `id` and
 * `name`, later chunks only carry argument text.
 */
data class ToolCallDelta(
    val index: Int,
    val id: String? = null,
    val name: String? = null,
    val argumentsDelta: String? = null,
)

/** Everything the UI and the agent loop need to observe about one model turn. */
sealed interface ChatEvent {

    /** Incremental assistant text. */
    data class TextDelta(val text: String) : ChatEvent

    /** Incremental tool call fragments; merge them by [ToolCallDelta.index]. */
    data class ToolCallEvent(val calls: List<ToolCallDelta>) : ChatEvent

    /** The stream ended normally. */
    data object Finished : ChatEvent

    /** The turn failed; [message] is safe to show to the user. */
    data class Error(val message: String, val cause: Throwable? = null) : ChatEvent
}
