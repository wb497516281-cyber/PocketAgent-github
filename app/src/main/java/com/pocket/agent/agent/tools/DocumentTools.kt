package com.pocket.agent.agent.tools

import com.pocket.agent.agent.AgentTool
import com.pocket.agent.agent.ConfirmRequest
import com.pocket.agent.agent.ToolContext
import com.pocket.agent.agent.ToolResult
import com.pocket.agent.agent.requiredString
import com.pocket.agent.data.file.BinaryTooLargeException
import com.pocket.agent.data.file.FileExistsException
import com.pocket.agent.llm.ToolSpec
import com.pocket.agent.util.FileNames
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

internal const val PDF_MIME_TYPE = "application/pdf"
internal const val DOCX_MIME_TYPE =
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
internal const val PPTX_MIME_TYPE =
    "application/vnd.openxmlformats-officedocument.presentationml.presentation"

/**
 * Turns Markdown into a PDF in the SAF directory.
 *
 * The model hands over Markdown; the local parser and renderer turn it into
 * pages, so nothing leaves the device. Failures - an unreadable directory, a
 * payload that will not encode, an overwrite the user declines - all come back
 * as [ToolResult.failure] text rather than an exception.
 */
class CreatePdfTool : AgentTool {

    override val spec = ToolSpec(
        name = "create_pdf",
        description = "把 Markdown 内容排版成 PDF 文档并保存到当前目录。" +
            "适合周报、总结、说明文档等需要正式排版的输出。",
        parameters = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("file_name", buildJsonObject {
                        put("type", "string")
                        put("description", "文件名，不需要扩展名，自动补 .pdf")
                    })
                    put("markdown_content", buildJsonObject {
                        put("type", "string")
                        put("description", "Markdown 正文，支持 # 标题、- 列表、``` 代码块")
                    })
                },
            )
            put("required", buildJsonArray { add("file_name"); add("markdown_content") })
        },
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val fileName = arguments.requiredString("file_name").getOrElse { e ->
            return ToolResult.failure(e.message ?: "参数 file_name 无效")
        }
        val markdown = arguments.requiredString("markdown_content").getOrElse { e ->
            return ToolResult.failure(e.message ?: "参数 markdown_content 无效")
        }
        val blocks = parseMarkdownBlocks(markdown)
        if (blocks.isEmpty()) {
            return ToolResult.failure("markdown_content 没有可排版的内容")
        }
        return saveGeneratedDocument(
            context = context,
            fileName = withExtension(fileName, "pdf"),
            mimeType = PDF_MIME_TYPE,
            kind = "PDF",
        ) {
            val stream = ByteArrayOutputStream()
            PdfWriter.render(blocks, stream)
            stream.toByteArray()
        }
    }
}

/**
 * Writes a Word document from the JSON body spec.
 *
 * The JSON shape is {"title":"...","paragraphs":[{"text":"...","heading":0}],
 * "tables":[{"headers":["..."],"rows":[["..."]]}]}. Heading levels 1-3 map to
 * Word heading styles; 0 means a normal paragraph.
 */
class CreateWordTool : AgentTool {

    override val spec = ToolSpec(
        name = "create_word",
        description = "生成 Word 文档（.docx）并保存到当前目录。" +
            "适合周报、报告等带标题层级和表格的正式文档。",
        parameters = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("file_name", buildJsonObject {
                        put("type", "string")
                        put("description", "文件名，不需要扩展名，自动补 .docx")
                    })
                    put("content_json", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "文档结构的 JSON 字符串：" +
                                "{\"title\":\"标题\",\"paragraphs\":[{\"text\":\"段落文字\",\"heading\":0}]," +
                                "\"tables\":[{\"headers\":[\"列一\",\"列二\"],\"rows\":[[\"a\",\"b\"]]}]}" +
                                "。heading 取 1-3 表示标题层级，0 表示正文。",
                        )
                    })
                },
            )
            put("required", buildJsonArray { add("file_name"); add("content_json") })
        },
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val fileName = arguments.requiredString("file_name").getOrElse { e ->
            return ToolResult.failure(e.message ?: "参数 file_name 无效")
        }
        val contentJson = arguments.requiredString("content_json").getOrElse { e ->
            return ToolResult.failure(e.message ?: "参数 content_json 无效")
        }
        val content = decodeDocContent(contentJson)
            ?: return ToolResult.failure(
                "content_json 不是合法的文档 JSON，期望形如 " +
                    "{\"title\":\"...\",\"paragraphs\":[{\"text\":\"...\",\"heading\":0}],\"tables\":[...]}",
            )
        if (content.isEmpty()) {
            return ToolResult.failure("content_json 里没有任何段落或表格，无法生成文档")
        }
        return saveGeneratedDocument(
            context = context,
            fileName = withExtension(fileName, "docx"),
            mimeType = DOCX_MIME_TYPE,
            kind = "Word 文档",
        ) {
            val stream = ByteArrayOutputStream()
            DocxWriter.write(content, stream)
            stream.toByteArray()
        }
    }
}

