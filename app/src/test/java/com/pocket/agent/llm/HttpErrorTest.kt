package com.pocket.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpErrorTest {

    @Test
    fun `activation error keeps the vendor message and points to the model catalogue`() {
        val body = """
            {"error":{"message":"The product is not activated, please confirm that you have activated models and contain and try again after activation.","type":"invalid_request_error","code":"invalid_model"}}
        """.trimIndent()
        val message = describeHttpError(400, body)
        assertTrue(message, message.startsWith("HTTP 400: The product is not activated"))
        assertTrue(message, message.contains("获取模型"))
        assertTrue(message, message.contains("模型不存在或账号未开通"))
    }

    @Test
    fun `model not found by name hits the same hint`() {
        val message = describeHttpError(400, """{"error":{"message":"The model `vanchin/deepseek-v4.1-flash` does not exist"}}""")
        assertTrue(message, message.contains("获取模型"))
        assertEquals(
            "HTTP 400: The model `vanchin/deepseek-v4.1-flash` does not exist\n" +
                "提示：模型不存在或账号未开通，可在编辑弹窗点击「获取模型」拉取当前账号可用的模型列表",
            message,
        )
    }

    @Test
    fun `400 without a model message suggests checking base url and model name`() {
        val message = describeHttpError(400, """{"error":{"message":"messages is empty"}}""")
        assertEquals(
            "HTTP 400: messages is empty\n提示：服务端拒绝了请求，请检查模型名与 Base URL 是否正确",
            message,
        )
    }

    @Test
    fun `bad credentials hint at the api key`() {
        assertEquals(
            "HTTP 401: Invalid API key\n提示：API Key 无效或没有访问权限，请在设置中检查 API Key",
            describeHttpError(401, """{"error":{"message":"Invalid API key"}}"""),
        )
        assertEquals(
            "HTTP 403: Forbidden\n提示：API Key 无效或没有访问权限，请在设置中检查 API Key",
            describeHttpError(403, """{"error":{"message":"Forbidden"}}"""),
        )
    }

    @Test
    fun `404 suggests checking base url and fetching the catalogue`() {
        val message = describeHttpError(404, """{"error":{"message":"page not found"}}""")
        assertEquals(
            "HTTP 404: page not found\n提示：接口地址或模型不存在，请检查 Base URL 与模型名，可点「获取模型」查看可用模型",
            message,
        )
    }

    @Test
    fun `rate limit and server failures ask for a retry`() {
        assertEquals(
            "HTTP 429: Rate limit reached\n提示：请求过于频繁或额度不足，请稍后重试并检查账号额度",
            describeHttpError(429, """{"error":{"message":"Rate limit reached"}}"""),
        )
        assertEquals(
            "HTTP 503: upstream connect error\n提示：服务端异常，可稍后重试；若持续失败请检查 Base URL 指向的服务",
            describeHttpError(503, "upstream connect error"),
        )
    }

    @Test
    fun `unmatched status passes the vendor message through untouched`() {
        val message = describeHttpError(418, """{"error":{"message":"I am a teapot"}}""")
        assertEquals("HTTP 418: I am a teapot", message)
        assertFalse(message, message.contains("提示"))
    }

    @Test
    fun `unparsable body falls back to the raw snippet`() {
        val message = describeHttpError(502, "<html>Bad Gateway</html>")
        assertEquals("HTTP 502: <html>Bad Gateway</html>\n提示：服务端异常，可稍后重试；若持续失败请检查 Base URL 指向的服务", message)
    }

    @Test
    fun `blank body degrades to a generic headline`() {
        assertEquals(
            "HTTP 500: 请求失败\n提示：服务端异常，可稍后重试；若持续失败请检查 Base URL 指向的服务",
            describeHttpError(500, ""),
        )
    }
}
