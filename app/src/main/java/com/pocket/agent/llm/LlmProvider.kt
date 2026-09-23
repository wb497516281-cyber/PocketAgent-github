package com.pocket.agent.llm

import kotlinx.coroutines.flow.Flow

/**
 * A chat model endpoint.
 *
 * Implementations are bound to a single [com.pocket.agent.data.settings.ProviderConfig]
 * plus its API key, so the only input left is the request itself. Every
 * implementation must emit exactly one terminal event: either
 * [ChatEvent.Finished] or [ChatEvent.Error].
 */
interface LlmProvider {

    fun chat(request: ChatRequest): Flow<ChatEvent>

    /**
     * Lists the chat models this endpoint offers, when it offers them.
     *
     * Implementations never throw: transport trouble comes back as
     * [ModelFetchResult.Failure] and an endpoint with no catalogue as
     * [ModelFetchResult.Unsupported], so the settings screen can keep a manual
     * model field instead of crashing.
     */
    suspend fun fetchModels(): ModelFetchResult
}
