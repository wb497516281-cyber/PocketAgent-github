package com.pocket.agent.agent

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchToolTest {

    @Test
    fun `parses title url and summary`() {
        val body = tavilyBody(
            """{"title":"AI 新闻","url":"https://example.com/1","content":"摘要一"}""",
            """{"title":"第二条","url":"https://example.com/2","content":"摘要二"}""",
        )
        val hits = parseTavilyResults(body)
        assertEquals(2, hits!!.size)
        assertEquals("AI 新闻", hits[0].title)
        assertEquals("https://example.com/1", hits[0].url)
        assertEquals("摘要一", hits[0].content)
    }

    @Test
    fun `keeps only the top three results`() {
        val entries = (1..6).map { index ->
            """{"title":"第 $index 条","url":"https://example.com/$index","content":"摘要 $index"}"""
        }
        val hits = parseTavilyResults(tavilyBody(*entries.toTypedArray()))
        assertEquals(3, hits!!.size)
        assertEquals("第 1 条", hits.first().title)
        assertEquals("第 3 条", hits.last().title)
    }

    @Test
    fun `truncates long summaries to five hundred characters`() {
        val long = "x".repeat(1200)
        val body = tavilyBody(
            """{"title":"长文","url":"https://example.com/long","content":"$long"}""",
        )
        val hits = parseTavilyResults(body)
        assertEquals(500 + 3, hits!!.single().content.length)
        assertTrue(hits.single().content.endsWith("..."))
    }

    @Test
    fun `garbage body parses as null so the caller fails cleanly`() {
        assertNull(parseTavilyResults("<html>oops</html>"))
        assertNull(parseTavilyResults(""))
        assertNull(parseTavilyResults("""{"answer":"no results key"}"""))
    }

    @Test
    fun `renders json the model can read`() {
        val text = renderSearchHits(
            "今天新闻",
            listOf(SearchHit("标题", "https://example.com", "摘要")),
        )
        val root = Json.parseToJsonElement(text).jsonObject
        assertEquals("今天新闻", root["query"]!!.jsonPrimitive.content)
        val first = root["results"]!!.jsonArray.first().jsonObject
        assertEquals("标题", first["title"]!!.jsonPrimitive.content)
        assertEquals("https://example.com", first["url"]!!.jsonPrimitive.content)
        assertEquals("摘要", first["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `auth failures hint at an invalid key`() {
        assertTrue(httpFailureText(401).contains("Tavily API Key 可能无效"))
        assertTrue(httpFailureText(403).contains("Tavily API Key 可能无效"))
        assertTrue(httpFailureText(500).contains("HTTP 500"))
        assertTrue(!httpFailureText(500).contains("Tavily API Key"))
    }

    @Test
    fun `transport failures become readable text`() {
        assertTrue(describeSearchErrorText(SocketTimeoutException()).contains("超时"))
        assertTrue(describeSearchErrorText(IOException("boom")).contains("boom"))
    }

    @Test
    fun `spec advertises web_search with a required query parameter`() {
        val tool = WebSearchTool(OkHttpClient(), apiKeyProvider = { null })
        assertEquals("web_search", tool.spec.name)
        val properties = tool.spec.parameters["properties"]!!.jsonObject
        assertTrue(properties.containsKey("query"))
        val required = tool.spec.parameters["required"]!!.jsonArray
        assertEquals("query", required.first().jsonPrimitive.content)
    }

    private fun tavilyBody(vararg results: String): String =
        """{"results":[${results.joinToString(",")}]}"""
}
