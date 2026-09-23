package com.pocket.agent.data.chat
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.chatDataStore: DataStore<Preferences> by preferencesDataStore(name = "chat_history")

/**
 * Keeps the last conversation across process death so a restart resumes where
 * the user left off. Only user/assistant text turns are persisted; tool chatter
 * would bloat the store and is not needed to continue a conversation.
 */
class ChatSessionStore(context: Context) {

    private val dataStore = context.chatDataStore
    private val json = Json { ignoreUnknownKeys = true }

    val history: Flow<List<ChatMessage>> = dataStore.data.map { prefs ->
        val raw = prefs[KEY_HISTORY] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<ChatMessage>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun save(messages: List<ChatMessage>) {
        // Image base64 would bloat the store many times over, so attachments
        // are dropped here; restored bubbles simply show the text again.
        val trimmed = messages.takeLast(MAX_PERSISTED_TURNS).map { message ->
            if (message.images.isEmpty()) message else message.copy(images = emptyList())
        }
        dataStore.edit { prefs ->
            if (trimmed.isEmpty()) {
                prefs.remove(KEY_HISTORY)
            } else {
                prefs[KEY_HISTORY] = json.encodeToString(trimmed)
            }
        }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(KEY_HISTORY) }
    }

    private companion object {
        val KEY_HISTORY = stringPreferencesKey("history_json")
        const val MAX_PERSISTED_TURNS = 60
    }
}
