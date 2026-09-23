package com.pocket.agent.agent.tools

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The document tools live and die by their JSON arguments, so the schema the
 * model sees and the lenient decoders behind it are both pinned down here.
 * Execution needs a real SAF directory, which only an emulator can provide.
 */
class DocumentToolsTest {

    @Test
    fun `every document tool advertises its required arguments`() {
        val expectations = listOf(
            CreatePdfTool() to listOf("file_name", "markdown_content"),
            CreateWordTool() to listOf("file_name", "content_json"),
            CreatePptxTool(OkHttpClient()) to listOf("file_name", "slides_json"),
        )
        expectations.forEach { (tool, required) ->
            assertTrue(tool.spec.description.isNotBlank())
            val properties = tool.spec.parameters["properties"]!!.jsonObject
            required.forEach { name ->
                assertTrue("${tool.spec.name} is missing $name", properties.containsKey(name))
                assertEquals(
                    "string",
                    properties[name]!!.jsonObject["type"]!!.jsonPrimitive.content,
                )
            }
            assertEquals(required, tool.spec.parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        }
    }

    @Test
    fun `file names get the extension of their format exactly once`() {
        assertEquals("周报.pdf", withExtension("周报", "pdf"))
        assertEquals("周报.pdf", withExtension("  周报  ", "pdf"))
        // A name that already carries the extension is left alone.
        assertEquals("周报.pdf", withExtension("周报.pdf", "pdf"))
        assertEquals("周报.PDF", withExtension("周报.PDF", "pdf"))
        // A foreign extension is kept; only the target one is skipped.
        assertEquals("周报.pdf.pptx", withExtension("周报.pdf", "pptx"))
    }

    @Test
    fun `a word body decodes into paragraphs and tables`() {
        val content = decodeDocContent(
            """{"title":"周报","paragraphs":[{"text":"本周","heading":2}],"tables":[{"headers":["任务"],"rows":[["单测"]]}]}""",
        )!!
        assertEquals("周报", content.title)
        assertEquals("本周", content.paragraphs.single().text)
        assertEquals(2, content.paragraphs.single().heading)
        assertEquals(listOf("任务"), content.tables.single().headers)
        assertEquals(listOf(listOf("单测")), content.tables.single().rows)
    }

    @Test
    fun `a fenced word body is still accepted`() {
        val raw = """
            ```json
            {"title":"周报","paragraphs":[{"text":"本周"}]}
            ```
        """.trimIndent()
        assertEquals("周报", decodeDocContent(raw)!!.title)
    }

    @Test
    fun `missing word fields fall back to sensible defaults`() {
        val content = decodeDocContent("""{"title":"只有标题"}""")!!
        assertEquals("只有标题", content.title)
        assertTrue(content.paragraphs.isEmpty())
        assertTrue(content.tables.isEmpty())
    }

    @Test
    fun `unreadable word bodies decode to null so the tool fails cleanly`() {
        assertNull(decodeDocContent("这不是 JSON"))
        assertNull(decodeDocContent(""))
        assertNull(decodeDocContent("```\n```"))
    }

    @Test
    fun `a deck decodes into slides with optional notes`() {
        val deck = decodeSlideDeck(
            """{"slides":[{"title":"第一页","bullets":["要点"],"notes":"开场"},{"title":"第二页"}]}""",
        )!!
        assertEquals(2, deck.slides.size)
        assertEquals("第一页", deck.slides[0].title)
        assertEquals(listOf("要点"), deck.slides[0].bullets)
        assertEquals("开场", deck.slides[0].notes)
        // A deck that mentions pictures still decodes with none resolved.
        assertEquals(0, deck.slides[0].images.size)
        // Notes default to empty rather than breaking the slide.
        assertEquals("", deck.slides[1].notes)
    }

    @Test
    fun `a slide decodes its pictures with optional captions`() {
        val deck = decodeSlideDeck(
            """{"slides":[{"title":"正文","images":[{"url":"https://example.com/a.png"},{"url":"https://example.com/b.png","caption":"图注"}]}]}""",
        )!!
        val images = deck.slides.single().images
        assertEquals(2, images.size)
        assertEquals("https://example.com/a.png", images[0].url)
        assertEquals("", images[0].caption)
        assertEquals("图注", images[1].caption)
    }

    @Test
    fun `a picture may point at an uploaded photo instead of a url`() {
        val deck = decodeSlideDeck(
            """{"slides":[{"title":"正文","images":[{"attachment":1}]}]}""",
        )!!
        assertEquals(1, deck.slides.single().images.single().attachment)
    }

    @Test
    fun `unreadable decks decode to null so the tool fails cleanly`() {
        assertNull(decodeSlideDeck("乱码"))
        assertNull(decodeSlideDeck(""))
    }

    @Test
    fun `an empty deck decodes but carries nothing to render`() {
        assertTrue(decodeSlideDeck("""{"slides":[]}""")!!.slides.isEmpty())
    }
}
