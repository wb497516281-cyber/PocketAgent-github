package com.pocket.agent.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "agent_settings")

/**
 * Persists provider configuration and the id of the default model.
 *
 * The provider list is stored as one JSON blob: it is small, always read and
 * written as a whole, and keeps the schema free to grow without migrations.
 *
 * The same store also holds web search settings and the cached model
 * catalogues; secrets never live here - only opaque references resolved
 * through [ApiKeyStore].
 */
class SettingsRepository(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val providers: Flow<List<ProviderConfig>> = dataStore.data.map { prefs ->
        val raw = prefs[KEY_PROVIDERS] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<ProviderConfig>>(raw) }.getOrDefault(emptyList())
    }

    val defaultProviderId: Flow<String?> = dataStore.data.map { it[KEY_DEFAULT_PROVIDER] }

    /** Tavily configuration; the key itself stays in [ApiKeyStore]. */
    val searchConfig: Flow<SearchConfig> = dataStore.data.map { prefs ->
        SearchConfig(tavilyApiKeyRef = prefs[KEY_TAVILY_KEY_REF]?.takeIf { it.isNotBlank() })
    }

    /** The provider the chat screen should use, falling back to the first one. */
    val activeProvider: Flow<ProviderConfig?> = dataStore.data.map { prefs ->
        val all = prefs[KEY_PROVIDERS]
            ?.let { raw -> runCatching { json.decodeFromString<List<ProviderConfig>>(raw) }.getOrNull() }
            ?: emptyList()
        val defaultId = prefs[KEY_DEFAULT_PROVIDER]
        all.firstOrNull { it.id == defaultId } ?: all.firstOrNull()
    }

    suspend fun upsertProvider(config: ProviderConfig) {
        dataStore.edit { prefs ->
            val current = decodeProviders(prefs[KEY_PROVIDERS])
            val index = current.indexOfFirst { it.id == config.id }
            val updated = if (index >= 0) {
                current.toMutableList().also { it[index] = config }
            } else {
                current + config
            }
            prefs[KEY_PROVIDERS] = json.encodeToString(updated)
        }
    }

    suspend fun deleteProvider(id: String) {
        dataStore.edit { prefs ->
            val updated = decodeProviders(prefs[KEY_PROVIDERS]).filterNot { it.id == id }
            prefs[KEY_PROVIDERS] = json.encodeToString(updated)
            if (prefs[KEY_DEFAULT_PROVIDER] == id) {
                prefs[KEY_DEFAULT_PROVIDER] = updated.firstOrNull()?.id ?: ""
            }
        }
    }

    suspend fun setDefaultProvider(id: String) {
        dataStore.edit { it[KEY_DEFAULT_PROVIDER] = id }
    }

    suspend fun setTavilyApiKeyRef(ref: String?) {
        dataStore.edit { prefs ->
            if (ref.isNullOrBlank()) {
                prefs.remove(KEY_TAVILY_KEY_REF)
            } else {
                prefs[KEY_TAVILY_KEY_REF] = ref
            }
        }
    }

    /** Current opaque Tavily key reference, resolved once at call time. */
    suspend fun tavilyApiKeyRef(): String? =
        dataStore.data.map { it[KEY_TAVILY_KEY_REF]?.takeIf { ref -> ref.isNotBlank() } }.first()

    /**
     * Models an endpoint reported the last time they were fetched, keyed by
     * normalised Base URL. The cache lets the editor show a dropdown without
     * a network request on every visit.
     */
    suspend fun readModelCache(baseUrl: String): List<String> {
        val key = normalizeBaseUrl(baseUrl)
        if (key.isEmpty()) return emptyList()
        val raw = dataStore.data.map { it[KEY_MODEL_CACHE] }.first() ?: return emptyList()
        val cache = runCatching {
            json.decodeFromString<Map<String, List<String>>>(raw)
        }.getOrDefault(emptyMap())
        return cache[key].orEmpty()
    }

    suspend fun saveModelCache(baseUrl: String, models: List<String>) {
        val key = normalizeBaseUrl(baseUrl)
        if (key.isEmpty() || models.isEmpty()) return
        dataStore.edit { prefs ->
            val current = prefs[KEY_MODEL_CACHE]
                ?.let { raw -> runCatching { json.decodeFromString<Map<String, List<String>>>(raw) }.getOrNull() }
                .orEmpty()
                .toMutableMap()
            current[key] = models
            prefs[KEY_MODEL_CACHE] = json.encodeToString(current)
        }
    }

    suspend fun findProvider(id: String): ProviderConfig? {
        return dataStore.data
            .map { prefs -> decodeProviders(prefs[KEY_PROVIDERS]).firstOrNull { it.id == id } }
            .first()
    }

    private fun decodeProviders(raw: String?): List<ProviderConfig> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<ProviderConfig>>(raw) }.getOrDefault(emptyList())
    }

    private companion object {
        val KEY_PROVIDERS = stringPreferencesKey("providers_json")
        val KEY_DEFAULT_PROVIDER = stringPreferencesKey("default_provider_id")
        val KEY_TAVILY_KEY_REF = stringPreferencesKey("tavily_api_key_ref")
        val KEY_MODEL_CACHE = stringPreferencesKey("model_cache_json")
    }
}

/** Web search settings. Only the opaque key reference is persisted. */
data class SearchConfig(val tavilyApiKeyRef: String? = null)

/** Cache keys ignore casing and trailing slashes so edits keep their entry. */
internal fun normalizeBaseUrl(baseUrl: String): String =
    baseUrl.trim().removeSuffix("/").lowercase()
