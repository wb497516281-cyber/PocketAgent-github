package com.pocket.agent.data.settings

/**
 * A ready-made template for a well-known OpenAI-compatible endpoint.
 *
 * A preset only pre-fills the Base URL, a default model and a few alternatives;
 * the API key is always typed by the user and stored encrypted. Nothing is
 * persisted until the user saves a provider built from it.
 */
data class ProviderPreset(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
    val region: PresetRegion,
    val suggestedModels: List<String> = emptyList(),
    val suggestedHeaders: Map<String, String> = emptyMap(),
    val keyHint: String = "",
    val note: String? = null,
)

/** Where a vendor is hosted; only used to group the picker. */
enum class PresetRegion(val label: String) {
    DOMESTIC("国内服务商"),
    OVERSEAS("海外服务商"),
}

/** Catalogue of mainstream vendors that speak the OpenAI chat protocol. */
object ProviderPresets {

    val all: List<ProviderPreset> = listOf(
        ProviderPreset(
            id = "deepseek",
            displayName = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            defaultModel = "deepseek-chat",
            region = PresetRegion.DOMESTIC,
            suggestedModels = listOf("deepseek-chat", "deepseek-reasoner"),
            keyHint = "sk-...",
        ),
        ProviderPreset(
            id = "qwen",
            displayName = "通义千问 Qwen",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            defaultModel = "qwen-plus",
            region = PresetRegion.DOMESTIC,
            suggestedModels = listOf("qwen-plus", "qwen-turbo", "qwen-max", "qwen-long"),
        ),
        ProviderPreset(
            id = "bailian",
            displayName = "阿里百炼 Bailian",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            defaultModel = "qwen-plus",
            region = PresetRegion.DOMESTIC,
            suggestedModels = listOf(
                "qwen-plus",
                "qwen-max",
                "qwen-turbo",
                "qwen-long",
                "qwen3-max",
                "deepseek-r1",
                "deepseek-v3",
            ),
            keyHint = "sk-...",
            note = "阿里百炼与通义千问共用 https://dashscope.aliyuncs.com/compatible-mode/v1 " +
                "这一个 OpenAI 兼容入口；除 qwen 系列外，也可直接调百炼托管的 deepseek-r1、" +
                "deepseek-v3 等模型，模型名以百炼控制台「模型广场」显示的为准。",
        ),
        ProviderPreset(
            id = "kimi",
            displayName = "Kimi (Moonshot)",
            baseUrl = "https://api.moonshot.cn/v1",
            defaultModel = "moonshot-v1-8k",
            region = PresetRegion.DOMESTIC,
            suggestedModels = listOf(
                "moonshot-v1-8k",
                "moonshot-v1-32k",
                "moonshot-v1-128k",
                "kimi-k2-0905-preview",
            ),
        ),
        ProviderPreset(
            id = "glm",
            displayName = "智谱 GLM",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            defaultModel = "glm-4-flash",
            region = PresetRegion.DOMESTIC,
            suggestedModels = listOf("glm-4-flash", "glm-4-plus", "glm-4-air", "glm-4.5", "glm-4.5-air"),
        ),
        ProviderPreset(
            id = "minimax",
            displayName = "MiniMax",
            baseUrl = "https://api.minimax.chat/v1",
            defaultModel = "MiniMax-Text-01",
            region = PresetRegion.DOMESTIC,
            suggestedModels = listOf("MiniMax-Text-01", "abab6.5s-chat"),
        ),
        ProviderPreset(
            id = "siliconflow",
            displayName = "硅基流动 SiliconFlow",
            baseUrl = "https://api.siliconflow.cn/v1",
            defaultModel = "Qwen/Qwen2.5-7B-Instruct",
            region = PresetRegion.DOMESTIC,
            suggestedModels = listOf(
                "Qwen/Qwen2.5-7B-Instruct",
                "deepseek-ai/DeepSeek-V3",
                "THUDM/glm-4-9b-chat",
            ),
        ),
        ProviderPreset(
            id = "yi",
            displayName = "零一万物 01.AI",
            baseUrl = "https://api.lingyiwanwu.com/v1",
            defaultModel = "yi-lightning",
            region = PresetRegion.DOMESTIC,
            suggestedModels = listOf("yi-lightning", "yi-large", "yi-medium"),
        ),
        ProviderPreset(
            id = "openai",
            displayName = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-4o-mini",
            region = PresetRegion.OVERSEAS,
            suggestedModels = listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "o4-mini"),
            keyHint = "sk-...",
        ),
        ProviderPreset(
            id = "openrouter",
            displayName = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            defaultModel = "openai/gpt-4o-mini",
            region = PresetRegion.OVERSEAS,
            suggestedModels = listOf(
                "openai/gpt-4o-mini",
                "anthropic/claude-3.5-sonnet",
                "google/gemini-2.0-flash-001",
            ),
            note = "OpenRouter 可留空附加头；如需统计可加 HTTP-Referer。免费模型有速率限制。",
        ),
        ProviderPreset(
            id = "gemini",
            displayName = "Google Gemini",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            defaultModel = "gemini-2.0-flash",
            region = PresetRegion.OVERSEAS,
            suggestedModels = listOf("gemini-2.0-flash", "gemini-1.5-pro", "gemini-1.5-flash"),
        ),
        ProviderPreset(
            id = "grok",
            displayName = "xAI Grok",
            baseUrl = "https://api.x.ai/v1",
            defaultModel = "grok-3-mini",
            region = PresetRegion.OVERSEAS,
            suggestedModels = listOf("grok-3-mini", "grok-2-latest", "grok-3"),
        ),
        ProviderPreset(
            id = "groq",
            displayName = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            defaultModel = "llama-3.3-70b-versatile",
            region = PresetRegion.OVERSEAS,
            suggestedModels = listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant"),
        ),
        ProviderPreset(
            id = "mistral",
            displayName = "Mistral",
            baseUrl = "https://api.mistral.ai/v1",
            defaultModel = "mistral-small-latest",
            region = PresetRegion.OVERSEAS,
            suggestedModels = listOf("mistral-small-latest", "mistral-large-latest"),
        ),
        ProviderPreset(
            id = "together",
            displayName = "Together AI",
            baseUrl = "https://api.together.xyz/v1",
            defaultModel = "meta-llama/Llama-3.3-70B-Instruct-Turbo",
            region = PresetRegion.OVERSEAS,
            suggestedModels = listOf(
                "meta-llama/Llama-3.3-70B-Instruct-Turbo",
                "Qwen/Qwen2.5-72B-Instruct-Turbo",
            ),
        ),
    )
}
