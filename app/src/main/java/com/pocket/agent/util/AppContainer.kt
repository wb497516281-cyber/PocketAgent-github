package com.pocket.agent.util

import android.content.Context
import com.pocket.agent.agent.AgentEngine
import com.pocket.agent.agent.CreateFileTool
import com.pocket.agent.agent.DeleteFileTool
import com.pocket.agent.agent.GetCurrentDirectoryTool
import com.pocket.agent.agent.ImagePreprocessor
import com.pocket.agent.agent.ListFilesTool
import com.pocket.agent.agent.ReadFileTool
import com.pocket.agent.agent.MlKitTextExtractor
import com.pocket.agent.agent.ToolRegistry
import com.pocket.agent.agent.WriteFileTool
import com.pocket.agent.agent.WebSearchTool
import com.pocket.agent.agent.tools.CreatePdfTool
import com.pocket.agent.agent.tools.CreatePptxTool
import com.pocket.agent.agent.tools.CreateWordTool
import com.pocket.agent.data.chat.ChatSessionStore
import com.pocket.agent.data.file.SafFileRepository
import com.pocket.agent.data.image.ImageCompressor
import com.pocket.agent.data.settings.ApiKeyStore
import com.pocket.agent.data.settings.SettingsRepository
import com.pocket.agent.llm.LlmProviderFactory
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

/**
 * Manual dependency injection.
 *
 * The graph is tiny and entirely singleton, so a hand written container is
 * clearer than wiring an annotation processor. Everything is lazy so cold start
 * only pays for what the first screen actually needs.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // Streaming turns have no natural end, so no total call timeout.
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    // Keys are redacted; headers are only logged in debug builds.
                    redactHeader("Authorization")
                    redactHeader("api-key")
                    level = if (com.pocket.agent.BuildConfig.DEBUG) {
                        HttpLoggingInterceptor.Level.BASIC
                    } else {
                        HttpLoggingInterceptor.Level.NONE
                    }
                },
            )
            .build()
    }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }

    val apiKeyStore: ApiKeyStore by lazy { ApiKeyStore(appContext) }

    val chatSessionStore: ChatSessionStore by lazy { ChatSessionStore(appContext) }

    // SAF grants are held by the process, so the repository only needs a context.
    val fileRepository: SafFileRepository by lazy { SafFileRepository(appContext) }

    /** Shrinks a picked photo before it is attached to a message. */
    val imageCompressor: ImageCompressor by lazy { ImageCompressor(appContext.contentResolver) }

    /** Local OCR, used only when the selected model cannot see. */
    val imagePreprocessor: ImagePreprocessor by lazy {
        ImagePreprocessor(MlKitTextExtractor(appContext))
    }

    val providerFactory: LlmProviderFactory by lazy {
        LlmProviderFactory(apiKeyStore, okHttpClient)
    }

    val toolRegistry: ToolRegistry by lazy {
        ToolRegistry(
            listOf(
                GetCurrentDirectoryTool(),
                ListFilesTool(),
                CreateFileTool(),
                ReadFileTool(),
                WriteFileTool(),
                DeleteFileTool(),
                WebSearchTool(okHttpClient) {
                    // DataStore holds only an opaque reference; the secret
                    // itself is resolved through the encrypted store.
                    settingsRepository.tavilyApiKeyRef()?.let { ref -> apiKeyStore.read(ref) }
                },
                CreatePdfTool(),
                CreateWordTool(),
                // Deck images are fetched over the same client the providers
                // use; the tool puts its own timeout on each call.
                CreatePptxTool(okHttpClient),
            ),
        )
    }

    val agentEngine: AgentEngine by lazy { AgentEngine(toolRegistry) }
}
