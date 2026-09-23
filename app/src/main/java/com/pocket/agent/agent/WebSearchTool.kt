package com.pocket.agent.agent

import android.util.Log
import com.pocket.agent.llm.ToolSpec
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** One search result, already trimmed to what the model can use. */
internal data class SearchHit(
    val title: String,
    val url: String,
    val content: String,
)

private const val SEARCH_URL = "https://api.tavily.com/search"
private const val SEARCH_DEPTH = "basic"

/** Only the top few results are worth the model's context. */
private const val MAX_RESULTS = 3

/** A verbose snippet must not flood the prompt; 500 characters is plenty. */
private const val MAX_CONTENT_CHARS = 500

/**
 * Searches the web through Tavily.
 *
 * The tool is the app's only source of fresh information: the model calls it
 * when a question needs data it does not have. Every failure - no key, an
 * invalid key, a timeout, an unparsable body - is reported as
 * [ToolResult.failure] text so the agent loop keeps running and the model can
 * explain the problem instead of the app crashing.
 */
class WebSearchTool(
    private val client: OkHttpClient,
    private val apiKeyProvider: suspend () -> String?,
) : AgentTool {

    override val spec = ToolSpec(
        name = "web_search",
        description = "联网搜索，获取最新信息。当你需要最新消息、实时数据，或对事实不确定时，" +
            "先调用本工具再回答。",
        parameters = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(
                        "query",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "搜索关键词，尽量具体")
                        },
                    )
                },
            )
            put("required", buildJsonArray { add("query") })
        },
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val query = arguments.requiredString("query").getOrElse { e ->
            return ToolResult.failure(e.message ?: "参数 query 无效")
        }
        val apiKey = runCatching { apiKeyProvider() }.getOrNull()
        if (apiKey.isNullOrBlank()) {
            return ToolResult.failure(MISSING_KEY_MESSAGE)
        }
        return withContext(Dispatchers.IO) {
            runCatching { search(query, apiKey) }.getOrElse { error ->
                Log.w(TAG, "Web search failed", error)
                ToolResult.failure(describeSearchErrorText(error))
            }
        }
    }

    private fun search(query: String, apiKey: String): ToolResult {
        val request = Request.Builder()
            .url(SEARCH_URL)
            .post(
                buildJsonObject {
                    put("api_key", apiKey)
                    put("query", query)
                    put("max_results", MAX_RESULTS)
                    put("search_depth", SEARCH_DEPTH)
                }.toString().toRequestBody(JSON_MEDIA_TYPE),
            )
            .header("Accept", "application/json")
            // Tavily reads the key from either place; sending both keeps
            // gateways that strip the body happy.
            .header("Authorization", "Bearer $apiKey")
            .build()

        // The shared client carries no call timeout so streaming turns can run
        // for minutes; a search must not.
        return client.newCall(request)
            .apply { timeout().timeout(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            .execute()
            .use { response ->
                if (!response.isSuccessful) {
                    return@use ToolResult.failure(httpFailureText(response.code))
                }
                val hits = parseTavilyResults(response.body.string())
                if (hits == null) {
                    return@use ToolResult.failure("搜索失败：接口返回了无法解析的内容")
                }
                ToolResult.success(renderSearchHits(query, hits))
            }
    }

    private companion object {
        const val TAG = "WebSearchTool"
        const val SEARCH_TIMEOUT_SECONDS = 20L

        val MISSING_KEY_MESSAGE =
            "未配置 Tavily API Key，无法联网搜索。请在「设置-联网搜索配置」中填写 Key 后重试。"

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/** Failure text for a non-2xx search response; 401/403 means a bad key. */
internal fun httpFailureText(code: Int): String {
    val hint = if (code == 401 || code == 403) "，Tavily API Key 可能无效" else ""
    return "搜索失败：HTTP $code$hint"
}

/** Turns transport trouble into text the model can relay; never throws. */
internal fun describeSearchErrorText(error: Throwable): String = when (error) {
    is UnknownHostException -> "搜索失败：无法解析主机，请检查网络"
    is SocketTimeoutException -> "搜索失败：连接超时，请稍后重试"
    is SSLException -> "搜索失败：TLS 握手失败"
    is IOException -> "搜索失败：${error.message ?: "网络错误"}"
    else -> "搜索失败：${error.message ?: "未知错误"}"
}

private val SEARCH_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * Reads the top [MAX_RESULTS] hits out of a Tavily response.
 *
 * Returns null when the body is not JSON with a `results` array - a shape the
 * caller reports as a failure rather than an empty search. Each summary is
 * truncated to [MAX_CONTENT_CHARS] characters so a verbose page cannot flood
 * the model's context.
 */
internal fun parseTavilyResults(body: String, maxContentChars: Int = MAX_CONTENT_CHARS): List<SearchHit>? {
    if (body.isBlank()) return null
    val root = runCatching {
        SEARCH_JSON.parseToJsonElement(body)
    }.getOrNull() ?: return null
    val results = (root as? JsonObject)?.get("results") as? JsonArray ?: return null
    return results.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val url = obj["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: url
        val content = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (url.isEmpty() && title.isEmpty()) {
            return@mapNotNull null
        }
        SearchHit(
            title = title,
            url = url,
            content = truncate(content, maxContentChars),
        )
    }.take(MAX_RESULTS)
}

/** Renders hits as the JSON text handed back to the model. */
internal fun renderSearchHits(query: String, hits: List<SearchHit>): String = buildJsonObject {
    put("query", query)
    put(
        "results",
        buildJsonArray {
            hits.forEach { hit ->
                add(
                    buildJsonObject {
                        put("title", hit.title)
                        put("url", hit.url)
                        put("content", hit.content)
                    },
                )
            }
        },
    )
    if (hits.isEmpty()) put("message", "没有找到相关结果")
}.toString()

/** Truncates to [max] characters, marking the cut with an ASCII ellipsis. */
internal fun truncate(text: String, max: Int): String =
    if (text.length <= max) text else text.take(max) + "..."
