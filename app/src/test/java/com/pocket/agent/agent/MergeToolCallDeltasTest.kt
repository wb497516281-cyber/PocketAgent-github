package com.pocket.agent.agent

import com.pocket.agent.llm.ToolCallDelta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeToolCallDeltasTest {

    @Test
    fun `folds fragments that only carry the id once`() {
        val deltas = listOf(
            ToolCallDelta(index = 0, id = "call_1", name = "create_file"),
            ToolCallDelta(index = 0, argumentsDelta = "{\"file_"),
            ToolCallDelta(index = 0, argumentsDelta = "name\":\"note.md\"}"),
        )

        val calls = mergeToolCallDeltas(deltas)

        assertEquals(1, calls.size)
        assertEquals("call_1", calls[0].id)
        assertEquals("create_file", calls[0].name)
        assertEquals("{\"file_name\":\"note.md\"}", calls[0].arguments)
    }

    @Test
    fun `keeps parallel calls apart by index`() {
        val deltas = listOf(
            ToolCallDelta(index = 0, id = "call_a", name = "list_files"),
            ToolCallDelta(index = 1, id = "call_b", name = "read_file"),
            ToolCallDelta(index = 1, argumentsDelta = "{\"file_name\":\"a.md\"}"),
        )

        val calls = mergeToolCallDeltas(deltas)

        assertEquals(listOf("list_files", "read_file"), calls.map { it.name })
        assertEquals(listOf("call_a", "call_b"), calls.map { it.id })
        assertEquals("{\"file_name\":\"a.md\"}", calls[1].arguments)
    }

    @Test
    fun `groups by index when the id only arrives on a later fragment`() {
        val deltas = listOf(
            ToolCallDelta(index = 0, name = "create_file"),
            ToolCallDelta(index = 0, id = "call_late", argumentsDelta = "{}"),
        )

        val calls = mergeToolCallDeltas(deltas)

        assertEquals(1, calls.size)
        assertEquals("call_late", calls[0].id)
        assertEquals("{}", calls[0].arguments)
    }

    @Test
    fun `drops fragments that never named a tool`() {
        val deltas = listOf(
            ToolCallDelta(index = 0, id = "call_x"),
            ToolCallDelta(index = 0, argumentsDelta = "{\"a\":1}"),
        )

        assertTrue(mergeToolCallDeltas(deltas).isEmpty())
    }

    @Test
    fun `defaults missing arguments to an empty object`() {
        val calls = mergeToolCallDeltas(listOf(ToolCallDelta(index = 0, id = "call_1", name = "list_files")))

        assertEquals("{}", calls.single().arguments)
    }

    @Test
    fun `synthesises a stable id when the provider sends none`() {
        val first = mergeToolCallDeltas(listOf(ToolCallDelta(index = 0, name = "list_files"))).single()
        val second = mergeToolCallDeltas(listOf(ToolCallDelta(index = 0, name = "list_files"))).single()

        assertEquals(first.id, second.id)
        assertTrue(first.id.startsWith("call_"))
    }
}
