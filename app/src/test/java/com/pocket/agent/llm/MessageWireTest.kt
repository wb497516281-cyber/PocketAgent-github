package com.pocket.agent.llm

import com.pocket.agent.data.chat.ChatMessage
import com.pocket.agent.data.chat.ChatRole
import com.pocket.agent.data.chat.ImageAttachment
import com.pocket.agent.data.chat.ToolCall
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire shape is what a gateway sees, so it is asserted directly rather
 * than through an HTTP call: a text-only message stays a plain string, and a
 * multimodal one becomes a content array with a text part first.
 */
class MessageWireTest {

    @Test
    fun `a bare message keeps the plain string content`() {
        val json = buildMessageJson(ChatMessage(ChatRole.USER, "你好"))
        assertEquals("user", json["role"]!!.jsonPrimitive.content)
        assertEquals("你好", json["content"]!!.jsonPrimitive.content)
        assertNull(json["tool_call_id"])
        assertNull(json["tool_calls"])
    }

    @Test
    fun `a multimodal message becomes a text part followed by image url parts`() {
        val json = buildMessageJson(
            ChatMessage(
                role = ChatRole.USER,
                content = "这是什么",
                images = listOf(
                    ImageAttachment(uri = "content://picks/1", base64 = "AAA"),
                    ImageAttachment(
                        uri = "content://picks/2",
                        base64 = "BBB",
                        mimeType = "image/png",
                    ),
                ),
            ),
        )
        assertEquals("user", json["role"]!!.jsonPrimitive.content)
        val content = json["content"]!!.jsonArray
        assertEquals(3, content.size)

        val text = content[0].jsonObject
        assertEquals("text", text["type"]!!.jsonPrimitive.content)
        assertEquals("这是什么", text["text"]!!.jsonPrimitive.content)

        val first = content[1].jsonObject
        assertEquals("image_url", first["type"]!!.jsonPrimitive.content)
        assertEquals(
            "data:image/jpeg;base64,AAA",
            first["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
        val second = content[2].jsonObject
        assertEquals(
            "data:image/png;base64,BBB",
            second["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `empty attachments never produce a content array`() {
        val json = buildMessageJson(ChatMessage(ChatRole.ASSISTANT, "", images = emptyList()))
        assertTrue(json["content"]!!.jsonPrimitive.content.isEmpty())
    }

    @Test
    fun `tool results link back to their invocation`() {
        val json = buildMessageJson(
            ChatMessage(ChatRole.TOOL, "目录里有两个文件", toolCallId = "call_1"),
        )
        assertEquals("tool", json["role"]!!.jsonPrimitive.content)
        assertEquals("call_1", json["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("目录里有两个文件", json["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `assistant tool calls are passed through in the function shape`() {
        val json = buildMessageJson(
            ChatMessage(
                role = ChatRole.ASSISTANT,
                content = "",
                toolCalls = listOf(ToolCall(id = "call_1", name = "list_files", arguments = "{}")),
            ),
        )
        val calls = json["tool_calls"]!!.jsonArray
        assertEquals(1, calls.size)
        val call = calls[0].jsonObject
        assertEquals("call_1", call["id"]!!.jsonPrimitive.content)
        assertEquals("function", call["type"]!!.jsonPrimitive.content)
        val function = call["function"]!!.jsonObject
        assertEquals("list_files", function["name"]!!.jsonPrimitive.content)
        assertEquals("{}", function["arguments"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an empty tool call list is omitted`() {
        val json = buildMessageJson(
            ChatMessage(ChatRole.ASSISTANT, "好的", toolCalls = emptyList()),
        )
        assertNull(json["tool_calls"])
    }
}
