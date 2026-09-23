package com.pocket.agent.llm

import android.util.Log
import com.pocket.agent.data.chat.ChatMessage
import com.pocket.agent.data.chat.ChatRole
import com.pocket.agent.data.settings.ProviderConfig
import com.pocket.agent.data.settings.ProviderType
import java.io.BufferedReader
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.channels.SendChannel

/**
 * Chat provider for the OpenAI `/chat/completions` shape.
 *
 * The same wire format is spoken by OpenAI, DeepSeek, Qwen (DashScope
 * compatible mode), Kimi (Moonshot), OpenRouter, One API, LiteLLM and most
 * self-hosted gateways, so one implementation covers all of them. Requests are
 * issued with `stream=true` and parsed from the SSE body.
 */
class OpenAiCompatibleProvider(
    private val config: ProviderConfig,
    private val apiKeyProvider: suspend () -> String?,
    private val client: OkHttpClient,
) : LlmProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override fun chat(request: ChatRequest): Flow<ChatEvent> = callbackFlow {
        // Captured so the SSE helpers can emit without being suspend.
        val events = channel
        val endpoint = resolveEndpoint(config.baseUrl)
        if (endpoint == null) {
            trySend(ChatEvent.Error("Base URL 无效，请检查设置：${config.baseUrl}"))
            close()
            return@callbackFlow
        }

        val apiKey = runCatching { apiKeyProvider() }.getOrNull()
        if (apiKey.isNullOrBlank()) {
            trySend(ChatEvent.Error("未配置 API Key，请先在设置中填写"))
            close()
            return@callbackFlow
        }

        val httpRequest = Request.Builder()
            .url(endpoint)
            .post(buildRequestBody(request).toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "text/event-stream")
            .header("Authorization", "Bearer $apiKey")
            .apply {
                // Explicit headers win over the derived Authorization header.
                config.extraHeaders.forEach { (name, value) ->
                    if (name.isNotBlank() && value.isNotBlank()) header(name, value)
                }
            }
            .build()

        val call = client.newCall(httpRequest)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) {
                    close()
                    return
                }
                trySend(ChatEvent.Error(describeTransportError(e), e))
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        trySend(ChatEvent.Error(readHttpError(response)))
                        close()
                        return@use
                    }
                    val contentType = response.header("Content-Type").orEmpty()
                    val body = response.body
                    if (contentType.contains("json")) {
                        // Either we asked for `stream=false`, or a gateway that
                        // ignores `stream` answered in one shot.
                        readNonStreaming(body.string(), events)
                        close()
                        return@use
                    }
                    try {
                        readStream(body.charStream().buffered(), events)
                    } catch (e: IOException) {
                        if (!call.isCanceled()) {
                            Log.w(TAG, "Stream aborted", e)
                            trySend(ChatEvent.Error(describeTransportError(e), e))
                        }
                    }
                    close()
                }
            }
        })

        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    /**
     * Reads the endpoint's `/models` catalogue.
     *
     * The request is blocking, so it happens on [Dispatchers.IO], and every
     * failure - transport, auth, a route that does not exist - becomes a
     * [ModelFetchResult] rather than an exception.
     */
    override suspend fun fetchModels(): ModelFetchResult = withContext(Dispatchers.IO) {
        val endpoint = resolveModelsEndpoint(config.baseUrl)
            ?: return@withContext ModelFetchResult.Failure("Base URL 无效，请检查设置：${config.baseUrl}")

        val apiKey = runCatching { apiKeyProvider() }.getOrNull()
        if (apiKey.isNullOrBlank()) {
            return@withContext ModelFetchResult.Failure("未配置 API Key，无法获取模型列表")
        }

        val httpRequest = Request.Builder()
            .url(endpoint)
            .get()
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .apply {
                config.extraHeaders.forEach { (name, value) ->
                    if (name.isNotBlank() && value.isNotBlank()) header(name, value)
                }
            }
            .build()

        runCatching {
            client.newCall(httpRequest)
                // The shared client carries no call timeout so streaming turns
                // can run for minutes; this call must not.
                .apply { timeout().timeout(MODELS_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                .execute()
                .use { response -> toModelFetchResult(response) }
        }.getOrElse { error ->
            Log.w(TAG, "Model catalogue fetch failed", error)
            ModelFetchResult.Failure(describeAnyError(error))
        }
    }

    private fun toModelFetchResult(response: Response): ModelFetchResult {
        if (!response.isSuccessful) {
            // A gateway that simply has no catalogue is a normal outcome: the
            // user types the model name instead.
            if (response.code in UNSUPPORTED_STATUS) return ModelFetchResult.Unsupported
            return ModelFetchResult.Failure(readHttpError(response))
        }
        val reported = parseModelIds(response.body.string())
        if (reported.isEmpty()) {
            return ModelFetchResult.Failure("接口没有返回模型列表，可能不是 OpenAI 兼容的 /models")
        }
        val models = filterChatModelIds(reported)
        if (models.isEmpty()) {
            return ModelFetchResult.Failure("没有可用的对话模型，已过滤 embedding、vision、audio 等非文本模型")
        }
        return ModelFetchResult.Success(models)
    }

    /**
     * Parses SSE frames and forwards them as [ChatEvent]s on the channel.
     *
     * Deliberately non-suspend: it runs on the OkHttp callback thread and
     * drains the body with blocking reads.
     */
    private fun readStream(reader: BufferedReader, events: SendChannel<ChatEvent>) {
        val pending = LinkedHashMap<Int, MutableToolCallAccumulator>()
        while (true) {
            val line = reader.readLine() ?: break
            if (SseParser.isIgnorable(line)) continue
            val payload = SseParser.payload(line) ?: continue
            if (payload == SseParser.DONE) break

            val chunk = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            if (chunk == null) {
                Log.w(TAG, "Skipping unparsable SSE chunk")
                continue
            }
            chunk["error"]?.let { error ->
                val message = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull
                    ?: "模型返回错误"
                events.trySend(ChatEvent.Error(message))
                return
            }

            val choices = chunk["choices"]?.jsonArray ?: continue
            for (choice in choices) {
                val delta = choice.jsonObject["delta"]?.jsonObject ?: continue
                delta["content"]?.jsonPrimitive?.contentOrNull?.let { text ->
                    if (text.isNotEmpty()) events.trySend(ChatEvent.TextDelta(text))
                }
                val toolCalls = delta["tool_calls"]?.jsonArray ?: continue
                for ((fallbackIndex, rawCall) in toolCalls.withIndex()) {
                    val call = rawCall.jsonObject
                    val index = call["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: fallbackIndex
                    val accumulator = pending.getOrPut(index) { MutableToolCallAccumulator(index) }
                    call["id"]?.jsonPrimitive?.contentOrNull?.let { accumulator.id = it }
                    val function = call["function"]?.jsonObject
                    function?.get("name")?.jsonPrimitive?.contentOrNull?.let { accumulator.name = it }
                    function?.get("arguments")?.jsonPrimitive?.contentOrNull?.let { args ->
                        accumulator.arguments.append(args)
                    }
                }
            }
        }

        val finished = pending.values
            .filter { !it.name.isNullOrBlank() }
            .map { accumulator ->
                ToolCallDelta(
                    index = accumulator.index,
                    id = accumulator.id ?: newSyntheticId(),
                    name = accumulator.name.orEmpty(),
                    argumentsDelta = accumulator.arguments.toString(),
                )
            }
        if (finished.isNotEmpty()) {
            events.trySend(ChatEvent.ToolCallEvent(finished))
        }
        events.trySend(ChatEvent.Finished)
    }

    /**
     * Emits a single JSON completion as the usual event sequence.
     *
     * Non-streaming bodies carry the finished shape (`message` instead of
     * `delta`, `message.tool_calls` instead of streamed fragments), so they are
     * folded here rather than rejected.
     */
    private fun readNonStreaming(body: String, events: SendChannel<ChatEvent>) {
        val chunk = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        if (chunk == null) {
            events.trySend(ChatEvent.Error("响应不是合法 JSON"))
            return
        }
        chunk["error"]?.let { error ->
            val message = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull
                ?: "模型返回错误"
            events.trySend(ChatEvent.Error(message))
            return
        }
        val message = chunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject
        if (message == null) {
            events.trySend(ChatEvent.Error("响应中没有 choices"))
            return
        }
        message["content"]?.jsonPrimitive?.contentOrNull?.let { text ->
            if (text.isNotEmpty()) events.trySend(ChatEvent.TextDelta(text))
        }
        val deltas = message["tool_calls"]?.jsonArray?.mapIndexedNotNull { index, raw ->
            val call = raw.jsonObject
            val function = call["function"]?.jsonObject
            ToolCallDelta(
                index = call["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: index,
                id = call["id"]?.jsonPrimitive?.contentOrNull,
                name = function?.get("name")?.jsonPrimitive?.contentOrNull,
                argumentsDelta = function?.get("arguments")?.jsonPrimitive?.contentOrNull,
            )
        }.orEmpty()
        if (deltas.isNotEmpty()) events.trySend(ChatEvent.ToolCallEvent(deltas))
        events.trySend(ChatEvent.Finished)
    }

    private fun newSyntheticId(): String = "call_" + java.util.UUID.randomUUID().toString().replace("-", "").take(24)

    private class MutableToolCallAccumulator(val index: Int) {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }

    private fun buildRequestBody(request: ChatRequest): String {
        val messages = buildJsonArray {
            request.messages.forEach { message -> add(buildMessageJson(message)) }
        }
        val tools = buildJsonArray {
            request.tools.forEach { spec ->
                add(
                    buildJsonObject {
                        put("type", "function")
                        put(
                            "function",
                            buildJsonObject {
                                put("name", spec.name)
                                put("description", spec.description)
                                put("parameters", spec.parameters)
                            },
                        )
                    },
                )
            }
        }
        return buildJsonObject {
            put("model", request.model)
            put("messages", messages)
            put("stream", request.stream)
            if (tools.isNotEmpty()) put("tools", tools)
            request.maxTokens?.let { put("max_tokens", it) }
        }.toString()
    }

    private fun resolveEndpoint(baseUrl: String): String? {
        val cleaned = baseUrl.trim().removeSuffix("/")
        if (cleaned.isEmpty()) return null
        val parsed = cleaned.toHttpUrlOrNull() ?: return null
        if (parsed.encodedPath.endsWith("/chat/completions")) return cleaned
        return "$cleaned/chat/completions"
    }

    /**
     * Derives `{baseUrl}/models`, tolerating a Base URL that already points at
     * the chat endpoint: gateways are configured either way.
     */
    private fun resolveModelsEndpoint(baseUrl: String): String? {
        val cleaned = baseUrl.trim().removeSuffix("/")
        if (cleaned.isEmpty()) return null
        val parsed = cleaned.toHttpUrlOrNull() ?: return null
        return when {
            parsed.encodedPath.endsWith("/models") -> cleaned
            parsed.encodedPath.endsWith("/chat/completions") ->
                cleaned.removeSuffix("/chat/completions") + "/models"
            else -> "$cleaned/models"
        }
    }

    private fun readHttpError(response: Response): String {
        val body = runCatching { response.body.string().orEmpty() }.getOrDefault("")
        return describeHttpError(response.code, body)
    }

    private fun describeTransportError(e: IOException): String = when (e) {
        is UnknownHostException -> "无法解析主机，请检查网络与 Base URL"
        is SocketTimeoutException -> "连接超时，请稍后重试"
        is SSLException -> "TLS 握手失败：${e.message ?: "证书或协议不受信任"}"
        else -> e.message ?: "网络错误"
    }

    /** Same shape as [describeTransportError] for calls that may throw anything. */
    private fun describeAnyError(error: Throwable): String = when (error) {
        is IOException -> describeTransportError(error)
        else -> error.message ?: "获取模型列表失败"
    }

    private companion object {
        const val TAG = "OpenAiProvider"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val MODELS_TIMEOUT_SECONDS = 15L

        /** Statuses that mean "this endpoint has no model catalogue", not "broken". */
        val UNSUPPORTED_STATUS = setOf(404, 405, 501)
    }
}

/**
 * Turns one [ChatMessage] into the JSON object the endpoint expects.
 *
 * A message carrying attachments becomes a multipart `content` array, which is
 * how OpenAI and every compatible gateway want vision input: the text part
 * first, then one `image_url` part per attachment holding a Base64 data URL.
 * Anthropic and Gemini express the same idea in a different envelope; this is
 * the seam where those two would plug in, so nobody else has to learn about
 * it. Kept out of the class so the JVM tests can assert on the wire shape.
 */
internal fun buildMessageJson(message: ChatMessage): JsonObject = buildJsonObject {
    put("role", message.role.wireName)
    put("content", buildContentJson(message))
    message.toolCallId?.let { put("tool_call_id", it) }
    message.toolCalls?.let { calls ->
        if (calls.isNotEmpty()) {
            put(
                "tool_calls",
                buildJsonArray {
                    calls.forEach { call ->
                        add(
                            buildJsonObject {
                                put("id", call.id)
                                put("type", "function")
                                put(
                                    "function",
                                    buildJsonObject {
                                        put("name", call.name)
                                        put("arguments", call.arguments)
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
    }
}

/** Plain text for a bare message, a text-plus-images array for a multimodal one. */
private fun buildContentJson(message: ChatMessage): JsonElement {
    if (message.images.isEmpty()) return JsonPrimitive(message.content)
    return buildJsonArray {
        add(
            buildJsonObject {
                put("type", "text")
                put("text", message.content)
            },
        )
        message.images.forEach { image ->
            add(
                buildJsonObject {
                    put("type", "image_url")
                    put(
                        "image_url",
                        buildJsonObject {
                            put("url", "data:${image.mimeType};base64,${image.base64}")
                        },
                    )
                },
            )
        }
    }
}

/**
 * Turns a non-2xx response into something the user can act on.
 *
 * The vendor's own message stays first so nothing about the original cause
 * is lost, then a hint is appended when the status points at something the
 * user can fix: an unknown or unactivated model (the usual reason a gateway
 * answers 400), a bad key, a wrong Base URL, throttling, or an outage. Pure
 * function so the JVM tests can cover every branch without a server.
 */
internal fun describeHttpError(code: Int, body: String): String {
    val vendorMessage = runCatching {
        Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
            ?.get("message")?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.takeIf { it.isNotBlank() }
    val detail = vendorMessage ?: body.take(300).takeIf { it.isNotBlank() }
    val headline = if (detail != null) "HTTP $code: $detail" else "HTTP $code: 请求失败"
    val hint = httpErrorHint(code, vendorMessage.orEmpty()) ?: return headline
    return "$headline\n$hint"
}

private fun httpErrorHint(code: Int, vendorMessage: String): String? = when (code) {
    400, 422 -> if (looksModelRelated(vendorMessage)) {
        "提示：模型不存在或账号未开通，可在编辑弹窗点击「获取模型」拉取当前账号可用的模型列表"
    } else {
        "提示：服务端拒绝了请求，请检查模型名与 Base URL 是否正确"
    }
    401, 403 -> "提示：API Key 无效或没有访问权限，请在设置中检查 API Key"
    404 -> "提示：接口地址或模型不存在，请检查 Base URL 与模型名，可点「获取模型」查看可用模型"
    429 -> "提示：请求过于频繁或额度不足，请稍后重试并检查账号额度"
    in 500..599 -> "提示：服务端异常，可稍后重试；若持续失败请检查 Base URL 指向的服务"
    else -> null
}

/**
 * Gateways phrase "this account cannot use that model" in many ways across
 * languages; matching on loose substrings survives translation and casing.
 */
private val MODEL_ERROR_KEYWORDS = listOf(
    "model",
    "模型",
    "activation",
    "activated",
    "not exist",
    "does not exist",
    "not found",
    "no such",
    "unsupported",
)

private fun looksModelRelated(vendorMessage: String): Boolean {
    val lower = vendorMessage.lowercase()
    return MODEL_ERROR_KEYWORDS.any { lower.contains(it) }
}
