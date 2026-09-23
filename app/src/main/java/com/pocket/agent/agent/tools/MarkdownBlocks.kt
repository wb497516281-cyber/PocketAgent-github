package com.pocket.agent.agent.tools

/** One rendered chunk of a Markdown document. */
sealed interface MarkdownBlock {

    /** `#`-style heading; [level] is 1..6. */
    data class Heading(val level: Int, val text: String) : MarkdownBlock

    /** Running text; soft line breaks inside a paragraph are preserved. */
    data class Paragraph(val text: String) : MarkdownBlock

    /** A list item, bulleted or numbered, with the marker stripped. */
    data class Bullet(val text: String) : MarkdownBlock

    /** A fenced code block, kept verbatim. */
    data class Code(val text: String) : MarkdownBlock
}

/**
 * Turns Markdown into the block list the PDF writer renders.
 *
 * The grammar is the pragmatic subset models actually emit: ATX headings,
 * `-`/`*`/`+` and numbered list items, fenced code, and paragraphs. Inline
 * emphasis, code spans and links are flattened to their visible text, because
 * a PDF page has no room for markup the reader cannot act on. Everything is a
 * pure function so the parser is unit tested on the JVM.
 */
internal fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')

    val paragraph = StringBuilder()
    val code = StringBuilder()
    var inCode = false

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MarkdownBlock.Paragraph(stripInline(paragraph.toString().trim()))
            paragraph.setLength(0)
        }
    }

    for (raw in lines) {
        val trimmed = raw.trim()
        if (trimmed.startsWith("```")) {
            if (inCode) {
                blocks += MarkdownBlock.Code(code.toString().trim('\n'))
                code.setLength(0)
                inCode = false
            } else {
                flushParagraph()
                inCode = true
            }
            continue
        }
        if (inCode) {
            code.append(raw).append('\n')
            continue
        }
        if (trimmed.isEmpty()) {
            flushParagraph()
            continue
        }
        if (isHorizontalRule(trimmed)) {
            flushParagraph()
            continue
        }
        val heading = HEADING_REGEX.matchEntire(trimmed)
        if (heading != null) {
            flushParagraph()
            val level = heading.groupValues[1].length.coerceIn(1, 6)
            blocks += MarkdownBlock.Heading(level, stripInline(heading.groupValues[2].trim()))
            continue
        }
        val bullet = BULLET_REGEX.matchEntire(trimmed)
        if (bullet != null) {
            flushParagraph()
            blocks += MarkdownBlock.Bullet(stripInline(bullet.groupValues[1].trim()))
            continue
        }
        if (paragraph.isNotEmpty()) paragraph.append('\n')
        paragraph.append(trimmed)
    }

    // An unterminated fence still counts as code; models clip output.
    if (inCode && code.isNotEmpty()) {
        blocks += MarkdownBlock.Code(code.toString().trim('\n'))
    }
    flushParagraph()
    return blocks
}

/** `---`, `***` and `___` separators carry no text worth rendering. */
private fun isHorizontalRule(trimmed: String): Boolean =
    trimmed.length >= 3 && trimmed.all { it == '-' || it == '*' || it == '_' }

/** Drops the inline markup, keeping only what a reader should see. */
internal fun stripInline(text: String): String = text
    .replace(CODE_SPAN, "$1")
    .replace(LINK, "$1")
    .replace(BOLD, "$1")
    .replace(ITALIC, "$1")
    // A leftover marker would print as noise; emphasis that never closed is
    // better shown as plain text.
    .replace("**", "")
    .replace("`", "")

private val HEADING_REGEX = Regex("^(#{1,6})\\s+(.*)$")

private val BULLET_REGEX = Regex("^(?:[-*+]|\\d+[.)])\\s+(.*)$")

private val CODE_SPAN = Regex("`([^`]*)`")

private val LINK = Regex("\\[([^]]*)]\\(([^)]*)\\)")

private val BOLD = Regex("\\*\\*([^*]+)\\*\\*")

private val ITALIC = Regex("\\*([^*]+)\\*")
