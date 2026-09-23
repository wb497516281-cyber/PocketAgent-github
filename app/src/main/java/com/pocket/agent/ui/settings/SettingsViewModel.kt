package com.pocket.agent.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pocket.agent.data.chat.ChatMessage
import com.pocket.agent.data.chat.ChatRole
import com.pocket.agent.data.settings.ApiKeyStore
import com.pocket.agent.data.settings.ProviderConfig
import com.pocket.agent.data.settings.ProviderPreset
import com.pocket.agent.data.settings.ProviderType
import com.pocket.agent.data.settings.SettingsRepository
import com.pocket.agent.llm.ChatEvent
import com.pocket.agent.llm.ChatRequest
import com.pocket.agent.llm.ModelFetchResult
import com.pocket.agent.llm.LlmProviderFactory
import com.pocket.agent.llm.guessVisionSupport
import com.pocket.agent.util.AppContainer
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Editable draft of a provider. `id == null` means "not saved yet". */
data class EditorState(
    val id: String? = null,
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val hasStoredKey: Boolean = false,
    val model: String = "",
    val extraHeaders: String = "",
    val suggestedModels: List<String> = emptyList(),
    val keyHint: String = "",
    val note: String? = null,
    val error: String? = null,
    /** Models the endpoint reported, cached or freshly fetched. */
    val availableModels: List<String> = emptyList(),
    val isLoadingModels: Boolean = false,
    /** Hint or error about the last fetch, e.g. the manual-input fallback. */
    val modelsStatus: String? = null,
    val modelsStatusIsError: Boolean = false,
    /** Multimodal models read images; text-only ones get the local OCR fallback. */
    val supportsVision: Boolean = false,
    /** True once the checkbox is set by hand; then it stops following the model id. */
    val visionTouched: Boolean = false,
)

/** Result of a connection probe, shown inline under a provider card. */
data class TestOutcome(val ok: Boolean, val message: String)

data class SettingsUiState(
    val providers: List<ProviderConfig> = emptyList(),
    val defaultProviderId: String? = null,
    val editor: EditorState? = null,
    val showPresetChooser: Boolean = false,
    val testingIds: Set<String> = emptySet(),
    val results: Map<String, TestOutcome> = emptyMap(),
    val tavilyKeyDraft: String = "",
    val tavilyKeySaved: Boolean = false,
    val tavilyBusy: Boolean = false,
    val tavilyStatus: String? = null,
)

