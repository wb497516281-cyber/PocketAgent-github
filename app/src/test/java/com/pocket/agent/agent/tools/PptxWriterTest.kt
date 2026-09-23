package com.pocket.agent.agent.tools

import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * POI's XSLF module is impractical on Android, so the deck is written by hand
 * as well. The structure PowerPoint insists on - master, layout, theme, notes
 * master - is what these tests pin down, along with the slide body markup.
 */
class PptxWriterTest {

    @Test
    fun `writes the master layout theme and one slide per entry`() {
        val pkg = packageOf(
            SlideDeckSpec(
                slides = listOf(
                    SlideSpec(title = "第一页", bullets = listOf("要点一", "要点二"), notes = "开场白"),
                    SlideSpec(title = "第二页", bullets = listOf("")),
                ),
            ),
        )
        assertEquals(
            setOf(
                "[Content_Types].xml",
                "_rels/.rels",
                "docProps/core.xml",
                "docProps/app.xml",
                "ppt/presentation.xml",
                "ppt/_rels/presentation.xml.rels",
                "ppt/slideMasters/slideMaster1.xml",
                "ppt/slideMasters/_rels/slideMaster1.xml.rels",
                "ppt/slideLayouts/slideLayout1.xml",
                "ppt/slideLayouts/_rels/slideLayout1.xml.rels",
                "ppt/theme/theme1.xml",
                "ppt/notesMasters/notesMaster1.xml",
                "ppt/notesMasters/_rels/notesMaster1.xml.rels",
                "ppt/slides/slide1.xml",
                "ppt/slides/_rels/slide1.xml.rels",
                "ppt/slides/slide2.xml",
                "ppt/slides/_rels/slide2.xml.rels",
                "ppt/notesSlides/notesSlide1.xml",
                "ppt/notesSlides/_rels/notesSlide1.xml.rels",
            ),
            pkg.names,
        )
    }

    @Test
    fun `a deck without notes emits no notes slides at all`() {
        val pkg = packageOf(SlideDeckSpec(slides = listOf(SlideSpec(title = "无备注", notes = "   "))))
        assertFalse(pkg.names.any { it.startsWith("ppt/notesSlides/") })
        assertTrue(!pkg["[Content_Types].xml"]!!.contains("notesSlide+xml"))
        assertTrue(!pkg["ppt/slides/_rels/slide1.xml.rels"]!!.contains("notesSlide"))
    }

    @Test
    fun `a content slide pairs the title with an accent tab and bullet list`() {
        val pkg = packageOf(
            SlideDeckSpec(
                slides = listOf(SlideSpec(title = "本周进展", bullets = listOf("完成 OCR", "补上单测"))),
            ),
        )
        val slide = pkg["ppt/slides/slide1.xml"]!!
        assertTrue(slide.contains("<a:t>本周进展</a:t>"))
        assertTrue(slide.contains("<a:t>1</a:t>"))
        assertTrue(slide.contains("sz=\"2400\""))
        // Accent tab, rule, title, body, page number - five shapes, no more.
        assertEquals(5, pkg.count("ppt/slides/slide1.xml", "<p:sp>"))
        // One bullet character and one hang indent per bullet, titles get none.
        assertEquals(2, pkg.count("ppt/slides/slide1.xml", "<a:buChar char=\"\u2022\"/>"))
        assertEquals(2, pkg.count("ppt/slides/slide1.xml", "marL=\"342900\" indent=\"-342900\""))
        // Bullets carry the accent color, ink text, and comfortable leading.
        assertTrue(slide.contains("<a:buClr><a:srgbClr val=\"2E5BFF\"/></a:buClr>"))
        assertTrue(slide.contains("<a:lnSpc><a:spcPct val=\"115000\"/></a:lnSpc>"))
        assertTrue(slide.contains("<a:srgbClr val=\"1F2430\"/>"))
    }

