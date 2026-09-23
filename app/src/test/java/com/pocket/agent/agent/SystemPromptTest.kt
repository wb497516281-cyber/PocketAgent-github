package com.pocket.agent.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptTest {

    @Test
    fun `tells the model to search when fresh facts are needed`() {
        val prompt = buildSystemPrompt("- web_search: 联网搜索")
        assertTrue(prompt.contains("web_search"))
        assertTrue(prompt.contains("最新信息"))
    }

    @Test
    fun `still carries the file tooling and confirmation guidance`() {
        val prompt = buildSystemPrompt("- list_files: 列目录")
        assertTrue(prompt.contains("list_files"))
        assertTrue(prompt.contains("确认"))
    }
}