/**
 * Owns provider configuration.
 *
 * The API key is written straight to [ApiKeyStore] and never travels back into
 * the draft: editing shows an empty field with [EditorState.hasStoredKey] set,
 * so leaving it blank keeps the stored secret untouched.
 */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val apiKeyStore: ApiKeyStore,
    private val providerFactory: LlmProviderFactory,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.providers.collect { providers ->
                _uiState.update { it.copy(providers = providers) }
            }
        }
        viewModelScope.launch {
            settingsRepository.defaultProviderId.collect { id ->
                _uiState.update { it.copy(defaultProviderId = id) }
            }
        }
        viewModelScope.launch {
            settingsRepository.searchConfig.collect { config ->
                _uiState.update { it.copy(tavilyKeySaved = config.tavilyApiKeyRef != null) }
            }
        }
    }

    fun startCreate() {
        _uiState.update { it.copy(showPresetChooser = true) }
    }

    /** Opens a blank editor for endpoints that are not in the preset list. */
    fun startCustom() {
        _uiState.update { it.copy(showPresetChooser = false, editor = EditorState()) }
    }

    fun dismissChooser() {
        _uiState.update { it.copy(showPresetChooser = false) }
    }

    /**
     * Pre-fills the draft from a vendor template. The chooser stays flagged open so
     * closing the editor returns here instead of dropping the user on the list.
     */
    fun choosePreset(preset: ProviderPreset) {
        _uiState.update { state ->
            state.copy(
                showPresetChooser = true,
                editor = EditorState(
                    name = preset.displayName,
                    baseUrl = preset.baseUrl,
                    model = preset.defaultModel,
                    supportsVision = guessVisionSupport(preset.defaultModel),
                    suggestedModels = preset.suggestedModels,
                    keyHint = preset.keyHint,
                    note = preset.note,
                    extraHeaders = preset.suggestedHeaders.entries
                        .joinToString("\n") { (name, value) -> "$name: $value" },
                ),
            )
        }
    }

    fun startEdit(provider: ProviderConfig) {
        _uiState.update { state ->
            state.copy(
                editor = EditorState(
                    id = provider.id,
                    name = provider.name,
                    baseUrl = provider.baseUrl,
                    apiKey = "",
                    hasStoredKey = !apiKeyStore.read(provider.apiKeyRef).isNullOrBlank(),
                    model = provider.model,
                    supportsVision = provider.supportsVision,
                    extraHeaders = provider.extraHeaders.entries.joinToString("\n") { (k, v) -> "$k: $v" },
                ),
            )
        }
        // The cached catalogue populates the dropdown without a network call;
        // refreshing stays an explicit action.
        val baseUrl = provider.baseUrl
        viewModelScope.launch {
            val cached = settingsRepository.readModelCache(baseUrl)
            _uiState.update { state ->
                val editor = state.editor?.takeIf { it.baseUrl == baseUrl } ?: return@update state
                state.copy(editor = editor.copy(availableModels = cached))
            }
        }
    }

    /**
     * Pulls the endpoint's model list and offers it as a dropdown.
     *
     * Both the "获取模型" and "刷新" buttons land here: the network is only
     * ever hit on an explicit user action. Unsupported endpoints keep the
     * manual model field and explain why via [EditorState.modelsStatus].
     */
    fun fetchModels() {
        val editor = _uiState.value.editor ?: return
        if (editor.isLoadingModels) return
        val existing = editor.id?.let { id -> _uiState.value.providers.firstOrNull { it.id == id } }
        val validation = validateForFetch(editor, existing)
        if (validation != null) {
            _uiState.update { it.copy(editor = editor.copy(error = validation, modelsStatus = null)) }
            return
        }
        val provider = buildProvider(editor, existing) ?: return
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(editor = state.editor?.copy(isLoadingModels = true, modelsStatus = null))
            }
            val result = runCatching {
                providerFactory.create(provider, editor.apiKey.ifBlank { null }).fetchModels()
            }.getOrElse { error ->
                ModelFetchResult.Failure(error.message ?: "获取模型列表失败")
            }
            // Cached before the state update so a retry of the CAS loop in
            // `update` cannot write twice.
            if (result is ModelFetchResult.Success && result.models.isNotEmpty()) {
                settingsRepository.saveModelCache(provider.baseUrl, result.models)
            }
            _uiState.update { state ->
                val current = state.editor ?: return@update state
                val updated = when (result) {
                    is ModelFetchResult.Success -> {
                        if (result.models.isEmpty()) {
                            current.copy(isLoadingModels = false)
                        } else {
                            // Keep a typed model only if the endpoint still
                            // offers it; otherwise preselect the first hit.
                            val model = if (current.model.isNotBlank() &&
                                result.models.contains(current.model.trim())
                            ) {
                                current.model.trim()
                            } else {
                                result.models.first()
                            }
                            current.applyModelChange(model, current.model).copy(
                                availableModels = result.models,
                                isLoadingModels = false,
                                modelsStatus = "已获取 ${result.models.size} 个模型",
                                modelsStatusIsError = false,
                            )
                        }
                    }
                    is ModelFetchResult.Unsupported -> current.copy(
                        isLoadingModels = false,
                        availableModels = emptyList(),
                        modelsStatus = UNSUPPORTED_MODELS_HINT,
                        modelsStatusIsError = false,
                    )
                    is ModelFetchResult.Failure -> current.copy(
                        isLoadingModels = false,
                        // A failed refresh keeps any cached list usable.
                        modelsStatus = result.message,
                        modelsStatusIsError = true,
                    )
                }
                state.copy(editor = updated)
            }
        }
    }

    /** Records a dropdown pick as the editor's current model. */
    fun selectModel(model: String) {
        _uiState.update { state ->
            val editor = state.editor ?: return@update state
            state.copy(editor = editor.applyModelChange(model, editor.model).copy(error = null))
        }
    }

    /**
     * Toggles the multimodal flag by hand.
     *
     * Once the user has spoken, the checkbox stops following the model id:
     * gateways carry vision models under ids no keyword list can keep up with.
     */
    fun setSupportsVision(value: Boolean) {
        _uiState.update { state ->
            val editor = state.editor ?: return@update state
            state.copy(
                editor = editor.copy(
                    supportsVision = value,
                    visionTouched = true,
                    error = null,
                ),
            )
        }
    }

    fun dismissEditor() {
        _uiState.update { it.copy(editor = null) }
    }

    fun updateEditor(transform: (EditorState) -> EditorState) {
        _uiState.update { state ->
            val editor = state.editor ?: return@update state
            val transformed = transform(editor)
            val updated = transformed
                .applyModelChange(transformed.model, editor.model)
                .copy(error = null)
            // A catalogue only belongs to the Base URL it was fetched from;
            // once the user points at another endpoint the dropdown would be
            // stale, so it is dropped back to the manual input.
            val baseUrlChanged = updated.baseUrl != editor.baseUrl
            state.copy(
                editor = if (baseUrlChanged) {
                    updated.copy(
                        availableModels = emptyList(),
                        modelsStatus = null,
                    )
                } else {
                    updated
                },
            )
        }
    }

    fun save() {
        val editor = _uiState.value.editor ?: return
        val existing = editor.id?.let { id -> _uiState.value.providers.firstOrNull { it.id == id } }
        val validation = validate(editor, existing)
        if (validation != null) {
            _uiState.update { it.copy(editor = editor.copy(error = validation)) }
            return
        }

        val apiKeyRef = existing?.apiKeyRef ?: apiKeyStore.newRef()
        val provider = ProviderConfig(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = editor.name.trim().ifBlank { editor.model.trim() },
            type = ProviderType.OPENAI_COMPATIBLE,
            baseUrl = editor.baseUrl.trim().removeSuffix("/"),
            apiKeyRef = apiKeyRef,
            model = editor.model.trim(),
            extraHeaders = parseHeaders(editor.extraHeaders),
            supportsVision = editor.supportsVision,
        )

        viewModelScope.launch {
            if (editor.apiKey.isNotBlank()) apiKeyStore.save(apiKeyRef, editor.apiKey)
            settingsRepository.upsertProvider(provider)
            if (existing == null) {
                // The first provider becomes the default so chat works at once.
                settingsRepository.setDefaultProvider(provider.id)
            }
            _uiState.update { it.copy(editor = null, showPresetChooser = false) }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            val provider = settingsRepository.findProvider(id)
            settingsRepository.deleteProvider(id)
            provider?.let { apiKeyStore.delete(it.apiKeyRef) }
            _uiState.update { state ->
                state.copy(results = state.results - id, editor = state.editor?.takeIf { it.id != id })
            }
        }
    }

    fun setDefault(id: String) {
        viewModelScope.launch { settingsRepository.setDefaultProvider(id) }
    }

    fun dismissResult(id: String) {
        _uiState.update { it.copy(results = it.results - id) }
    }

    fun updateTavilyKeyDraft(value: String) {
        _uiState.update { it.copy(tavilyKeyDraft = value, tavilyStatus = null) }
    }

    /**
     * Stores the Tavily key in [ApiKeyStore]; DataStore keeps only the new
     * reference, matching how provider keys are handled.
     */
    fun saveTavilyKey() {
        val draft = _uiState.value.tavilyKeyDraft.trim()
        if (draft.isEmpty()) {
            _uiState.update { it.copy(tavilyStatus = "请输入 Tavily API Key") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(tavilyBusy = true) }
            val existingRef = settingsRepository.tavilyApiKeyRef()
            val ref = existingRef ?: apiKeyStore.newRef()
            apiKeyStore.save(ref, draft)
            settingsRepository.setTavilyApiKeyRef(ref)
            _uiState.update {
                it.copy(
                    tavilyKeyDraft = "",
                    tavilyKeySaved = true,
                    tavilyBusy = false,
                    tavilyStatus = "已保存，Agent 现在可以联网搜索",
                )
            }
        }
    }

    fun clearTavilyKey() {
        viewModelScope.launch {
            _uiState.update { it.copy(tavilyBusy = true) }
            val ref = settingsRepository.tavilyApiKeyRef()
            ref?.let { apiKeyStore.delete(it) }
            settingsRepository.setTavilyApiKeyRef(null)
            _uiState.update {
                it.copy(
                    tavilyKeyDraft = "",
                    tavilyKeySaved = false,
                    tavilyBusy = false,
                    tavilyStatus = "已清除，Agent 将无法联网搜索",
                )
            }
        }
    }

    fun dismissTavilyStatus() {
        _uiState.update { it.copy(tavilyStatus = null) }
    }

    /** Probes a saved provider with a one-shot, non-streaming request. */
    fun testConnection(provider: ProviderConfig) {
        if (provider.id in _uiState.value.testingIds) return
        viewModelScope.launch {
            _uiState.update { it.copy(testingIds = it.testingIds + provider.id) }
            val outcome = probe(provider)
            _uiState.update { state ->
                state.copy(
                    testingIds = state.testingIds - provider.id,
                    results = state.results + (provider.id to outcome),
                )
            }
        }
    }

    /** Saves the draft first so a freshly typed key is resolvable, then probes. */
    fun testEditor() {
        val editor = _uiState.value.editor ?: return
        val existing = editor.id?.let { id -> _uiState.value.providers.firstOrNull { it.id == id } }
        val validation = validate(editor, existing)
        if (validation != null) {
            _uiState.update { it.copy(editor = editor.copy(error = validation)) }
            return
        }
        val provider = buildProvider(editor, existing) ?: return
        viewModelScope.launch {
            if (editor.apiKey.isNotBlank()) apiKeyStore.save(provider.apiKeyRef, editor.apiKey)
            settingsRepository.upsertProvider(provider)
            _uiState.update { it.copy(testingIds = it.testingIds + provider.id) }
            val outcome = probe(provider)
            _uiState.update { state ->
                state.copy(
                    testingIds = state.testingIds - provider.id,
                    results = state.results + (provider.id to outcome),
                )
            }
        }
    }

    private fun buildProvider(editor: EditorState, existing: ProviderConfig?): ProviderConfig? {
        val apiKeyRef = existing?.apiKeyRef ?: apiKeyStore.newRef()
        return ProviderConfig(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = editor.name.trim().ifBlank { editor.model.trim() },
            type = ProviderType.OPENAI_COMPATIBLE,
            baseUrl = editor.baseUrl.trim().removeSuffix("/"),
            apiKeyRef = apiKeyRef,
            model = editor.model.trim(),
            extraHeaders = parseHeaders(editor.extraHeaders),
            supportsVision = editor.supportsVision,
        )
    }

    private suspend fun probe(provider: ProviderConfig): TestOutcome {
        val llm = runCatching { providerFactory.create(provider) }.getOrElse { error ->
            return TestOutcome(false, error.message ?: "无法创建 Provider")
        }
        var failure: String? = null
        var succeeded = false
        llm.chat(
            ChatRequest(
                model = provider.model,
                messages = listOf(ChatMessage(ChatRole.USER, PROBE_MESSAGE)),
                tools = emptyList(),
                stream = false,
            ),
        )
            .catch { error -> failure = error.message ?: "请求失败" }
            .collect { event ->
                when (event) {
                    is ChatEvent.Finished -> succeeded = true
                    is ChatEvent.Error -> failure = event.message
                    else -> Unit
                }
            }
        return when {
            succeeded -> TestOutcome(true, "连接成功，模型 ${provider.model} 可用")
            else -> TestOutcome(false, failure ?: "没有收到任何响应")
        }
    }

    private fun validate(editor: EditorState, existing: ProviderConfig?): String? {
        if (editor.baseUrl.isBlank()) return "请填写 Base URL"
        val scheme = editor.baseUrl.trim().substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") return "Base URL 必须以 http:// 或 https:// 开头"
        if (editor.model.isBlank()) return "请填写模型名"
        if (editor.apiKey.isBlank() && existing == null) return "请填写 API Key"
        if (editor.apiKey.isBlank() && existing != null && !editor.hasStoredKey) {
            return "请填写 API Key"
        }
        parseHeaders(editor.extraHeaders).keys.forEach { key ->
            if (key.isBlank()) return "附加请求头格式应为「名称: 值」，每行一条"
        }
        return null
    }

    /** Fetching needs a reachable endpoint and a resolvable key, no model yet. */
    private fun validateForFetch(editor: EditorState, existing: ProviderConfig?): String? {
        if (editor.baseUrl.isBlank()) return "请先填写 Base URL 再获取模型"
        val scheme = editor.baseUrl.trim().substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") return "Base URL 必须以 http:// 或 https:// 开头"
        if (editor.apiKey.isBlank() && existing == null) return "请先填写 API Key 再获取模型"
        if (editor.apiKey.isBlank() && existing != null && !editor.hasStoredKey) {
            return "请先填写 API Key 再获取模型"
        }
        return null
    }

    private fun parseHeaders(raw: String): Map<String, String> =
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) return@mapNotNull null
                val name = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                if (name.isEmpty() || value.isEmpty()) null else name to value
            }
            .toMap()

    companion object {
        private const val PROBE_MESSAGE = "ping"

        /** Shown when the endpoint has no `/models` route at all. */
        private const val UNSUPPORTED_MODELS_HINT = "该供应商不支持自动获取模型，请手动输入"

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(
                    settingsRepository = container.settingsRepository,
                    apiKeyStore = container.apiKeyStore,
                    providerFactory = container.providerFactory,
                ) as T
            }
    }
}

/**
 * Narrows a fetched catalogue down to what the search box holds.
 *
 * A blank query means "show everything": the menu opens on the full list and
 * the user narrows it from there. Matching is case-insensitive because model
 * ids are inconsistently cased across gateways.
 */
internal fun filterModelMatches(models: List<String>, query: String): List<String> {
    val needle = query.trim()
    if (needle.isEmpty()) return models
    return models.filter { it.contains(needle, ignoreCase = true) }
}

/**
 * Points the draft at [nextModel], re-deriving the vision flag when the id
 * really changed and the user has not set the checkbox by hand.
 *
 * Leaving the flag alone for a no-op change matters: the preset chips and the
 * dropdown both rewrite the same model back into the draft, and that must not
 * silently undo a manual choice.
 */
private fun EditorState.applyModelChange(nextModel: String, previousModel: String): EditorState {
    if (nextModel == previousModel) return copy(model = nextModel)
    return if (visionTouched) {
        copy(model = nextModel)
    } else {
        copy(model = nextModel, supportsVision = guessVisionSupport(nextModel))
    }
}