/**
 * Writes a PowerPoint deck from the JSON slide spec.
 *
 * The JSON shape is {"slides":[{"title":"...","subtitle":"...",
 * "bullets":["..."],"layout":"cover|section|content|end","notes":"...",
 * "images":[{"url":"...","caption":"..."}]}]; the subtitle, speaker notes and
 * pictures are optional and only emitted when present. The layout field picks
 * the visual treatment per slide; left blank it is inferred from where the
 * slide sits in the deck. A picture is either a web address or one of the
 * photos the user attached to this conversation, referred to by position.
 */
class CreatePptxTool(private val imageClient: OkHttpClient) : AgentTool {

    override val spec = ToolSpec(
        name = "create_ppt",
        description = "生成演示文稿（.pptx）并保存到当前目录。" +
            "每页可声明版式：封面 cover、章节过渡 section、正文 content、结束页 end；" +
            "未声明时第一页按封面、最后一页按结束页处理。",
        parameters = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("file_name", buildJsonObject {
                        put("type", "string")
                        put("description", "文件名，不需要扩展名，自动补 .pptx")
                    })
                    put("slides_json", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "幻灯片结构的 JSON 字符串：" +
                                "{\"slides\":[{\"title\":\"页标题\",\"subtitle\":\"副标题（可选，仅封面/结束页显示）\",\"bullets\":[\"要点一\",\"要点二\"],\"layout\":\"cover | section | content | end\",\"notes\":\"演讲者备注\",\"images\":[{\"url\":\"图片地址\",\"caption\":\"图注（可选）\"}]}]}" +
                                "。版式建议：第一页用 cover，章节切换用 section，内容页用 content，" +
                                "最后一页用 end；每页 3-6 条要点、每条不超过 50 字效果最好。" +
                                "需要插图时在对应页加 images：url 填 http(s) 图片地址（可用 web_search 找到），" +
                                "attachment 填本次对话中用户上传图片的序号（从 1 开始）；也可两者只填其一。" +
                                "每页最多 3 张，图放在正文右侧或封面右半，caption 会显示在图下方。",
                        )
                    })
                },
            )
            put("required", buildJsonArray { add("file_name"); add("slides_json") })
        },
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val fileName = arguments.requiredString("file_name").getOrElse { e ->
            return ToolResult.failure(e.message ?: "参数 file_name 无效")
        }
        val slidesJson = arguments.requiredString("slides_json").getOrElse { e ->
            return ToolResult.failure(e.message ?: "参数 slides_json 无效")
        }
        val deck = decodeSlideDeck(slidesJson)
            ?: return ToolResult.failure(
                "slides_json 不是合法的幻灯片 JSON，期望形如 " +
                    "{\"slides\":[{\"title\":\"...\",\"bullets\":[\"...\"],\"notes\":\"...\"}]}",
            )
        if (deck.slides.isEmpty()) {
            return ToolResult.failure("slides_json 里没有任何幻灯片，无法生成演示文稿")
        }
        val crowded = deck.slides.withIndex().firstOrNull { (_, spec) ->
            spec.images.size > MAX_IMAGES_PER_SLIDE
        }
        if (crowded != null) {
            return ToolResult.failure(
                "第 ${crowded.index + 1} 页放了 ${crowded.value.images.size} 张图片，" +
                    "超过每页 $MAX_IMAGES_PER_SLIDE 张的上限，请删减后再试。",
            )
        }
        val pictures = SlideImageResolver(imageClient).resolve(deck, context.images)
        return saveGeneratedDocument(
            context = context,
            fileName = withExtension(fileName, "pptx"),
            mimeType = PPTX_MIME_TYPE,
            kind = "PPT",
            notes = pictures.skipped.map { skipped ->
                "第 ${skipped.slideNumber} 页的图片 ${skipped.source} 未使用：${skipped.reason}"
            },
        ) {
            val stream = ByteArrayOutputStream()
            PptxWriter.write(deck, pictures.bySlide, stream)
            stream.toByteArray()
        }
    }
}

