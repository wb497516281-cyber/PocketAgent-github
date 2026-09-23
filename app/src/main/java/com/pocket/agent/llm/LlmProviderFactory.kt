package com.pocket.agent.llm

import com.pocket.agent.data.settings.ApiKeyStore
import com.pocket.agent.data.settings.ProviderConfig
import com.pocket.agent.data.settings.ProviderType
import okhttp3.OkHttpClient

/** Builds an [LlmProvider] for a stored configuration. */
class LlmProviderFactory(
    private val apiKeyStore: ApiKeyStore,
    private val client: OkHttpClient,
) {

    /**
     * Creates a provider for [config].
     *
     * [apiKeyOverride] lets a caller - the settings editor - use a key that
     * was typed but not saved yet; when it is null the key is resolved from
     * [ApiKeyStore] via the config's reference, as usual.
     */
    fun create(config: ProviderConfig): LlmProvider = when (config.type) {
        ProviderType.OPENAI_COMPATIBLE -> OpenAiCompatibleProvider(
            config = config,
            apiKeyProvider = { apiKeyStore.read(config.apiKeyRef) },
            client = client,
        )
    }

    fun create(config: ProviderConfig, apiKeyOverride: String?): LlmProvider = when (config.type) {
        ProviderType.OPENAI_COMPATIBLE -> OpenAiCompatibleProvider(
            config = config,
            apiKeyProvider = { apiKeyOverride ?: apiKeyStore.read(config.apiKeyRef) },
            client = client,
        )
    }
}
