package com.pocket.agent.agent.tools

import java.io.OutputStream
import kotlinx.serialization.Serializable

@Serializable
data class DocTableSpec(
    val headers: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList(),
)

@Serializable
data class DocParagraphSpec(
    val text: String = "",
    val heading: Int = 0,
)

@Serializable
data class DocContentSpec(
    val title: String = "",
    val paragraphs: List<DocParagraphSpec> = emptyList(),
    val tables: List<DocTableSpec> = emptyList(),
)

/**
 * Emits a minimal but valid .docx (WordprocessingML) package.
 *
 * Apache POI is heavy and awkward on Android, so the container is written by
 * hand: a ZIP holding only the parts Word needs to open the file. Keeping the
 * writer pure lets the unit tests run it on the JVM and assert on the produced
 * package without an emulator.
 */
internal object DocxWriter {

    fun write(content: DocContentSpec, out: OutputStream) {
        val body = buildBody(content)
        val document = (XML_HEADER +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:body>$body<w:sectPr>" +
            "<w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
            "<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" " +
            "w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/>" +
            "</w:sectPr></w:body></w:document>")

        writeZipText(
            listOf(
                "[Content_Types].xml" to contentTypes(),
                "_rels/.rels" to rootRels(),
                "word/document.xml" to document,
                "word/_rels/document.xml.rels" to documentRels(),
                "word/styles.xml" to styles(),
                "docProps/core.xml" to coreProps(content.title),
                "docProps/app.xml" to appProps(),
            ),
            out,
        )
    }

    private fun buildBody(content: DocContentSpec): String = buildString {
        val title = content.title.trim()
        if (title.isNotEmpty()) append(paragraph(title, heading = 1))
        content.paragraphs.forEach { spec ->
            val text = spec.text.trim()
            if (text.isNotEmpty()) append(paragraph(text, spec.heading))
        }
        content.tables.forEach { table -> append(buildTable(table)) }
    }

    private fun paragraph(text: String, heading: Int): String {
        val style = if (heading in 1..3) "<w:pPr><w:pStyle w:val=\"Heading$heading\"/></w:pPr>" else ""
        return "<w:p>$style<w:r><w:t xml:space=\"preserve\">${xmlEscape(text)}</w:t></w:r></w:p>"
    }

    private fun buildTable(table: DocTableSpec): String {
        val columns = maxOf(table.headers.size, table.rows.maxOfOrNull { it.size } ?: 0)
        if (columns == 0) return ""
        val grid = (0 until columns).joinToString("") { "<w:gridCol w:w=\"2200\"/>" }
        val rows = buildString {
            if (table.headers.isNotEmpty()) {
                append("<w:tr>")
                table.headers.forEach { cell -> append(tableCell(cell, bold = true)) }
                append("</w:tr>")
            }
            table.rows.forEach { row ->
                append("<w:tr>")
                (0 until columns).forEach { index -> append(tableCell(row.getOrNull(index).orEmpty(), false)) }
                append("</w:tr>")
            }
        }
        return "<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/>" +
            "<w:tblBorders>$ALL_BORDERS</w:tblBorders></w:tblPr>" +
            "<w:tblGrid>$grid</w:tblGrid>$rows</w:tbl>"
    }

    private fun tableCell(text: String, bold: Boolean): String {
        val rPr = if (bold) "<w:rPr><w:b/></w:rPr>" else ""
        return "<w:tc><w:tcPr><w:tcW w:w=\"2200\" w:type=\"dxa\"/>" +
            "<w:vAlign w:val=\"center\"/></w:tcPr>" +
            "<w:p><w:r>$rPr<w:t xml:space=\"preserve\">${xmlEscape(text)}</w:t></w:r></w:p></w:tc>"
    }

    private fun contentTypes(): String = XML_HEADER +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/word/document.xml\" " +
        "ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
        "<Override PartName=\"/word/styles.xml\" " +
        "ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
        "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
        "<Override PartName=\"/docProps/app.xml\" " +
        "ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>" +
        "</Types>"

    private fun rootRels(): String = XML_HEADER +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" " +
        "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" " +
        "Target=\"word/document.xml\"/>" +
        "<Relationship Id=\"rId2\" " +
        "Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" " +
        "Target=\"docProps/core.xml\"/>" +
        "<Relationship Id=\"rId3\" " +
        "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" " +
        "Target=\"docProps/app.xml\"/>" +
        "</Relationships>"

    private fun documentRels(): String = XML_HEADER +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" " +
        "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" " +
        "Target=\"styles.xml\"/>" +
        "</Relationships>"

    private fun styles(): String = XML_HEADER +
        "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
        "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\"/>" +
        "<w:sz w:val=\"22\"/></w:rPr></w:rPrDefault></w:docDefaults>" +
        "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style>" +
        headingStyle("Heading1", "heading 1", "0", "36") +
        headingStyle("Heading2", "heading 2", "1", "30") +
        headingStyle("Heading3", "heading 3", "2", "26") +
        "</w:styles>"

    private fun headingStyle(id: String, name: String, level: String, size: String): String =
        "<w:style w:type=\"paragraph\" w:styleId=\"$id\"><w:name w:val=\"$name\"/>" +
            "<w:pPr><w:outlineLvl w:val=\"$level\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"$size\"/></w:rPr></w:style>"

    private fun coreProps(title: String): String = XML_HEADER +
        "<cp:coreProperties " +
        "xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
        "xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
        "<dc:title>${xmlEscape(title)}</dc:title><dc:creator>Pocket Agent</dc:creator></cp:coreProperties>"

    private fun appProps(): String = XML_HEADER +
        "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\">" +
        "<Application>Pocket Agent</Application></Properties>"

    private const val ALL_BORDERS =
        "<w:top w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>" +
            "<w:left w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>" +
            "<w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>" +
            "<w:right w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>" +
            "<w:insideH w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>" +
            "<w:insideV w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>"
}