/**
 * Shared tail of every document tool: resolve the directory, ask before an
 * overwrite, render the bytes, then write them through SAF.
 *
 * The user is asked before anything is rendered, so declining an overwrite
 * costs nothing. [ToolResult.fileUri] carries the created document back to the
 * chat UI, which offers to open it; the model only ever sees the text.
 */
private suspend fun saveGeneratedDocument(
    context: ToolContext,
    fileName: String,
    mimeType: String,
    kind: String,
    /** Problems worth passing back to the model, one line each. */
    notes: List<String> = emptyList(),
    generate: suspend () -> ByteArray,
): ToolResult {
    val dirUri = runCatching { context.requireDirUri() }.getOrElse { e ->
        return ToolResult.failure(e.message ?: "无法确定当前目录")
    }
    FileNames.error(fileName)?.let { return ToolResult.failure(it) }

    val existing = runCatching { context.files.findFile(dirUri, fileName) }.getOrNull()
    if (existing != null && !existing.isDirectory) {
        val approved = context.confirm(
            ConfirmRequest(
                title = "覆盖文件？",
                message = "「${existing.name}」已存在，是否用新生成的文档覆盖它？",
            ),
        )
        if (!approved) {
            return ToolResult.failure("用户拒绝覆盖 ${existing.name}，文件未修改。")
        }
    }

    val bytes = runCatching {
        withContext(Dispatchers.IO) { generate() }
    }.getOrElse { error ->
        return ToolResult.failure("$kind 生成失败：${error.message ?: "未知错误"}")
    }

    return runCatching {
        context.files.createBinaryFile(
            dirUri = dirUri,
            fileName = fileName,
            content = bytes,
            mimeType = mimeType,
            overwrite = true,
        )
    }.fold(
        onSuccess = { entry ->
            ToolResult(
                text = buildString {
                    append("已生成 ${entry.name}（${describeSize(bytes.size.toLong())}），保存在当前目录。")
                    notes.forEach { note -> append("\n").append(note) }
                },
                isError = false,
                fileUri = entry.uri.toString(),
            )
        },
        onFailure = { error -> ToolResult.failure(describeDocumentError(error, fileName)) },
    )
}

/** Turns a repository exception into text the model can act on. */
private fun describeDocumentError(error: Throwable, fileName: String): String = when (error) {
    is FileExistsException -> error.message ?: "文件已存在：$fileName"
    is BinaryTooLargeException -> error.message ?: "生成的文档超出大小限制"
    else -> error.message ?: "写入失败"
}

private fun describeSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "${bytes / 1024 / 1024} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes 字节"
}

/** Appends [extension] unless the model already supplied one. */
internal fun withExtension(fileName: String, extension: String): String {
    val trimmed = fileName.trim()
    return if (trimmed.endsWith(".$extension", ignoreCase = true)) trimmed else "$trimmed.$extension"
}

/**
 * Strips a Markdown code fence models sometimes wrap JSON in.
 *
 * Some providers emit fenced blocks even inside a tool argument; the decoder
 * would reject those, so the fence is removed before parsing.
 */
private fun stripJsonFences(raw: String): String = raw
    .trim()
    .removePrefix("```json")
    .removePrefix("```JSON")
    .removePrefix("```")
    .removeSuffix("```")
    .trim()

private val LENIENT_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun decodeDocContent(raw: String): DocContentSpec? {
    val cleaned = stripJsonFences(raw)
    if (cleaned.isEmpty()) return null
    return runCatching { LENIENT_JSON.decodeFromString<DocContentSpec>(cleaned) }.getOrNull()
}

internal fun decodeSlideDeck(raw: String): SlideDeckSpec? {
    val cleaned = stripJsonFences(raw)
    if (cleaned.isEmpty()) return null
    return runCatching { LENIENT_JSON.decodeFromString<SlideDeckSpec>(cleaned) }.getOrNull()
}

/** True when the spec carries anything at all to render. */
private fun DocContentSpec.isEmpty(): Boolean =
    title.isBlank() && paragraphs.none { it.text.isNotBlank() } && tables.isEmpty()
