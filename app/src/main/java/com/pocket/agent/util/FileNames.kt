package com.pocket.agent.util

/** Result of validating a user supplied file name. */
sealed interface NameCheck {
    data object Ok : NameCheck
    data class Invalid(val reason: String) : NameCheck
}

/**
 * Rules for names the user or the model may create.
 *
 * The point is to keep a name inside the directory it was created in: no path
 * separators, no parent references, no control characters.
 */
object FileNames {

    /** Hard ceiling on a single file, applied on both read and write. */
    const val MAX_FILE_BYTES: Long = 1L * 1024 * 1024

    private const val MAX_NAME_LENGTH = 128

    private val WINDOWS_RESERVED = setOf(
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
    )

    fun check(fileName: String): NameCheck {
        if (fileName.isBlank()) return NameCheck.Invalid("文件名不能为空")
        if (fileName.length > MAX_NAME_LENGTH) {
            return NameCheck.Invalid("文件名过长，最多 $MAX_NAME_LENGTH 个字符")
        }
        if (fileName.any { it == '/' || it == '\\' || it == '\u0000' }) {
            return NameCheck.Invalid("文件名不能包含 /、\\ 或空字符")
        }
        if (fileName.contains("..")) return NameCheck.Invalid("文件名不能包含 ..")
        if (fileName.any { it.isISOControl() }) return NameCheck.Invalid("文件名不能包含控制字符")
        if (fileName.endsWith('.') || fileName.endsWith(' ')) {
            return NameCheck.Invalid("文件名不能以 . 或空格结尾")
        }
        if (fileName == "." || fileName == "..") return NameCheck.Invalid("文件名不能为 . 或 ..")
        val trimmed = fileName.trim()
        if (trimmed.substringBeforeLast('.').lowercase() in WINDOWS_RESERVED) {
            return NameCheck.Invalid("$trimmed 是系统保留名称")
        }
        return NameCheck.Ok
    }

    /** Convenience wrapper returning null when the name is usable. */
    fun error(fileName: String): String? = (check(fileName) as? NameCheck.Invalid)?.reason
}
