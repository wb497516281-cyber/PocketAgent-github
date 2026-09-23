package com.pocket.agent.agent

import com.pocket.agent.data.chat.ChatMessage
import com.pocket.agent.data.chat.ChatRole
import com.pocket.agent.data.chat.ToolCall
import com.pocket.agent.llm.ChatEvent
import com.pocket.agent.llm.ChatRequest
import com.pocket.agent.llm.LlmProvider
import com.pocket.agent.llm.ToolCallDelta
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** What the chat screen renders for one agent turn. */
sealed interface AgentEvent {

    data class TextDelta(val text: String) : AgentEvent

    data class ToolCallStarted(val call: ToolCall) : AgentEvent

    data class ToolCallFinished(
        val call: ToolCall,
        val result: String,
        val isError: Boolean,
        /** SAF uri of a file the tool produced, when the outcome has one. */
        val fileUri: String? = null,
    ) : AgentEvent

    /** The model answered without asking for another tool call. */
    data object Finished : AgentEvent

    data class Error(val message: String) : AgentEvent
}

/**
 * Drives the tool-calling loop.
 *
 * Each round sends the system prompt, the conversation history and the tool
 * catalogue to the model. Text streams straight through; tool calls are
 * executed here, their result appended as a `tool` role message, and the model
 * is called again. The loop stops when the model answers without tool calls or
 * after [maxSteps] rounds, whichever comes first.
 */
class AgentEngine(
    private val toolRegistry: ToolRegistry,
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
    private val systemPrompt: (String) -> String = ::buildSystemPrompt,
) {

    fun run(
        provider: LlmProvider,
        model: String,
        history: List<ChatMessage>,
        userMessage: ChatMessage,
        context: ToolContext,
    ): Flow<AgentEvent> = flow {
        val messages = mutableListOf<ChatMessage>()
        messages += ChatMessage(ChatRole.SYSTEM, systemPrompt(toolRegistry.describeForPrompt()))
        messages += history
        // The turn arrives preprocessed: a vision-capable model keeps its
        // attachments, a text-only one has already had them OCR'd away.
        messages += userMessage

        var step = 0
        while (step < maxSteps) {
            step++

            val text = StringBuilder()
            val toolCalls = mutableListOf<ToolCall>()
            var failure: String? = null

            provider.chat(
                ChatRequest(
                    model = model,
                    messages = messages.toList(),
                    tools = toolRegistry.specs,
                    stream = true,
                ),
            ).collect { event ->
                when (event) {
                    is ChatEvent.TextDelta -> {
                        text.append(event.text)
                        emit(AgentEvent.TextDelta(event.text))
                    }

                    is ChatEvent.ToolCallEvent -> toolCalls += mergeToolCallDeltas(event.calls)

                    is ChatEvent.Finished -> Unit

                    is ChatEvent.Error -> failure = event.message
                }
            }

            failure?.let { message ->
                emit(AgentEvent.Error(message))
                return@flow
            }

            if (toolCalls.isEmpty()) {
                emit(AgentEvent.Finished)
                return@flow
            }

            // The assistant turn that requested the tools must be replayed
            // verbatim before any tool result, otherwise the API rejects it.
            messages += ChatMessage(
                role = ChatRole.ASSISTANT,
                content = text.toString(),
                toolCalls = toolCalls,
            )

            for (call in toolCalls) {
                emit(AgentEvent.ToolCallStarted(call))
                val result = runTool(call, context)
                emit(AgentEvent.ToolCallFinished(call, result.text, result.isError, result.fileUri))
                messages += ChatMessage(
                    role = ChatRole.TOOL,
                    content = result.text,
                    toolCallId = call.id,
                )
            }
        }

        emit(AgentEvent.Error("已达到最大工具调用步数（$maxSteps），已停止。"))
    }

    /** Executes one tool call, converting any unexpected throw into tool text. */
    private suspend fun runTool(call: ToolCall, context: ToolContext): ToolResult {
        val tool = toolRegistry.get(call.name)
        if (tool == null) {
            return ToolResult.failure("未知工具：${call.name}，可用工具：${toolRegistry.toolNames.joinToString()}")
        }
        val arguments = parseArguments(call.arguments)
            ?: return ToolResult.failure("工具参数不是合法 JSON：${call.arguments.take(200)}")
        return runCatching { tool.execute(arguments, context) }
            .getOrElse { throwable ->
                ToolResult.failure(
                    "工具 ${call.name} 执行失败：${throwable.message ?: throwable::class.simpleName}",
                )
            }
    }

    private fun parseArguments(raw: String): JsonObject? {
        if (raw.isBlank()) return JsonObject(emptyMap())
        return runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
    }

    companion object {
        const val DEFAULT_MAX_STEPS = 8
    }
}

/**
 * Folds streamed tool call fragments into complete calls.
 *
 * The OpenAI wire format tags every fragment with the call `index`, so that is
 * the grouping key: the `id`, `name` and `arguments` may each arrive on a
 * different fragment of the same call.
 */
internal fun mergeToolCallDeltas(deltas: List<ToolCallDelta>): List<ToolCall> {
    data class Mutable(
        var id: String? = null,
        var name: String? = null,
        val arguments: StringBuilder = StringBuilder(),
    )

    val byIndex = LinkedHashMap<Int, Mutable>()
    for (delta in deltas) {
        val accumulator = byIndex.getOrPut(delta.index) { Mutable() }
        if (!delta.id.isNullOrBlank()) accumulator.id = delta.id
        if (!delta.name.isNullOrBlank()) accumulator.name = delta.name
        delta.argumentsDelta?.let { accumulator.arguments.append(it) }
    }

    return byIndex.values
        .filter { !it.name.isNullOrBlank() }
        .map { accumulator ->
            ToolCall(
                id = accumulator.id ?: "call_" + accumulator.name.orEmpty().hashCode(),
                name = accumulator.name.orEmpty(),
                arguments = accumulator.arguments.toString().ifBlank { "{}" },
            )
        }
}
