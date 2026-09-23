package com.pocket.agent.agent

import android.net.Uri
import com.pocket.agent.data.chat.ImageAttachment
import com.pocket.agent.data.file.SafFileRepository
import com.pocket.agent.llm.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A yes/no question the agent raises before touching existing data. */
data class ConfirmRequest(
    val title: String,
    val message: String,
    val confirmLabel: String = "确认",
    val denyLabel: String = "取消",
)

/**
 * Everything a tool is allowed to know about the current run.
 *
 * [confirm] is a suspend hook so destructive actions can wait for the user
 * without the agent loop having to know about dialogs.
 */
class ToolContext(
    val files: SafFileRepository,
    val confirm: suspend (ConfirmRequest) -> Boolean = { true },
    /**
     * Photos the user attached to the turn that is running, in the order they
     * were picked. A document tool can embed one when the model refers to it
     * by position, which is how "把这些图做成 PPT" works.
     */
    val images: List<ImageAttachment> = emptyList(),
) {
    /** Directory the agent operates on; tools fail with a readable message if unset. */
    suspend fun requireDirUri(): Uri {
        val uri = files.getCurrentDirUri()
        return uri ?: throw NoDirectorySelectedException()
    }
}

class NoDirectorySelectedException :
    Exception("尚未选择保存目录，请在「文件」页选择一个目录")

/** What a tool hands back to the model. */
data class ToolResult(
    val text: String,
    val isError: Boolean = false,
    /**
     * SAF uri of a file the tool produced, when it produced one. The chat UI
     * offers an "open file" action for it; the model only ever sees [text].
     */
    val fileUri: String? = null,
) {

    companion object {
        fun success(text: String) = ToolResult(text, isError = false)
        fun failure(text: String) = ToolResult(text, isError = true)
    }
}

/**
 * A capability the model can call.
 *
 * [spec] is advertised to the model, [execute] runs it. Implementations must
 * never throw: every failure is reported as [ToolResult.failure] so the loop
 * keeps going and the model can explain what went wrong.
 */
interface AgentTool {
    val spec: ToolSpec

    suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult
}

/** Reads a required string argument, returning a failure result when absent. */
internal fun JsonObject.requiredString(key: String): Result<String> {
    val value = this[key]?.let { element ->
        runCatching { element.jsonPrimitive.content }.getOrNull()
    }
    return if (value.isNullOrBlank()) {
        Result.failure(IllegalArgumentException("缺少参数 $key"))
    } else {
        Result.success(value)
    }
}

/** Reads an optional string argument. */
internal fun JsonObject.optionalString(key: String, fallback: String): String =
    this[key]?.let { element -> runCatching { element.jsonPrimitive.content }.getOrNull() }
        ?.takeIf { it.isNotBlank() }
        ?: fallback