    @Test
    fun `a first slide without bullets falls back to a navy cover`() {
        val pkg = packageOf(SlideDeckSpec(slides = listOf(SlideSpec(title = "季度总结"))))
        val slide = pkg["ppt/slides/slide1.xml"]!!
        assertTrue(slide.contains("<a:srgbClr val=\"1B2A5E\"/>"))
        assertTrue(slide.contains("sz=\"4000\""))
        assertTrue(slide.contains("<a:srgbClr val=\"FFFFFF\"/>"))
        assertEquals(0, pkg.count("ppt/slides/slide1.xml", "<a:buChar"))
    }

    @Test
    fun `a cover shows the subtitle under the title`() {
        val pkg = packageOf(
            SlideDeckSpec(
                slides = listOf(
                    SlideSpec(title = "季度总结", subtitle = "2026 年第三季度", layout = "cover"),
                ),
            ),
        )
        val slide = pkg["ppt/slides/slide1.xml"]!!
        assertTrue(slide.contains("<a:t>2026 年第三季度</a:t>"))
        assertTrue(slide.contains("sz=\"1600\""))
        assertTrue(slide.contains("<a:srgbClr val=\"C9D6FF\"/>"))
    }

    @Test
    fun `the layout field picks the slide kind`() {
        val pkg = packageOf(
            SlideDeckSpec(
                slides = listOf(
                    SlideSpec(title = "封面", layout = "cover"),
                    SlideSpec(title = "章节一", layout = "section"),
                    SlideSpec(title = "谢谢观看", layout = "end"),
                ),
            ),
        )
        assertTrue(pkg["ppt/slides/slide1.xml"]!!.contains("sz=\"4000\""))
        val section = pkg["ppt/slides/slide2.xml"]!!
        assertTrue(section.contains("<a:srgbClr val=\"F4F6FB\"/>"))
        assertTrue(section.contains("<a:t>02</a:t>"))
        assertTrue(section.contains("sz=\"6000\""))
        val end = pkg["ppt/slides/slide3.xml"]!!
        assertTrue(end.contains("<a:srgbClr val=\"1B2A5E\"/>"))
        assertTrue(end.contains("<a:t>谢谢观看</a:t>"))
        assertTrue(end.contains("sz=\"3600\""))
    }

    @Test
    fun `crowded decks and long points shrink the body text to fit the slide`() {
        val pkg = packageOf(
            SlideDeckSpec(
                slides = listOf(
                    SlideSpec(title = "长要点", bullets = listOf("要点".repeat(25), "短一点")),
                ),
            ),
        )
        assertTrue(pkg["ppt/slides/slide1.xml"]!!.contains("sz=\"1400\""))
    }

    @Test
    fun `every emitted part is well formed xml`() {
        // Substring assertions cannot see broken nesting; a real parser can.
        val pkg = packageOf(
            SlideDeckSpec(
                slides = listOf(
                    SlideSpec(title = "封面", subtitle = "副标题", layout = "cover", notes = "备注"),
                    SlideSpec(title = "章节一", layout = "section"),
                    SlideSpec(title = "正文页", bullets = listOf("要点一", "要点二"), layout = "content"),
                    SlideSpec(title = "谢谢观看", layout = "end"),
                ),
            ),
        )
        val factory = DocumentBuilderFactory.newInstance()
        pkg.names.filter { it.endsWith(".xml") || it.endsWith(".rels") }.forEach { name ->
            val text = pkg[name]!!
            val error = runCatching {
                factory.newDocumentBuilder()
                    .parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
            }.exceptionOrNull()
            assertTrue("$name 不是良构的 XML：${error?.message}", error == null)
        }
    }

    @Test
    fun `speaker notes ride along only on the slide that has them`() {
        val pkg = packageOf(
            SlideDeckSpec(
                slides = listOf(
                    SlideSpec(title = "第一页", notes = "开场白"),
                    SlideSpec(title = "第二页"),
                ),
            ),
        )
        assertTrue(pkg["ppt/notesSlides/notesSlide1.xml"]!!.contains("<a:t>开场白</a:t>"))
        assertFalse(pkg.names.contains("ppt/notesSlides/notesSlide2.xml"))
        assertTrue(pkg["ppt/slides/_rels/slide1.xml.rels"]!!.contains("notesSlide"))
        assertFalse(pkg["ppt/slides/_rels/slide2.xml.rels"]!!.contains("notesSlide"))
    }

