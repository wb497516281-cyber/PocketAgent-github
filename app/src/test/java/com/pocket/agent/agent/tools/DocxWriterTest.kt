package com.pocket.agent.agent.tools

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Apache POI is not an option on Android, so the .docx is written by hand;
 * these tests are what stand behind the claim that Word can still open it.
 * They assert on the package layout and on the WordprocessingML fragments
 * that carry structure - heading styles, table grid, XML escaping.
 */
class DocxWriterTest {

    @Test
    fun `writes exactly the parts a minimal word package needs`() {
        val pkg = packageOf(
            DocContentSpec(
                title = "周报",
                paragraphs = listOf(DocParagraphSpec(text = "本周完成的事项")),
            ),
        )
        assertEquals(
            setOf(
                "[Content_Types].xml",
                "_rels/.rels",
                "word/document.xml",
                "word/_rels/document.xml.rels",
                "word/styles.xml",
                "docProps/core.xml",
                "docProps/app.xml",
            ),
            pkg.names,
        )
    }

    @Test
    fun `the title and headings map to word heading styles`() {
        val pkg = packageOf(
            DocContentSpec(
                title = "文档标题",
                paragraphs = listOf(
                    DocParagraphSpec(text = "二级标题", heading = 2),
                    DocParagraphSpec(text = "三级标题", heading = 3),
                    DocParagraphSpec(text = "正文段落", heading = 0),
                    DocParagraphSpec(text = "越界的层级退化成正文", heading = 9),
                ),
            ),
        )
        val document = pkg["word/document.xml"]!!
        assertTrue(document.contains("<w:pStyle w:val=\"Heading1\"/>"))
        assertTrue(document.contains("<w:pStyle w:val=\"Heading2\"/>"))
        assertTrue(document.contains("<w:pStyle w:val=\"Heading3\"/>"))
        // Heading 4 and 0 both fall through to a plain paragraph.
        assertEquals(3, pkg.count("word/document.xml", "<w:pStyle"))

        val styles = pkg["word/styles.xml"]!!
        assertTrue(styles.contains("w:styleId=\"Heading1\""))
        assertTrue(styles.contains("w:outlineLvl"))

        val core = pkg["docProps/core.xml"]!!
        assertTrue(core.contains("<dc:title>文档标题</dc:title>"))
    }

    @Test
    fun `tables are emitted with a grid a header row and one row per entry`() {
        val pkg = packageOf(
            DocContentSpec(
                tables = listOf(
                    DocTableSpec(
                        headers = listOf("任务", "状态"),
                        rows = listOf(listOf("写单测", "完成"), listOf("补文档")),
                    ),
                ),
            ),
        )
        val document = pkg["word/document.xml"]!!
        assertTrue(document.contains("<w:tbl>"))
        // Two columns, so two grid columns.
        assertEquals(2, pkg.count("word/document.xml", "<w:gridCol"))
        // Header row plus two body rows.
        assertEquals(3, pkg.count("word/document.xml", "<w:tr>"))
        assertEquals(6, pkg.count("word/document.xml", "<w:tc>"))
        assertTrue(document.contains("<w:tblBorders>"))
        // A short row is padded, so the table never ends up ragged.
        assertTrue(document.contains("<w:t xml:space=\"preserve\">写单测</w:t>"))
    }

    @Test
    fun `text is xml escaped so quotes cannot break the document`() {
        val pkg = packageOf(
            DocContentSpec(title = "Tom & Jerry <b>", paragraphs = listOf(DocParagraphSpec(text = "a > b"))),
        )
        val document = pkg["word/document.xml"]!!
        assertTrue(document.contains("Tom &amp; Jerry &lt;b&gt;"))
        assertTrue(document.contains("a &gt; b"))
        assertTrue(!document.contains("<b>"))
    }

    @Test
    fun `an empty content still yields a document word can open`() {
        val pkg = packageOf(DocContentSpec())
        assertTrue(pkg["word/document.xml"]!!.contains("<w:body><w:sectPr>"))
    }

    private fun packageOf(content: DocContentSpec): ZipPackage {
        val stream = ByteArrayOutputStream()
        DocxWriter.write(content, stream)
        return ZipPackage.from(stream.toByteArray())
    }
}
