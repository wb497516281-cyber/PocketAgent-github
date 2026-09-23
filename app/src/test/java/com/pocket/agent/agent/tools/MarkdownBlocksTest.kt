package com.pocket.agent.agent.tools

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The block parser is the only half of the PDF pipeline that runs on the JVM,
 * so the pragmatic Markdown subset the models actually emit is pinned here.
 */
class MarkdownBlocksTest {

    @Test
    fun `headings bullets paragraphs and code become separate blocks`() {
        val markdown = listOf(
            "# 标题一",
            "## 标题二",
            "",
            "第一段，",
            "还没说完。",
            "",
            "- 项目一",
            "* 项目二",
            "1. 第一项",
            "2) 第二项",
            "",
            "```kotlin",
            "val a = 1",
            "```",
            "",
            "---",
            "",
            "最后一段。",
        ).joinToString("\n")

        assertEquals(
            listOf(
                MarkdownBlock.Heading(1, "标题一"),
                MarkdownBlock.Heading(2, "标题二"),
                MarkdownBlock.Paragraph("第一段，\n还没说完。"),
                MarkdownBlock.Bullet("项目一"),
                MarkdownBlock.Bullet("项目二"),
                MarkdownBlock.Bullet("第一项"),
                MarkdownBlock.Bullet("第二项"),
                MarkdownBlock.Code("val a = 1"),
                MarkdownBlock.Paragraph("最后一段。"),
            ),
            parseMarkdownBlocks(markdown),
        )
    }

    @Test
    fun `heading levels clamp at six`() {
        assertEquals(
            listOf(
                MarkdownBlock.Heading(1, "一级"),
                MarkdownBlock.Heading(4, "四级"),
                MarkdownBlock.Heading(6, "六级"),
            ),
            parseMarkdownBlocks("# 一级\n#### 四级\n###### 六级"),
        )
    }

    @Test
    fun `a hash without a following space is plain text`() {
        assertEquals(
            listOf(MarkdownBlock.Paragraph("#不是标题")),
            parseMarkdownBlocks("#不是标题"),
        )
    }

    @Test
    fun `carriage returns fold into the same line breaks`() {
        assertEquals(
            listOf(
                MarkdownBlock.Heading(1, "标题"),
                MarkdownBlock.Paragraph("一段"),
            ),
            parseMarkdownBlocks("# 标题\r\n\r\n一段\r"),
        )
    }

    @Test
    fun `an unterminated fence still renders as code`() {
        // Providers clip long outputs, so a missing fence must not swallow the
        // rest of the document into a code block that never ends.
        assertEquals(
            listOf(MarkdownBlock.Code("int main() {")),
            parseMarkdownBlocks("```\nint main() {"),
        )
    }

    @Test
    fun `an empty document yields nothing to render`() {
        assertEquals(emptyList<MarkdownBlock>(), parseMarkdownBlocks(""))
        assertEquals(emptyList<MarkdownBlock>(), parseMarkdownBlocks("\n\n---\n\n"))
    }

    @Test
    fun `inline markup is flattened to visible text`() {
        assertEquals("加粗", stripInline("**加粗**"))
        assertEquals("斜体", stripInline("*斜体*"))
        assertEquals("代码", stripInline("`代码`"))
        assertEquals("链接文字", stripInline("[链接文字](https://example.com)"))
        assertEquals("普通文字", stripInline("普通文字"))
        // Unclosed emphasis reads better as plain text than as noise.
        assertEquals("加粗 未闭合", stripInline("加粗 **未闭合"))
        // A lone backtick has nothing to close, so it is dropped as noise.
        assertEquals("源码 片段", stripInline("源码 `片段"))
    }

    @Test
    fun `bullet and heading markers keep their inner markup flattened`() {
        assertEquals(
            listOf(
                MarkdownBlock.Heading(2, "粗体标题"),
                MarkdownBlock.Bullet("重点条目"),
            ),
            parseMarkdownBlocks("## **粗体标题**\n\n- *重点条目*"),
        )
    }
}