    @Test
    fun `a slide without bullets keeps an empty body paragraph`() {
        // An spTree with an empty txBody is what makes the shape survive
        // round tripping through PowerPoint instead of being dropped.
        // A bare first slide would now become a cover, so the layout is stated.
        val pkg = packageOf(
            SlideDeckSpec(slides = listOf(SlideSpec(title = "只有标题", layout = "content"))),
        )
        val slide = pkg["ppt/slides/slide1.xml"]!!
        assertTrue(slide.contains("<a:t></a:t>"))
        assertEquals(0, pkg.count("ppt/slides/slide1.xml", "<a:buChar"))
    }

    @Test
    fun `slide text is xml escaped`() {
        val pkg = packageOf(
            SlideDeckSpec(
                slides = listOf(SlideSpec(title = "A & B <c>", bullets = listOf("1 < 2"))),
            ),
        )
        val slide = pkg["ppt/slides/slide1.xml"]!!
        assertTrue(slide.contains("A &amp; B &lt;c&gt;"))
        assertTrue(slide.contains("1 &lt; 2"))
        assertFalse(slide.contains("<c>"))
    }

    @Test
    fun `the presentation part references every slide in order`() {
        val pkg = packageOf(
            SlideDeckSpec(
                slides = listOf(
                    SlideSpec(title = "一"),
                    SlideSpec(title = "二"),
                    SlideSpec(title = "三"),
                ),
            ),
        )
        val presentation = pkg["ppt/presentation.xml"]!!
        assertTrue(presentation.contains("<p:sldId id=\"256\" r:id=\"rId4\"/>"))
        assertTrue(presentation.contains("<p:sldId id=\"258\" r:id=\"rId6\"/>"))
        assertTrue(pkg["ppt/_rels/presentation.xml.rels"]!!.contains("Target=\"slides/slide3.xml\""))
        assertTrue(pkg["docProps/app.xml"]!!.contains("<Slides>3</Slides>"))
        assertTrue(pkg["ppt/theme/theme1.xml"]!!.contains("<a:accent1>"))
    }

    @Test
    fun `shapes sit inside the spTree or every slide renders blank`() {
        // Regression: shapes were appended after </p:spTree>, which still
        // parses, so substring tests passed while PowerPoint showed blanks.
        val pkg = packageOf(
            SlideDeckSpec(
                slides = listOf(SlideSpec(title = "标题", bullets = listOf("要点"), notes = "备注")),
            ),
        )
        listOf(
            "ppt/slides/slide1.xml",
            "ppt/notesSlides/notesSlide1.xml",
            "ppt/notesMasters/notesMaster1.xml",
        ).forEach { part ->
            val xml = pkg[part]!!
            val treeStart = xml.indexOf("<p:spTree>")
            val treeEnd = xml.indexOf("</p:spTree>")
            assertTrue("$part 缺少 spTree", treeStart >= 0 && treeEnd > treeStart)
            val firstShape = xml.indexOf("<p:sp>")
            assertTrue("$part 没有形状", firstShape >= 0)
            assertTrue("$part 的形状落在 spTree 之外", firstShape in (treeStart + 1) until treeEnd)
            val lastShapeEnd = maxOf(xml.lastIndexOf("</p:sp>"), xml.lastIndexOf("</p:pic>"))
            assertTrue("$part 的形状没有闭合在 spTree 内", lastShapeEnd < treeEnd)
        }
        // The slide master and layout ship an intentionally empty tree.
        listOf(
            "ppt/slideMasters/slideMaster1.xml",
            "ppt/slideLayouts/slideLayout1.xml",
        ).forEach { part ->
            val xml = pkg[part]!!
            val treeEnd = xml.indexOf("</p:spTree>")
            assertTrue("$part 缺少 spTree", treeEnd >= 0)
            assertFalse("$part 的树外出现形状", xml.substring(treeEnd).contains("<p:sp>"))
        }
    }

