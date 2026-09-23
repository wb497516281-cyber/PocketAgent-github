package com.pocket.agent.data.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * Holds API keys in [EncryptedSharedPreferences] so the plaintext never touches
 * normal app storage or backups.
 *
 * Callers only ever see an opaque [ProviderConfig.apiKeyRef]; the key itself is
 * resolved through [read] right before a request is sent.
 */
class ApiKeyStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy { openOrRecreate() }

    private fun openOrRecreate(): SharedPreferences {
        return try {
            create()
        } catch (t: Throwable) {
            // A corrupted keyset (rare, e.g. after a restored backup) is the one
            // case where wiping the store is the only way forward.
            Log.w(TAG, "Encrypted key store unusable, recreating", t)
            runCatching { appContext.deleteSharedPreferences(FILE_NAME) }
            create()
        }
    }

    private fun create(): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Creates a fresh handle for a key that has not been stored yet. */
    fun newRef(): String = "key-" + UUID.randomUUID().toString()

    fun save(ref: String, apiKey: String) {
        prefs.edit().putString(ref, apiKey.trim()).apply()
    }

    fun read(ref: String): String? {
        val value = prefs.getString(ref, null) ?: return null
        return value.ifBlank { null }
    }

    fun delete(ref: String) {
        prefs.edit().remove(ref).apply()
    }

    private companion object {
        const val TAG = "ApiKeyStore"
        const val FILE_NAME = "agent_api_keys"
    }
}
