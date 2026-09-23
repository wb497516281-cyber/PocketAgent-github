package com.pocket.agent.ui.chat

import com.pocket.agent.agent.ConfirmRequest
import com.pocket.agent.data.chat.ImageAttachment
import com.pocket.agent.data.chat.ToolCall
import com.pocket.agent.data.settings.ProviderConfig

/** Lifecycle of a tool call card in the transcript. */
enum class ToolCallStatus { RUNNING, DONE, FAILED }

/** One row of the transcript. */
sealed interface ChatItem {

    val id: String

    data class User(
        override val id: String,
        val text: String,
        val images: List<ImageAttachment> = emptyList(),
    ) : ChatItem

    data class Assistant(override val id: String, val text: String, val streaming: Boolean) : ChatItem

    /**
     * A note the app wrote, not the model: an OCR fallback, a refused
     * overwrite. Rendered as plain muted text so it never reads like an answer.
     */
    data class System(override val id: String, val text: String) : ChatItem

    data class Tool(
        override val id: String,
        val call: ToolCall,
        val status: ToolCallStatus,
        val result: String?,
        val isError: Boolean,
        /** SAF uri of a generated file, offered as an "open file" action. */
        val fileUri: String? = null,
    ) : ChatItem

    data class Error(override val id: String, val message: String) : ChatItem
}

data class ChatUiState(
    val items: List<ChatItem> = emptyList(),
    val input: String = "",
    val isRunning: Boolean = false,
    val providers: List<ProviderConfig> = emptyList(),
    val selectedProviderId: String? = null,
    val pendingConfirmation: ConfirmRequest? = null,
    val banner: String? = null,
    /** Images already compressed and waiting to go out with the next turn. */
    val pendingImages: List<ImageAttachment> = emptyList(),
) {

    val selectedProvider: ProviderConfig?
        get() = providers.firstOrNull { it.id == selectedProviderId }

    /** A turn worth sending needs words, an image, or both. */
    val canSend: Boolean
        get() = !isRunning &&
            (input.isNotBlank() || pendingImages.isNotEmpty()) &&
            selectedProvider != null

    val lastUserMessage: String?
        get() = items.lastOrNull { it is ChatItem.User }?.let { (it as ChatItem.User).text }
}