    @Test
    fun `a picture becomes a pic shape wired to a media part`() {
        val pkg = packageOf(
            SlideDeckSpec(
                slides = listOf(SlideSpec(title = "图片页", bullets = listOf("要点"), layout = "content")),
            ),
            mapOf(0 to listOf(photo("https://example.com/a.png", 1600, 900, caption = "数据图"))),
        )
        val slide = pkg["ppt/slides/slide1.xml"]!!
        // The frame renders the media through its blipFill relationship and
        // stretches it whole instead of cropping any of it away.
        assertTrue(slide.contains("<p:pic>"))
        assertTrue(slide.contains("<a:blip r:embed=\"rId2\"/>"))
        assertTrue(slide.contains("<a:stretch><a:fillRect/></a:stretch>"))
        val treeEnd = slide.indexOf("</p:spTree>")
        assertTrue("图片落在 spTree 之外", slide.indexOf("<p:pic>") in 1 until treeEnd)
        assertTrue("图片没有闭合在 spTree 内", slide.lastIndexOf("</p:pic>") < treeEnd)
        // The caption rides under the picture.
        assertTrue(slide.contains("<a:t>数据图</a:t>"))
        // The rels part and the media entry name the same picture.
        val rels = pkg["ppt/slides/_rels/slide1.xml.rels"]!!
        assertTrue(
            rels.contains(
                "<Relationship Id=\"rId2\" Type=\"" +
                    "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" " +
                    "Target=\"../media/image1.png\"/>",
            ),
        )
        assertTrue(pkg.names.contains("ppt/media/image1.png"))
        assertTrue(
            pkg["[Content_Types].xml"]!!
                .contains("<Default Extension=\"png\" ContentType=\"image/png\"/>"),
        )
    }

    @Test
    fun `pictures take relationship ids after the layout and notes rels`() {
        // rId1 belongs to the layout and rId2 to the notes slide, so the
        // picture starts one later or it would overwrite the notes rel.
        val pkg = packageOf(
            SlideDeckSpec(slides = listOf(SlideSpec(title = "正文", notes = "备注", layout = "content"))),
            mapOf(0 to listOf(photo("https://example.com/a.png", 1600, 900))),
        )
        assertTrue(pkg["ppt/slides/slide1.xml"]!!.contains("<a:blip r:embed=\"rId3\"/>"))
        assertTrue(pkg["ppt/slides/_rels/slide1.xml.rels"]!!.contains("Id=\"rId3\""))
    }

    @Test
    fun `repeated sources share one media part`() {
        val url = "https://example.com/a.png"
        val pkg = packageOf(
            SlideDeckSpec(
                slides = listOf(
                    SlideSpec(title = "第一页", layout = "content"),
                    SlideSpec(title = "第二页", layout = "content"),
                ),
            ),
            mapOf(
                0 to listOf(photo(url, 1600, 900)),
                1 to listOf(photo(url, 1600, 900)),
            ),
        )
        assertEquals(1, pkg.names.count { it.startsWith("ppt/media/") })
        assertTrue(pkg.names.contains("ppt/media/image1.png"))
        assertTrue(pkg["ppt/slides/slide2.xml"]!!.contains("<a:blip r:embed=\"rId2\"/>"))
        assertTrue(
            pkg["ppt/slides/_rels/slide2.xml.rels"]!!.contains("Target=\"../media/image1.png\""),
        )
    }

