package com.pocket.agent.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderPresetsTest {

    @Test
    fun `ids are unique`() {
        val ids = ProviderPresets.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `every preset has a name, an https base url and a default model`() {
        ProviderPresets.all.forEach { preset ->
            assertTrue(preset.displayName.isNotBlank())
            assertTrue(preset.baseUrl.startsWith("https://"))
            assertTrue(preset.baseUrl.endsWith("/").not())
            assertTrue(preset.defaultModel.isNotBlank())
        }
    }

    @Test
    fun `suggested models include the default model`() {
        ProviderPresets.all.forEach { preset ->
            if (preset.suggestedModels.isNotEmpty()) {
                assertTrue(
                    "${preset.id} is missing its default model",
                    preset.defaultModel in preset.suggestedModels,
                )
            }
        }
    }

    @Test
    fun `regions are known so the picker can group them`() {
        ProviderPresets.all.forEach { preset ->
            assertTrue(
                PresetRegion.entries.contains(preset.region),
            )
        }
    }

    @Test
    fun `covers the mainstream vendors`() {
        val ids = ProviderPresets.all.map { it.id }
        listOf(
            "openai",
            "deepseek",
            "qwen",
            "bailian",
            "kimi",
            "glm",
            "minimax",
            "siliconflow",
            "openrouter",
            "gemini",
            "grok",
            "groq",
            "mistral",
        ).forEach { id ->
            assertTrue("missing preset for $id", id in ids)
        }
    }
}
