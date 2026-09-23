package com.pocket.agent.agent

import com.pocket.agent.data.file.FileExistsException
import com.pocket.agent.data.file.FileTooLargeException
import com.pocket.agent.data.file.SafFileRepository
import com.pocket.agent.llm.ToolSpec
import com.pocket.agent.util.FileNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Returns the absolute path the agent is currently working in. */
class GetCurrentDirectoryTool : AgentTool {

    override val spec = ToolSpec(
        name = "get_current_directory",
        description = "返回 Agent 当前工作的目录路径，不需要参数。",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {})
        },
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val dirUri = runCatching { context.requireDirUri() }.getOrElse { e ->
            return ToolResult.failure(e.message ?: "无法确定当前目录")
        }
        return ToolResult.success(dirUri.toString())
    }
}

/** Lists the names, sizes and kinds of everything in the current directory. */
class ListFilesTool : AgentTool {

    override val spec = ToolSpec(
        name = "list_files",
        description = "列出当前目录下的所有文件和子目录，包含名称、类型与大小。",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {})
        },
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val dirUri = runCatching { context.requireDirUri() }.getOrElse { e ->
            return ToolResult.failure(e.message ?: "无法确定当前目录")
        }
        val entries = runCatching { context.files.listFiles(dirUri) }.getOrElse { e ->
            return ToolResult.failure("读取目录失败：${e.message ?: "未知错误"}")
        }
        if (entries.isEmpty()) return ToolResult.success("目录为空。")

        val lines = entries.joinToString("\n") { entry ->
            val kind = if (entry.isDirectory) "目录" else "${entry.sizeBytes / 1024} KB"
            "- ${entry.name}（$kind）"
        }
        return ToolResult.success("当前目录共有 ${entries.size} 项：\n$lines")
    }
}

/** Creates a file in the current directory and writes [content] into it. */
class CreateFileTool : AgentTool {

    override val spec = ToolSpec(
        name = "create_file",
        description = "在当前目录创建文件并写入内容。若同名文件已存在，需要用户确认后才会覆盖。",
        parameters = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("file_name", buildJsonObject {
                        put("type", "string")
                        put("description", "文件名，不能包含路径分隔符")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "要写入的文件内容")
                    })
                    put("mime_type", buildJsonObject {
                        put("type", "string")
                        put("description", "MIME 类型，默认 text/plain")
                        put(
                            "enum",
                            buildJsonArray {
                                add("text/plain")
                                add("text/markdown")
                                add("text/html")
                                add("application/json")
                            },
                        )
                    })
                },
            )
            put("required", buildJsonArray { add("file_name"); add("content") })
        },
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val dirUri = runCatching { context.requireDirUri() }.getOrElse { e ->
            return ToolResult.failure(e.message ?: "无法确定当前目录")
        }
        val fileName = arguments.requiredString("file_name").getOrElse { e ->
            return ToolResult.failure(e.message ?: "参数 file_name 无效")
        }
        val content = arguments.requiredString("content").getOrElse { e ->
            return ToolResult.failure(e.message ?: "参数 content 无效")
        }
        val mimeType = arguments.optionalString("mime_type", SafFileRepository.DEFAULT_MIME_TYPE)

        FileNames.error(fileName)?.let { return ToolResult.failure(it) }

        val existing = runCatching { context.files.findFile(dirUri, fileName) }.getOrNull()
        if (existing != null && !existing.isDirectory) {
            val approved = context.confirm(
                ConfirmRequest(
                    title = "覆盖文件？",
                    message = "「${existing.name}」已存在，是否用新内容覆盖它？",
                ),
            )
            if (!approved) {
                return ToolResult.failure("用户拒绝覆盖 ${existing.name}，文件未修改。")
            }
        }

        return runCatching {
            context.files.createFile(
                dirUri = dirUri,
                fileName = fileName,
                content = content,
                mimeType = mimeType,
                overwrite = true,
            )
        }.fold(
            onSuccess = { entry ->
                ToolResult.success("已在当前目录创建 ${entry.name}，写入 ${content.toByteArray().size} 字节。")
            },
            onFailure = { e -> ToolResult.failure(describe(e, fileName)) },
        )
    }
}

/** Reads the text of a file in the current directory. */
class ReadFileTool : AgentTool {