    @Test
    fun `stacked pictures keep unique shape ids`() {
        val pkg = packageOf(
            SlideDeckSpec(slides = listOf(SlideSpec(title = "图片墙", layout = "content"))),
            mapOf(
                0 to listOf(
                    photo("https://example.com/a.png", 1600, 900),
                    photo("https://example.com/b.png", 900, 1600),
                ),
            ),
        )
        val slide = pkg["ppt/slides/slide1.xml"]!!
        assertEquals(2, pkg.count("ppt/slides/slide1.xml", "<p:pic>"))
        assertTrue(slide.contains("<a:blip r:embed=\"rId2\"/>"))
        assertTrue(slide.contains("<a:blip r:embed=\"rId3\"/>"))
        // PowerPoint silently drops shapes that reuse an id.
        val ids = Regex("<p:cNvPr id=\"(\\d+)\"").findAll(slide)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `each picture keeps its aspect ratio inside its slot`() {
        val wide = packageOf(
            SlideDeckSpec(slides = listOf(SlideSpec(title = "宽图", layout = "content"))),
            mapOf(0 to listOf(photo("https://example.com/wide.png", 1600, 900))),
        )["ppt/slides/slide1.xml"]!!
        // A wide picture in a tall slot fills the width; the height follows
        // the ratio instead of the slot, so the picture is letterboxed.
        val (wideWidth, wideHeight) = pictureExtent(wide)
        assertEquals(5105400, wideWidth)
        assertTrue("宽图高度应随比例收缩", wideHeight in 2_871_000..2_872_500)

        val tall = packageOf(
            SlideDeckSpec(slides = listOf(SlideSpec(title = "竖图", layout = "content"))),
            mapOf(0 to listOf(photo("https://example.com/tall.png", 900, 1600))),
        )["ppt/slides/slide1.xml"]!!
        // A tall picture fills the height and lets the width shrink back.
        val (tallWidth, tallHeight) = pictureExtent(tall)
        assertEquals(4937760, tallHeight)
        assertTrue("竖图宽度应随比例收缩", tallWidth in 2_777_000..2_778_000)
    }

    @Test
    fun `a cover splits its text beside the pictures`() {
        val pkg = packageOf(
            SlideDeckSpec(slides = listOf(SlideSpec(title = "产品发布", layout = "cover"))),
            mapOf(0 to listOf(photo("https://example.com/hero.png", 1600, 900))),
        )
        val slide = pkg["ppt/slides/slide1.xml"]!!
        // The title keeps the left half at a readable size rather than being
        // stretched across a slide the picture also needs.
        assertTrue(slide.contains("<a:ext cx=\"5303520\" cy=\"1554480\"/>"))
        assertTrue(slide.contains("sz=\"3200\""))
        // The picture column sits on the right, clear of the title.
        val (x, y) = pictureOffset(slide)
        assertEquals(6858000, x)
        assertTrue("封面图片应占右半", x > 6_000_000)
        assertTrue("封面图片应落在右栏内", y in 2_057_400..4_800_600)
    }

    /** A resolved picture whose bytes never have to decode; the writer only fits them. */
    private fun photo(
        source: String,
        width: Int,
        height: Int,
        caption: String = "",
    ): ResolvedImage = ResolvedImage(
        source = source,
        caption = caption,
        format = ImageFormat.PNG,
        bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
        width = width,
        height = height,
    )

    /** The fitted box of the first `<p:pic>` in a slide part. */
    private fun pictureExtent(slide: String): Pair<Int, Int> {
        val pic = slide.substring(slide.indexOf("<p:pic>"), slide.indexOf("</p:pic>"))
        val match = Regex("<a:ext cx=\"(\\d+)\" cy=\"(\\d+)\"/>").find(pic)!!
        return match.groupValues[1].toInt() to match.groupValues[2].toInt()
    }

    /** Where the first `<p:pic>` in a slide part was placed. */
    private fun pictureOffset(slide: String): Pair<Int, Int> {
        val pic = slide.substring(slide.indexOf("<p:pic>"), slide.indexOf("</p:pic>"))
        val match = Regex("<a:off x=\"(\\d+)\" y=\"(\\d+)\"/>").find(pic)!!
        return match.groupValues[1].toInt() to match.groupValues[2].toInt()
    }

    private fun packageOf(
        deck: SlideDeckSpec,
        pictures: Map<Int, List<ResolvedImage>> = emptyMap(),
    ): ZipPackage {
        val stream = ByteArrayOutputStream()
        PptxWriter.write(deck, pictures, stream)
        return ZipPackage.from(stream.toByteArray())
    }
}
