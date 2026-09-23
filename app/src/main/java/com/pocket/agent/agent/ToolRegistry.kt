package com.pocket.agent.agent

import com.pocket.agent.llm.ToolSpec

/**
 * The set of tools advertised to the model.
 *
 * Registration is explicit rather than reflective: the app is small enough that
 * a plain list is clearer than any discovery mechanism.
 */
class ToolRegistry(private val tools: List<AgentTool>) {

    private val toolsByName: Map<String, AgentTool> = tools.associateBy { it.spec.name }

    val specs: List<ToolSpec> = tools.map { it.spec }

    val toolNames: List<String> = tools.map { it.spec.name }

    fun get(name: String): AgentTool? = toolsByName[name]

    /** Renders the tool catalogue for the system prompt. */
    fun describeForPrompt(): String = tools.joinToString("\n\n") { tool ->
        buildString {
            append("- ").append(tool.spec.name).append(": ").append(tool.spec.description)
        }
    }
}
