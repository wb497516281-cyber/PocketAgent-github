package com.pocket.agent.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pocket.agent.agent.AgentEngine
import com.pocket.agent.agent.AgentEvent
import com.pocket.agent.agent.ConfirmRequest
import com.pocket.agent.agent.ImagePreprocessor
import com.pocket.agent.agent.ToolContext
import com.pocket.agent.data.chat.ChatMessage
import com.pocket.agent.data.chat.ChatRole
import com.pocket.agent.data.chat.ChatSessionStore
import com.pocket.agent.data.chat.ImageAttachment
import com.pocket.agent.data.file.SafFileRepository
import com.pocket.agent.data.image.ImageCompressor
import com.pocket.agent.data.settings.ProviderConfig
import com.pocket.agent.data.settings.SettingsRepository
import com.pocket.agent.llm.LlmProviderFactory
import com.pocket.agent.util.AppContainer
import android.net.Uri
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * Drives one chat screen session.
 *
 * The transcript is a flat list of [ChatItem]s so streaming text, tool call
 * cards and error cards all live in one LazyColumn. [history] mirrors only the
 * user/assistant turns the API needs and is what gets persisted.
 */
class ChatViewModel(
    private val settingsRepository: SettingsRepository,
    private val chatSessionStore: ChatSessionStore,
    private val fileRepository: SafFileRepository,
    private val agentEngine: AgentEngine,
    private val providerFactory: LlmProviderFactory,
    private val imageCompressor: ImageCompressor,
    private val imagePreprocessor: ImagePreprocessor,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val history = mutableListOf<ChatMessage>()

    private var turnJob: Job? = null
    private var openAssistantId: String? = null
    private var pendingConfirmation: CompletableDeferred<Boolean>? = null

    init {
        observeProviders()
        restoreTranscript()
    }

    private fun observeProviders() {
        viewModelScope.launch {
            combine(
                settingsRepository.providers,
                settingsRepository.defaultProviderId,
            ) { providers, defaultId -> providers to defaultId }
                .collect { (providers, defaultId) ->
                    _uiState.update { state ->
                        val keepCurrent = state.selectedProviderId
                            ?.takeIf { id -> providers.any { it.id == id } }
                        val preferred = keepCurrent
                            ?: defaultId?.takeIf { id -> providers.any { it.id == id } }
                            ?: providers.firstOrNull()?.id
                        state.copy(providers = providers, selectedProviderId = preferred)
                    }
                }
        }
    }

    private fun restoreTranscript() {
        viewModelScope.launch {
            val restored = runCatching { chatSessionStore.history.first() }.getOrDefault(emptyList())
            if (restored.isEmpty()) return@launch
            history += restored
            val items = restored.map { message ->
                when (message.role) {
                    ChatRole.USER -> ChatItem.User(newId(), message.content)
                    else -> ChatItem.Assistant(newId(), message.content, streaming = false)
                }
            }
            _uiState.update { it.copy(items = items) }
        }
    }

    fun onInputChange(text: String) {
        _uiState.update { it.copy(input = text) }
    }

    /**
     * Compresses a picked photo and queues it for the next turn.
     *
     * Decoding and re-encoding are done on the IO dispatcher; the UI only ever
     * sees the finished, already-shrunk attachment. A photo that will not
     * compress is reported through the banner instead of throwing.
     */
    fun onImagePicked(uri: Uri) {
        if (_uiState.value.isRunning) return
        viewModelScope.launch {
            val attachment = runCatching {
                withContext(Dispatchers.IO) { imageCompressor.compress(uri) }
            }.getOrNull()
            if (attachment == null) {
                _uiState.update { it.copy(banner = "图片读取或压缩失败，请换一张试试") }
                return@launch
            }
            _uiState.update { it.copy(pendingImages = it.pendingImages + attachment) }
        }
    }

    fun removePendingImage(uri: String) {
        _uiState.update { state ->
            state.copy(pendingImages = state.pendingImages.filterNot { it.uri == uri })
        }
    }

    fun selectProvider(id: String) {
        _uiState.update { it.copy(selectedProviderId = id) }
    }

    fun dismissBanner() {
        _uiState.update { it.copy(banner = null) }
    }

    fun send() {
        val state = _uiState.value
        val text = state.input.trim()
        if (state.isRunning) return
        if (text.isEmpty() && state.pendingImages.isEmpty()) return
        val provider = state.selectedProvider
        if (provider == null) {
            _uiState.update { it.copy(banner = "请先在「设置」中添加一个模型 Provider") }
            return
        }
        val images = state.pendingImages
        _uiState.update { it.copy(input = "", banner = null, pendingImages = emptyList()) }
        startTurn(userText = text, images = images, provider = provider, alreadyRecorded = false)
    }

    /** Re-runs the most recent user turn without duplicating it in the transcript. */
    fun retry() {
        val state = _uiState.value
        if (state.isRunning) return
        val provider = state.selectedProvider
        if (provider == null) {
            _uiState.update { it.copy(banner = "请先在「设置」中添加一个模型 Provider") }
            return
        }
        val lastUser = state.lastUserMessage
        if (lastUser == null) return
        // Drop the trailing error card so the retry starts from a clean slate.
        _uiState.update { current ->
            current.copy(
                items = current.items.dropLastWhile { it is ChatItem.Error },
                banner = null,
            )
        }
        // Whatever images that turn carried are replayed too, so switching to a
        // vision model and retrying actually resends them.
        val images = _uiState.value.items
            .lastOrNull { it is ChatItem.User }
            ?.let { (it as ChatItem.User).images }
            .orEmpty()
        startTurn(
            userText = lastUser,
            images = images,
            provider = provider,
            alreadyRecorded = true,
        )
    }

    fun stop() {
        turnJob?.cancel()
        turnJob = null
        pendingConfirmation?.complete(false)
        pendingConfirmation = null
        _uiState.update { it.copy(isRunning = false, pendingConfirmation = null) }
    }

    fun resolveConfirmation(approved: Boolean) {
        val deferred = pendingConfirmation ?: return
        pendingConfirmation = null
        _uiState.update { it.copy(pendingConfirmation = null) }
        deferred.complete(approved)
    }

    fun clearConversation() {
        turnJob?.cancel()
        turnJob = null
        pendingConfirmation?.complete(false)
        pendingConfirmation = null
        history.clear()
        openAssistantId = null
        val keep = _uiState.value.let { it.providers to it.selectedProviderId }
        _uiState.value = ChatUiState(providers = keep.first, selectedProviderId = keep.second)
        viewModelScope.launch { chatSessionStore.clear() }
    }

    private fun startTurn(
        userText: String,
        images: List<ImageAttachment>,
        provider: ProviderConfig,
        alreadyRecorded: Boolean,
    ) {
        turnJob = viewModelScope.launch {
            try {
                if (!alreadyRecorded) {
                    appendItem(ChatItem.User(newId(), userText, images))
                    history += ChatMessage(ChatRole.USER, userText, images = images)
                }
                _uiState.update { it.copy(isRunning = true, banner = null) }
                openAssistantId = null

                val model = provider.model.trim()
                if (model.isEmpty()) {
                    appendItem(ChatItem.Error(newId(), "Provider「${provider.name}」没有填写模型名"))
                    return@launch
                }

                val llmProvider = runCatching { providerFactory.create(provider) }
                    .getOrElse { error ->
                        appendItem(
                            ChatItem.Error(newId(), "无法创建 Provider：${error.message ?: "未知错误"}"),
                        )
                        return@launch
                    }

                val context = ToolContext(
                    files = fileRepository,
                    confirm = ::confirm,
                    // The turn's own photos, so a document tool can embed them
                    // when the model refers to one by position.
                    images = images,
                )
                val turnText = StringBuilder()

                // A vision-capable model reads the attachments itself; a text
                // only one has them OCR'd into the prompt first.
                val prepared = imagePreprocessor.prepare(
                    message = ChatMessage(ChatRole.USER, userText, images = images),
                    supportsVision = provider.supportsVision,
                )
                if (prepared.blocked) {
                    appendItem(
                        ChatItem.System(
                            newId(),
                            "图片里没有识别到文字，当前模型也不支持视觉。" +
                                "请切换到支持视觉的模型后重试。",
                        ),
                    )
                    return@launch
                }
                prepared.notice?.let { appendItem(ChatItem.System(newId(), it)) }
                if (history.isNotEmpty()) {
                    history[history.lastIndex] = prepared.message
                }

                agentEngine.run(
                    provider = llmProvider,
                    model = model,
                    // The engine appends the current user turn itself.
                    history = history.dropLast(1),
                    userMessage = prepared.message,
                    context = context,
                ).collect { event -> handleAgentEvent(event, turnText) }

                val finalText = turnText.toString().trim()
                if (finalText.isNotEmpty()) {
                    history += ChatMessage(ChatRole.ASSISTANT, finalText)
                }
            } finally {
                openAssistantId = null
                _uiState.update { it.copy(isRunning = false) }
                withContext(NonCancellable) { persistHistory() }
            }
        }
    }

    private fun handleAgentEvent(event: AgentEvent, turnText: StringBuilder) {
        when (event) {
            is AgentEvent.TextDelta -> {
                turnText.append(event.text)
                appendAssistantText(event.text)
            }

            is AgentEvent.ToolCallStarted -> {
                // Text that follows a tool call belongs to a fresh bubble.
                openAssistantId = null
                upsertToolItem(event.call.id) {
                    it.copy(
                        call = event.call,
                        status = ToolCallStatus.RUNNING,
                        result = null,
                        isError = false,
                        fileUri = null,
                    )
                }
            }

            is AgentEvent.ToolCallFinished -> {
                upsertToolItem(event.call.id) {
                    it.copy(
                        call = event.call,
                        status = if (event.isError) ToolCallStatus.FAILED else ToolCallStatus.DONE,
                        result = event.result,
                        isError = event.isError,
                        fileUri = event.fileUri,
                    )
                }
            }

            is AgentEvent.Finished -> Unit

            is AgentEvent.Error -> appendItem(ChatItem.Error(newId(), event.message))
        }
    }

    private fun appendAssistantText(text: String) {
        val openId = openAssistantId
        val open = _uiState.value.items.firstOrNull { it.id == openId } as? ChatItem.Assistant
        if (open != null) {
            updateAssistantText(open.id) { item ->
                item.copy(text = item.text + text, streaming = true)
            }
            return
        }
        val id = newId()
        openAssistantId = id
        appendItem(ChatItem.Assistant(id = id, text = text, streaming = true))
    }

    private fun upsertToolItem(callId: String, transform: (ChatItem.Tool) -> ChatItem.Tool) {
        // ToolCallStarted always fires first, so the card is already in the list.
        _uiState.update { state ->
            state.copy(
                items = state.items.map { item ->
                    if (item is ChatItem.Tool && item.call.id == callId) transform(item) else item
                },
            )
        }
    }

    private fun appendItem(item: ChatItem) {
        _uiState.update { it.copy(items = it.items + item) }
    }

    private fun updateAssistantText(id: String, transform: (ChatItem.Assistant) -> ChatItem.Assistant) {
        _uiState.update { state ->
            state.copy(
                items = state.items.map { item ->
                    if (item.id == id && item is ChatItem.Assistant) transform(item) else item
                },
            )
        }
    }

    private suspend fun confirm(request: ConfirmRequest): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingConfirmation = deferred
        _uiState.update { it.copy(pendingConfirmation = request) }
        return try {
            deferred.await()
        } finally {
            pendingConfirmation = null
            _uiState.update { it.copy(pendingConfirmation = null) }
        }
    }

    private suspend fun persistHistory() {
        val snapshot = history.toList()
        runCatching { chatSessionStore.save(snapshot) }
    }

    private fun newId(): String = UUID.randomUUID().toString()

    companion object {

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(
                    settingsRepository = container.settingsRepository,
                    chatSessionStore = container.chatSessionStore,
                    fileRepository = container.fileRepository,
                    agentEngine = container.agentEngine,
                    providerFactory = container.providerFactory,
                    imageCompressor = container.imageCompressor,
                    imagePreprocessor = container.imagePreprocessor,
                ) as T
            }
    }
}