    override val spec = ToolSpec(
        name = "read_file",
        description = "读取当前目录中某个文件的文本内容。",
        parameters = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("file_name", buildJsonObject {
                        put("type", "string")
                        put("description", "要读取的文件名")
                    })
                },
            )
            put("required", buildJsonArray { add("file_name") })
        },
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val dirUri = runCatching { context.requireDirUri() }.getOrElse { e ->
            return ToolResult.failure(e.message ?: "无法确定当前目录")
        }
        val fileName = arguments.requiredString("file_name").getOrElse { e ->
            return ToolResult.failure(e.message ?: "参数 file_name 无效")
        }
        FileNames.error(fileName)?.let { return ToolResult.failure(it) }

        val entry = runCatching { context.files.findFile(dirUri, fileName) }.getOrNull()
            ?: return ToolResult.failure("文件不存在：$fileName")
        if (entry.isDirectory) return ToolResult.failure("$fileName 是目录，不能读取。")

        return runCatching { context.files.readText(entry.uri) }.fold(
            onSuccess = { ToolResult.success(it) },
            onFailure = { e -> ToolResult.failure(describe(e, fileName)) },
        )
    }
}

/** Overwrites an existing file in the current directory. */
class WriteFileTool : AgentTool {

    override val spec = ToolSpec(
        name = "write_file",
        description = "覆盖写入当前目录中已存在文件的全部内容，写入前需要用户确认。",
        parameters = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("file_name", buildJsonObject {
                        put("type", "string")
                        put("description", "要覆盖的文件名")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "新的文件内容")
                    })
                },
            )
            put("required", buildJsonArray { add("file_name"); add("content") })
        },
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val dirUri = runCatching { context.requireDirUri() }.getOrElse { e ->
            return ToolResult.failure(e.message ?: "无法确定当前目录")
        }
        val fileName = arguments.requiredString("file_name").getOrElse { e ->
            return ToolResult.failure(e.message ?: "参数 file_name 无效")
        }
        val content = arguments.requiredString("content").getOrElse { e ->
            return ToolResult.failure(e.message ?: "参数 content 无效")
        }
        FileNames.error(fileName)?.let { return ToolResult.failure(it) }

        val entry = runCatching { context.files.findFile(dirUri, fileName) }.getOrNull()
            ?: return ToolResult.failure("文件不存在：$fileName，请改用 create_file。")
        if (entry.isDirectory) return ToolResult.failure("$fileName 是目录，不能写入。")

        val approved = context.confirm(
            ConfirmRequest(
                title = "覆盖文件？",
                message = "将用新内容覆盖「${entry.name}」（当前 ${entry.sizeBytes / 1024} KB）。",
            ),
        )
        if (!approved) return ToolResult.failure("用户拒绝覆盖 ${entry.name}，文件未修改。")

        return runCatching { context.files.writeText(entry.uri, content) }.fold(
            onSuccess = {
                ToolResult.success("已覆盖 ${entry.name}，写入 ${content.toByteArray().size} 字节。")
            },
            onFailure = { e -> ToolResult.failure(describe(e, fileName)) },
        )
    }
}

/** Deletes a file from the current directory after explicit confirmation. */
class DeleteFileTool : AgentTool {

    override val spec = ToolSpec(
        name = "delete_file",
        description = "删除当前目录中的某个文件，删除前需要用户确认。",
        parameters = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("file_name", buildJsonObject {
                        put("type", "string")
                        put("description", "要删除的文件名")
                    })
                },
            )
            put("required", buildJsonArray { add("file_name") })
        },
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val dirUri = runCatching { context.requireDirUri() }.getOrElse { e ->
            return ToolResult.failure(e.message ?: "无法确定当前目录")
        }
        val fileName = arguments.requiredString("file_name").getOrElse { e ->
            return ToolResult.failure(e.message ?: "参数 file_name 无效")
        }
        FileNames.error(fileName)?.let { return ToolResult.failure(it) }

        val entry = runCatching { context.files.findFile(dirUri, fileName) }.getOrNull()
            ?: return ToolResult.failure("文件不存在：$fileName")
        if (entry.isDirectory) return ToolResult.failure("$fileName 是目录，暂不支持删除目录。")

        val approved = context.confirm(
            ConfirmRequest(
                title = "删除文件？",
                message = "「${entry.name}」将被永久删除，且无法恢复。",
            ),
        )
        if (!approved) return ToolResult.failure("用户拒绝删除 ${entry.name}，文件未删除。")

        return runCatching { context.files.delete(entry.uri) }.fold(
            onSuccess = { deleted ->
                if (deleted) {
                    ToolResult.success("已删除 ${entry.name}。")
                } else {
                    ToolResult.failure("删除 ${entry.name} 失败，可能已被移除。")
                }
            },
            onFailure = { e -> ToolResult.failure(describe(e, fileName)) },
        )
    }
}

/** Turns a repository exception into text the model can act on. */
private fun describe(error: Throwable, fileName: String): String = when (error) {
    is FileExistsException -> error.message ?: "文件已存在：$fileName"
    is FileTooLargeException -> error.message ?: "文件超过 1MB 限制"
    else -> error.message ?: "操作失败"
}
