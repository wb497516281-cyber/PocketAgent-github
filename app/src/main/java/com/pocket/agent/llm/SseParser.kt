package com.pocket.agent.llm

/** Helpers for reading the `data:` framed lines of a Server-Sent Events body. */
internal object SseParser {

    const val DONE = "[DONE]"

    /** Returns the payload of a `data:` line, or null for any other line. */
    fun payload(line: String): String? {
        val trimmed = line.removeSuffix("\r")
        if (!trimmed.startsWith("data:")) return null
        return trimmed.removePrefix("data:").trimStart(' ')
    }

    /** True when the line carries no information (blank line or an SSE comment). */
    fun isIgnorable(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.isEmpty() || trimmed.startsWith(":")
    }
}
