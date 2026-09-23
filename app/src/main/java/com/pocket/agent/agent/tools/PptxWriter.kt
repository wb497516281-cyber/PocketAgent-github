package com.pocket.agent.agent.tools

import java.io.OutputStream
import kotlinx.serialization.Serializable

/**
 * One picture a slide should show.
 *
 * [url] is an http address the model found on the web; [attachment] is the
 * 1-based index of a photo the user sent in this conversation. One of the two
 * is resolved to bytes by the tool before the writer ever runs.
 */
@Serializable
data class SlideImage(
    val url: String = "",
    val attachment: Int = 0,
    /** Line printed under the picture; optional. */
    val caption: String = "",
)

/** One slide of a generated deck. */
@Serializable
data class SlideSpec(
    val title: String = "",
    val bullets: List<String> = emptyList(),
    val notes: String = "",
    /** Cover and closing slides show it under the title; optional. */
    val subtitle: String = "",
    /** One of "cover", "section", "content", "end"; inferred when blank. */
    val layout: String = "",
    /** Pictures to place beside the text; at most three per slide. */
    val images: List<SlideImage> = emptyList(),
)

/** The deck payload the `create_ppt` tool expects. */
@Serializable
data class SlideDeckSpec(
    val slides: List<SlideSpec> = emptyList(),
)

/**
 * A picture the writer can embed: bytes already in hand.
 *
 * The tool downloads or unwraps attachments first, so [PptxWriter] stays pure
 * and its tests never touch the network or a bitmap factory.
 */
internal data class ResolvedImage(
    /** The url or "attachment:N" the picture came from; the media library key. */
    val source: String,
    val caption: String = "",
    val format: ImageFormat,
    val bytes: ByteArray,
    val width: Int = 0,
    val height: Int = 0,
) {
    /** Width over height; a readable default when the header would not give. */
    val ratio: Float
        get() = if (width > 0 && height > 0) width.toFloat() / height.toFloat() else FALLBACK_RATIO
}

/** Shape assumed for a picture whose dimensions cannot be read. */
private const val FALLBACK_RATIO = 4f / 3f

/**
 * Emits a minimal but valid .pptx (PresentationML) package.
 *
 * Apache POI's XSLF module is impractical on Android, so the container is
 * written by hand: a ZIP with the parts PowerPoint needs - a slide master, one
 * blank layout, the theme, and one slide (plus its notes slide when the model
 * supplied speaker notes) per entry. Every slide picks one of four layouts -
 * cover, section divider, content, closing - so a deck reads like a designed
 * one instead of a stack of identical bullet pages. The writer is pure, so the
 * unit tests assert on the produced package without an emulator.
 */
internal object PptxWriter {

    /**
     * Writes [deck] to [out].
     *
     * [pictures] maps a slide index to the pictures that slide shows; the tool
     * resolves them to bytes first so the writer knows nothing about the
     * network. Repeated sources share one media part.
     */
    fun write(deck: SlideDeckSpec, pictures: Map<Int, List<ResolvedImage>>, out: OutputStream) {
        val slides = deck.slides
        val media = MediaLibrary()
        slides.indices.forEach { index ->
            pictures[index]?.forEach { media.register(it) }
        }
        val entries = mutableListOf<Pair<String, ByteArray>>()

        fun xml(name: String, body: String) {
            entries += name to body.toByteArray(Charsets.UTF_8)
        }

        xml("[Content_Types].xml", contentTypes(slides, media.formats))
        xml("_rels/.rels", rootRels())
        xml("docProps/core.xml", coreProps())
        xml("docProps/app.xml", appProps(slides.size))
        xml("ppt/presentation.xml", presentation(slides))
        xml("ppt/_rels/presentation.xml.rels", presentationRels(slides))
        xml("ppt/slideMasters/slideMaster1.xml", slideMaster())
        xml("ppt/slideMasters/_rels/slideMaster1.xml.rels", slideMasterRels())
        xml("ppt/slideLayouts/slideLayout1.xml", slideLayout())
        xml("ppt/slideLayouts/_rels/slideLayout1.xml.rels", slideLayoutRels())
        xml("ppt/theme/theme1.xml", theme())
        xml("ppt/notesMasters/notesMaster1.xml", notesMaster())
        xml("ppt/notesMasters/_rels/notesMaster1.xml.rels", notesMasterRels())
        slides.forEachIndexed { index, spec ->
            val number = index + 1
            val slidePictures = pictures[index] ?: emptyList()
            xml("ppt/slides/slide$number.xml", slide(number, slides.size, spec, slidePictures))
            xml(
                "ppt/slides/_rels/slide$number.xml.rels",
                slideRels(number, spec, slidePictures, media),
            )
            if (spec.notes.isNotBlank()) {
                xml("ppt/notesSlides/notesSlide$number.xml", notesSlide(number, spec))
                xml("ppt/notesSlides/_rels/notesSlide$number.xml.rels", notesSlideRels())
            }
        }
        media.entries.forEach { (name, bytes) -> entries += name to bytes }
        writeZip(entries, out)
    }

    private fun presentation(slides: List<SlideSpec>): String = XML_HEADER +
        "<p:presentation $NS saveSubsetFonts=\"1\">" +
        "<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>" +
        "<p:notesMasterIdLst><p:notesMasterId r:id=\"rId2\"/></p:notesMasterIdLst>" +
        "<p:sldIdLst>" +
        slides.indices.joinToString("") { index ->
            "<p:sldId id=\"${256 + index}\" r:id=\"rId${4 + index}\"/>"
        } +
        "</p:sldIdLst>" +
        "<p:sldSz cx=\"12192000\" cy=\"6858000\"/>" +
        "<p:notesSz cx=\"6858000\" cy=\"9144000\"/>" +
        "</p:presentation>"

    private fun presentationRels(slides: List<SlideSpec>): String = XML_HEADER +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"$RT/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"$RT/notesMaster\" Target=\"notesMasters/notesMaster1.xml\"/>" +
        "<Relationship Id=\"rId3\" Type=\"$RT/theme\" Target=\"theme/theme1.xml\"/>" +
        slides.indices.joinToString("") { index ->
            "<Relationship Id=\"rId${4 + index}\" Type=\"$RT/slide\" Target=\"slides/slide${index + 1}.xml\"/>"
        } +
        "</Relationships>"

    private fun slideMaster(): String = XML_HEADER +
        "<p:sldMaster $NS>" +
        "<p:cSld>" +
        "<p:bg><p:bgPr><a:solidFill><a:schemeClr val=\"bg1\"/></a:solidFill>" +
        "<a:effectLst/></p:bgPr></p:bg>" +
        spTree() +
        "</p:cSld>" +
        "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" " +
        "accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" " +
        "accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>" +
        "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst>" +
        "<p:txStyles>" +
        "<p:titleStyle><a:lvl1pPr algn=\"ctr\"><a:defRPr sz=\"3200\" b=\"1\"/></a:lvl1pPr></p:titleStyle>" +
        "<p:bodyStyle><a:lvl1pPr marL=\"342900\" indent=\"-342900\" algn=\"l\">" +
        "<a:buChar char=\"•\"/><a:defRPr sz=\"1800\"/></a:lvl1pPr></p:bodyStyle>" +
        "<p:otherStyle><a:lvl1pPr><a:defRPr sz=\"1800\"/></a:lvl1pPr></p:otherStyle>" +
        "</p:txStyles>" +
        "</p:sldMaster>"

    private fun slideMasterRels(): String = XML_HEADER +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"$RT/slideLayout\" " +
        "Target=\"../slideLayouts/slideLayout1.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"$RT/theme\" Target=\"../theme/theme1.xml\"/>" +
        "</Relationships>"

    private fun slideLayout(): String = XML_HEADER +
        "<p:sldLayout $NS type=\"blank\" preserve=\"1\">" +
        "<p:cSld name=\"空白\">${spTree()}</p:cSld>" +
        "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>" +
        "</p:sldLayout>"

    private fun slideLayoutRels(): String = XML_HEADER +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"$RT/slideMaster\" " +
        "Target=\"../slideMasters/slideMaster1.xml\"/>" +
        "</Relationships>"

    private fun slide(
        number: Int,
        slideCount: Int,
        spec: SlideSpec,
        pictures: List<ResolvedImage>,
    ): String = XML_HEADER +
        "<p:sld $NS>" +
        "<p:cSld>" +
        spTree(shapesFor(number, slideCount, spec, pictures)) +
        "</p:cSld>" +
        "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>" +
        "</p:sld>"

    /** The slide as a shape list, laid out by its kind. */
    private fun shapesFor(
        number: Int,
        slideCount: Int,
        spec: SlideSpec,
        pictures: List<ResolvedImage>,
    ): List<String> {
        val ids = IdSequence()
        val relIds = pictureRelIds(spec.notes.isNotBlank(), pictures.size)
        return when (kindOf(spec, number, slideCount)) {
            SlideKind.COVER -> coverShapes(spec, ids, pictures, relIds)
            SlideKind.SECTION -> sectionShapes(number, spec, ids, pictures, relIds)
            SlideKind.END -> endShapes(spec, ids, pictures, relIds)
            SlideKind.CONTENT -> contentShapes(number, spec, ids, pictures, relIds)
        }
    }

    /**
     * Relationship ids a slide hands to its pictures.
     *
     * rId1 belongs to the layout and rId2 to the notes slide when the model
     * supplied speaker notes, so the pictures take the ids after them. Both
     * the shapes and the rels part derive from here and cannot drift apart.
     */
    private fun pictureRelIds(hasNotes: Boolean, pictureCount: Int): List<String> =
        List(pictureCount) { index -> "rId${(if (hasNotes) 3 else 2) + index}" }

    /** The four visual treatments a generated deck can use. */
    private enum class SlideKind { COVER, SECTION, END, CONTENT }

    /**
     * Picks the layout for [spec].
     *
     * The model may declare one - "cover", "section", "content", "end", or the
     * Chinese equivalents. When it does not, the deck is read the way a person
     * reads one: a first slide without bullets is the cover, a last one is the
     * closing slide, and everything between carries content.
     */
    private fun kindOf(spec: SlideSpec, number: Int, slideCount: Int): SlideKind =
        when (normalizeLayout(spec.layout)) {
            "cover" -> SlideKind.COVER
            "section" -> SlideKind.SECTION
            "end" -> SlideKind.END
            "content" -> SlideKind.CONTENT
            else -> inferredKind(spec, number, slideCount)
        }

    /** Folds the spellings models actually emit onto the four kinds. */
    private fun normalizeLayout(raw: String): String = when (raw.trim().lowercase()) {
        "cover", "封面", "标题页", "title", "title-slide" -> "cover"
        "section", "章节", "目录", "过渡", "过渡页", "divider" -> "section"
        "end", "结束", "结束页", "结尾", "谢谢", "thanks", "thank-you", "closing" -> "end"
        "content", "正文", "内容", "body" -> "content"
        else -> ""
    }

    private fun inferredKind(spec: SlideSpec, number: Int, slideCount: Int): SlideKind {
        if (spec.bullets.any { it.isNotBlank() }) return SlideKind.CONTENT
        if (CLOSING_TITLES.any { spec.title.contains(it, ignoreCase = true) }) {
            return SlideKind.END
        }
        if (number == 1) return SlideKind.COVER
        if (slideCount > 1 && number == slideCount) return SlideKind.END
        return SlideKind.CONTENT
    }

    /**
     * Full-bleed navy cover: a short accent bar, the oversized title, and an
     * optional subtitle underneath.
     */
    private fun coverShapes(
        spec: SlideSpec,
        ids: IdSequence,
        pictures: List<ResolvedImage>,
        relIds: List<String>,
    ): List<String> {
        val declared = spec.title.trim()
        val title = declared.ifEmpty { firstBullet(spec).ifEmpty { "演示文稿" } }
        // A borrowed title leaves no room for a subtitle to read as one.
        val subtitle = if (declared.isEmpty()) "" else spec.subtitle.trim()
        // Pictures push the title into a left column so it stays readable
        // next to them instead of fighting for the same space.
        val split = pictures.isNotEmpty()
        val column = if (split) "5303520" else "9997440"
        val titleSize = when {
            title.length > 16 || split -> 3200
            else -> 4000
        }
        return listOfNotNull(
            rect(ids.next(), "背景", "0,0", SLIDE_SIZE, NAVY),
            rect(
                ids.next(),
                "装饰条",
                if (split) "1097280,1828800" else "5455920,2560320",
                "1280160,68580",
                ACCENT,
            ),
            shape(
                id = ids.next(),
                name = "封面标题",
                offset = if (split) "1097280,2377440" else "1097280,2743200",
                extent = "$column,1554480",
                anchor = "ctr",
                paragraphs = listOf(
                    paragraph(
                        text = xmlEscape(title),
                        size = titleSize,
                        bold = true,
                        color = WHITE,
                        align = if (split) "l" else "ctr",
                    ),
                ),
            ),
            subtitle.takeIf { it.isNotEmpty() }?.let {
                shape(
                    id = ids.next(),
                    name = "封面副标题",
                    offset = "1097280,4389120",
                    extent = "$column,640080",
                    paragraphs = listOf(
                        paragraph(
                            text = xmlEscape(it),
                            size = 1600,
                            color = MIST,
                            align = if (split) "l" else "ctr",
                        ),
                    ),
                )
            },
        ) + pictureColumn(pictures, ids, relIds, 6858000, 2057400, 4618800, 2743200)
    }

    /**
     * Divider between chapters: pale panel, oversized section number on the
     * left, a rule, then the chapter title.
     */
    private fun sectionShapes(
        number: Int,
        spec: SlideSpec,
        ids: IdSequence,
        pictures: List<ResolvedImage>,
        relIds: List<String>,
    ): List<String> {
        // The chapter title stops short of the picture column.
        val titleExtent = if (pictures.isEmpty()) "7315200,1828800" else "2743200,1828800"
        return listOf(
            rect(ids.next(), "背景", "0,0", SLIDE_SIZE, PANEL),
            shape(
                id = ids.next(),
                name = "章节号",
                offset = "1097280,1828800",
                extent = "1828800,1828800",
                anchor = "ctr",
                paragraphs = listOf(
                    paragraph(
                        text = "%02d".format(number),
                        size = 6000,
                        bold = true,
                        color = ACCENT,
                        align = "l",
                    ),
                ),
            ),
            rect(ids.next(), "分隔条", "3200400,1828800", "68580,1828800", ACCENT),
            shape(
                id = ids.next(),
                name = "章节标题",
                offset = "3657600,1828800",
                extent = titleExtent,
                anchor = "ctr",
                paragraphs = listOf(
                    paragraph(
                        text = xmlEscape(spec.title.trim().ifEmpty { firstBullet(spec) }),
                        size = 2800,
                        bold = true,
                        color = INK,
                        align = "l",
                    ),
                ),
            ),
        ) + pictureColumn(pictures, ids, relIds, 6858000, 1828800, 4618800, 3200400)
    }

    /** Closing slide: navy, centered thanks, optional subtitle. */
    private fun endShapes(
        spec: SlideSpec,
        ids: IdSequence,
        pictures: List<ResolvedImage>,
        relIds: List<String>,
    ): List<String> {
        val title = spec.title.trim().ifEmpty { "谢谢观看" }
        val subtitle = spec.subtitle.trim()
        val split = pictures.isNotEmpty()
        val column = if (split) "5303520" else "9997440"
        val align = if (split) "l" else "ctr"
        return listOfNotNull(
            rect(ids.next(), "背景", "0,0", SLIDE_SIZE, NAVY),
            rect(ids.next(), "装饰条", "5455920,2560320", "1280160,68580", ACCENT),
            shape(
                id = ids.next(),
                name = "结束标题",
                offset = "1097280,2743200",
                extent = "$column,1554480",
                anchor = "ctr",
                paragraphs = listOf(
                    paragraph(
                        text = xmlEscape(title),
                        size = if (title.length > 16) 3200 else 3600,
                        bold = true,
                        color = WHITE,
                        align = align,
                    ),
                ),
            ),
            subtitle.takeIf { it.isNotEmpty() }?.let {
                shape(
                    id = ids.next(),
                    name = "结束副标题",
                    offset = "1097280,4389120",
                    extent = "$column,640080",
                    paragraphs = listOf(
                        paragraph(text = xmlEscape(it), size = 1600, color = MIST, align = align),
                    ),
                )
            },
        ) + pictureColumn(pictures, ids, relIds, 6858000, 2057400, 4618800, 2743200)
    }

    /**
     * Standard content slide: an accent tab beside the title, a hairline rule
     * under it, the bullet body, and a page number in the corner.
     */
    private fun contentShapes(
        number: Int,
        spec: SlideSpec,
        ids: IdSequence,
        pictures: List<ResolvedImage>,
        relIds: List<String>,
    ): List<String> {
        val title = spec.title.trim()
        val points = spec.bullets.map { it.trim() }.filter { it.isNotEmpty() }
        val split = pictures.isNotEmpty()
        // Crowded decks and long points both shrink the body first.
        // A narrower column beside pictures needs the same treatment, or the
        // last lines run off the slide.
        val bodySize = when {
            split && (points.size > 4 || points.any { it.length > 24 }) -> 1400
            points.size > 6 || points.any { it.length > 48 } -> 1400
            else -> BODY_SIZE
        }
        // Text keeps the left half; the pictures take the right half.
        val bodyExtent = if (split) "5257800,4800600" else "10972800,4800600"
        val body = if (points.isEmpty()) {
            listOf(paragraph(text = "", size = bodySize))
        } else {
            points.map { point ->
                paragraph(
                    text = xmlEscape(point),
                    size = bodySize,
                    color = INK,
                    bullet = true,
                    bulletColor = ACCENT,
                    lineSpacing = 115000,
                    spaceBefore = 700,
                )
            }
        }
        return listOf(
            rect(ids.next(), "标题装饰", "548640,274638", "68580,594360", ACCENT),
            rect(ids.next(), "分隔线", "548640,822960", "11094720,19050", RULE),
            shape(
                id = ids.next(),
                name = "标题",
                offset = "731520,237744",
                extent = "10896720,548640",
                anchor = "ctr",
                paragraphs = listOf(
                    paragraph(
                        text = xmlEscape(title),
                        size = if (title.length > 20) 2000 else 2400,
                        bold = true,
                        color = INK,
                        align = "l",
                    ),
                ),
            ),
            shape(
                id = ids.next(),
                name = "内容",
                offset = "685800,1005840",
                extent = bodyExtent,
                anchor = "t",
                paragraphs = body,
            ),
            shape(
                id = ids.next(),
                name = "页码",
                offset = "10515600,6400800",
                extent = "1097280,320040",
                paragraphs = listOf(
                    paragraph(text = number.toString(), size = 1000, color = MUTED, align = "r"),
                ),
            ),
        ) + pictureColumn(pictures, ids, relIds, 6400800, 1005840, 5105400, 4937760)
    }

    /**
     * Stacks [pictures] down one column of a slide.
     *
     * Each picture keeps the aspect ratio it arrived with and is centred
     * inside its slot, with an optional caption in the sliver underneath.
     * Empty when there is nothing to show, so callers append it blindly.
     */
    private fun pictureColumn(
        pictures: List<ResolvedImage>,
        ids: IdSequence,
        relIds: List<String>,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): List<String> {
        if (pictures.isEmpty()) return emptyList()
        val gap = 274320
        val slotHeight = (height - gap * (pictures.size - 1)) / pictures.size
        return pictures.mapIndexed { index, image ->
            val slotTop = y + (slotHeight + gap) * index
            val caption = image.caption.trim()
            val pictureHeight = if (caption.isEmpty()) slotHeight else slotHeight - 384048
            val (fitWidth, fitHeight) = fitBox(width, pictureHeight, image.ratio)
            val list = mutableListOf<String>()
            list += picture(
                id = ids.next(),
                name = "图片 ${index + 1}",
                relId = relIds[index],
                offset = "${x + (width - fitWidth) / 2},${slotTop + (pictureHeight - fitHeight) / 2}",
                extent = "$fitWidth,$fitHeight",
                description = caption,
            )
            if (caption.isNotEmpty()) {
                list += shape(
                    id = ids.next(),
                    name = "图注 ${index + 1}",
                    offset = "$x,${slotTop + slotHeight - 329184}",
                    extent = "$width,274320",
                    paragraphs = listOf(
                        paragraph(
                            text = xmlEscape(caption),
                            size = 1100,
                            color = MUTED,
                            align = "ctr",
                        ),
                    ),
                )
            }
            list
        }.flatten()
    }

    /** Largest box carrying [ratio] that fits inside [width] x [height]. */
    private fun fitBox(width: Int, height: Int, ratio: Float): Pair<Int, Int> {
        if (width <= 0 || height <= 0 || ratio <= 0f) return width to height
        val fittedWidth = minOf(width, (height * ratio).toInt())
        val fittedHeight = if (fittedWidth < width) height else (width / ratio).toInt()
        return fittedWidth to fittedHeight
    }

    /**
     * A picture frame.
     *
     * PowerPoint renders the media through the blipFill relationship, so the
     * shape holds no text of its own and no txBody like a text shape does.
     */
    private fun picture(
        id: Int,
        name: String,
        relId: String,
        offset: String,
        extent: String,
        description: String,
    ): String {
        val (offX, offY) = offset.split(',')
        val (extCx, extCy) = extent.split(',')
        return "<p:pic>" +
            "<p:nvPicPr><p:cNvPr id=\"$id\" name=\"$name\" descr=\"${xmlEscape(description)}\"/>" +
            "<p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr>" +
            "<p:nvPr/></p:nvPicPr>" +
            "<p:blipFill><a:blip r:embed=\"$relId\"/>" +
            "<a:stretch><a:fillRect/></a:stretch></p:blipFill>" +
            "<p:spPr><a:xfrm><a:off x=\"$offX\" y=\"$offY\"/>" +
            "<a:ext cx=\"$extCx\" cy=\"$extCy\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>" +
            "<a:ln w=\"12700\"><a:solidFill><a:srgbClr val=\"$RULE\"/></a:solidFill></a:ln>" +
            "</p:spPr></p:pic>"
    }

    /** First non-empty bullet, for slides whose title went missing. */
    private fun firstBullet(spec: SlideSpec): String =
        spec.bullets.firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    private fun slideRels(
        number: Int,
        spec: SlideSpec,
        pictures: List<ResolvedImage>,
        media: MediaLibrary,
    ): String {
        val notes = if (spec.notes.isNotBlank()) {
            "<Relationship Id=\"rId2\" Type=\"$RT/notesSlide\" " +
                "Target=\"../notesSlides/notesSlide$number.xml\"/>"
        } else {
            ""
        }
        val relIds = pictureRelIds(spec.notes.isNotBlank(), pictures.size)
        val pictureRels = pictures.mapIndexed { index, image ->
            "<Relationship Id=\"${relIds[index]}\" Type=\"$RT/image\" " +
                "Target=\"${media.register(image)}\"/>"
        }
        return XML_HEADER +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"$RT/slideLayout\" " +
                "Target=\"../slideLayouts/slideLayout1.xml\"/>" +
            notes +
            pictureRels.joinToString("") +
            "</Relationships>"
    }

    /** One media part per distinct picture source, in first-seen order. */
    private class MediaLibrary {
        private val slots = LinkedHashMap<String, MediaSlot>()

        /** Adds the picture if it is new and returns the rel target for it. */
        fun register(image: ResolvedImage): String {
            val slot = slots.getOrPut(image.source) { MediaSlot(slots.size + 1, image) }
            return slot.relTarget
        }

        /** Container formats the package must declare a content type for. */
        val formats: List<ImageFormat> get() = slots.values.map { it.format }

        val entries: List<Pair<String, ByteArray>> get() = slots.values.map { it.part }
    }

    private class MediaSlot(index: Int, image: ResolvedImage) {
        val format: ImageFormat = image.format
        val part: Pair<String, ByteArray> =
            "ppt/media/image$index.${image.format.extension}" to image.bytes
        val relTarget: String = "../media/image$index.${image.format.extension}"
    }

    private fun notesSlide(number: Int, spec: SlideSpec): String = XML_HEADER +
        "<p:notesSlide $NS>" +
        "<p:cSld>" +
        spTree(
            listOf(
                "<p:sp>" +
                    "<p:nvSpPr><p:cNvPr id=\"2\" name=\"Notes Placeholder 1\"/>" +
                    "<p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr>" +
                    "<p:nvPr><p:ph type=\"body\" idx=\"1\"/></p:nvPr></p:nvSpPr>" +
                    "<p:spPr/>" +
                    "<p:txBody><a:bodyPr/><a:lstStyle/>" +
                    paragraph(text = xmlEscape(spec.notes.trim()), size = 1200) +
                    "</p:txBody></p:sp>",
            ),
        ) +
        "</p:cSld>" +
        "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>" +
        "</p:notesSlide>"

    private fun notesSlideRels(): String = XML_HEADER +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"$RT/notesMaster\" " +
        "Target=\"../notesMasters/notesMaster1.xml\"/>" +
        "</Relationships>"

    private fun notesMaster(): String = XML_HEADER +
        "<p:notesMaster $NS>" +
        "<p:cSld>" +
        spTree(
            listOf(
                "<p:sp>" +
                    "<p:nvSpPr><p:cNvPr id=\"2\" name=\"Notes Placeholder 1\"/>" +
                    "<p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr>" +
                    "<p:nvPr><p:ph type=\"body\" idx=\"1\"/></p:nvPr></p:nvSpPr>" +
                    "<p:spPr><a:xfrm><a:off x=\"685800\" y=\"4343400\"/>" +
                    "<a:ext cx=\"5486400\" cy=\"4114800\"/></a:xfrm></p:spPr>" +
                    "<p:txBody><a:bodyPr/><a:lstStyle/>" +
                    "<a:p><a:endParaRPr lang=\"zh-CN\"/></a:p>" +
                    "</p:txBody></p:sp>",
            ),
        ) +
        "</p:cSld>" +
        "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" " +
        "accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" " +
        "accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>" +
        "<p:notesStyle><a:lvl1pPr><a:defRPr sz=\"1200\"/></a:lvl1pPr></p:notesStyle>" +
        "</p:notesMaster>"

    private fun notesMasterRels(): String = XML_HEADER +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"$RT/theme\" Target=\"../theme/theme1.xml\"/>" +
        "</Relationships>"

    private fun shape(
        id: Int,
        name: String,
        offset: String,
        extent: String,
        paragraphs: List<String>,
        anchor: String? = null,
    ): String {
        val (offX, offY) = offset.split(',')
        val (extCx, extCy) = extent.split(',')
        val anchorAttr = if (anchor != null) " anchor=\"$anchor\"" else ""
        return "<p:sp>" +
            "<p:nvSpPr><p:cNvPr id=\"$id\" name=\"$name\"/>" +
            "<p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr/></p:nvSpPr>" +
            "<p:spPr><a:xfrm><a:off x=\"$offX\" y=\"$offY\"/>" +
            "<a:ext cx=\"$extCx\" cy=\"$extCy\"/></a:xfrm></p:spPr>" +
            "<p:txBody><a:bodyPr wrap=\"square\"$anchorAttr><a:normAutofit/></a:bodyPr>" +
            "<a:lstStyle/>${paragraphs.joinToString("")}</p:txBody>" +
            "</p:sp>"
    }

    /**
     * A solid decorative block: backgrounds, accent bars, hairline rules.
     *
     * It still needs a txBody - PowerPoint drops shapes that lack one.
     */
    private fun rect(id: Int, name: String, offset: String, extent: String, fill: String): String {
        val (offX, offY) = offset.split(',')
        val (extCx, extCy) = extent.split(',')
        return "<p:sp>" +
            "<p:nvSpPr><p:cNvPr id=\"$id\" name=\"$name\"/>" +
            "<p:cNvSpPr/><p:nvPr/></p:nvSpPr>" +
            "<p:spPr><a:xfrm><a:off x=\"$offX\" y=\"$offY\"/>" +
            "<a:ext cx=\"$extCx\" cy=\"$extCy\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>" +
            "<a:solidFill><a:srgbClr val=\"$fill\"/></a:solidFill>" +
            "<a:ln><a:noFill/></a:ln></p:spPr>" +
            "<p:txBody><a:bodyPr/><a:lstStyle/><a:p/></p:txBody>" +
            "</p:sp>"
    }

    /** Hands out the unique shape ids a slide part expects. */
    private class IdSequence {
        private var next = 2

        fun next(): Int = next++
    }

    private fun paragraph(
        text: String,
        size: Int,
        bold: Boolean = false,
        color: String? = null,
        align: String? = null,
        bullet: Boolean = false,
        bulletColor: String? = null,
        lineSpacing: Int? = null,
        spaceBefore: Int? = null,
    ): String {
        // A bullet hangs from its indent; a centered title gets neither.
        val props = buildString {
            if (bullet) append(" marL=\"342900\" indent=\"-342900\"")
            if (align != null) append(" algn=\"$align\"")
        }
        // DrawingML fixes the child order: spacing, then bullet formatting.
        val extras = buildString {
            if (lineSpacing != null) {
                append("<a:lnSpc><a:spcPct val=\"$lineSpacing\"/></a:lnSpc>")
            }
            if (spaceBefore != null) append("<a:spcBef><a:spcPts val=\"$spaceBefore\"/></a:spcBef>")
            if (bullet) {
                if (bulletColor != null) {
                    append("<a:buClr><a:srgbClr val=\"$bulletColor\"/></a:buClr>")
                }
                append("<a:buSzPct val=\"90000\"/>")
                append("<a:buFont typeface=\"Arial\" pitchFamily=\"34\" charset=\"0\"/>")
                append("<a:buChar char=\"\u2022\"/>")
            }
        }
        // Fill and typeface live inside the run properties, fill first.
        val runFill = if (color != null) {
            "<a:solidFill><a:srgbClr val=\"$color\"/></a:solidFill>" +
                "<a:latin typeface=\"$FONT\"/><a:ea typeface=\"$FONT\"/><a:cs typeface=\"$FONT\"/>"
        } else {
            ""
        }
        val attrs = buildString {
            append("lang=\"zh-CN\" sz=\"$size\"")
            if (bold) append(" b=\"1\"")
            append(" dirty=\"0\"")
        }
        val run = "<a:r><a:rPr $attrs>$runFill</a:rPr><a:t>$text</a:t></a:r>"
        return "<a:p><a:pPr$props>$extras</a:pPr>$run</a:p>"
    }

    /**
     * Wraps [shapes] in the group shape that opens every spTree.
     *
     * Shapes must land *inside* the tree: appended after the closing tag the
     * package still opens, but every slide renders blank.
     */
    private fun spTree(shapes: List<String> = emptyList()): String =
        "<p:spTree>" +
        "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
        "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/>" +
        "<a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>" +
        shapes.joinToString("") +
        "</p:spTree>"

    private fun contentTypes(slides: List<SlideSpec>, formats: List<ImageFormat>): String {
        val notes = if (slides.any { it.notes.isNotBlank() }) {
            slides.indices
                .filter { slides[it].notes.isNotBlank() }
                .joinToString("") { index ->
                    "<Override PartName=\"/ppt/notesSlides/notesSlide${index + 1}.xml\" " +
                        "ContentType=\"application/vnd.openxmlformats-officedocument." +
                        "presentationml.notesSlide+xml\"/>"
                }
        } else {
            ""
        }
        val images = formats.distinct().joinToString("") { format ->
            "<Default Extension=\"${format.extension}\" ContentType=\"${format.mimeType}\"/>"
        }
        return XML_HEADER +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" " +
            "ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            images +
            "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"" +
            "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>" +
            "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"" +
            "application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>" +
            "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"" +
            "application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>" +
            slides.indices.joinToString("") { index ->
                "<Override PartName=\"/ppt/slides/slide${index + 1}.xml\" ContentType=\"" +
                    "application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>"
            } +
            "<Override PartName=\"/ppt/notesMasters/notesMaster1.xml\" ContentType=\"" +
            "application/vnd.openxmlformats-officedocument.presentationml.notesMaster+xml\"/>" +
            notes +
            "<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"" +
            "application/vnd.openxmlformats-officedocument.theme+xml\"/>" +
            "<Override PartName=\"/docProps/core.xml\" " +
            "ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
            "<Override PartName=\"/docProps/app.xml\" ContentType=\"" +
            "application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>" +
            "</Types>"
    }

    private fun rootRels(): String = XML_HEADER +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"$RT/officeDocument\" " +
        "Target=\"ppt/presentation.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"" +
        "http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" " +
        "Target=\"docProps/core.xml\"/>" +
        "<Relationship Id=\"rId3\" Type=\"" +
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" " +
        "Target=\"docProps/app.xml\"/>" +
        "</Relationships>"

    private fun coreProps(): String = XML_HEADER +
        "<cp:coreProperties " +
        "xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
        "xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
        "<dc:title>Pocket Agent</dc:title><dc:creator>Pocket Agent</dc:creator></cp:coreProperties>"

    private fun appProps(slideCount: Int): String = XML_HEADER +
        "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\" " +
        "xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\">" +
        "<Application>Pocket Agent</Application>" +
        "<Slides>$slideCount</Slides>" +
        "</Properties>"

    private fun theme(): String = XML_HEADER +
        "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"Office Theme\">" +
        "<a:themeElements>" +
        "<a:clrScheme name=\"Office\">" +
        "<a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1>" +
        "<a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1>" +
        "<a:dk2><a:srgbClr val=\"0F0F0F\"/></a:dk2>" +
        "<a:lt2><a:srgbClr val=\"F2F2F2\"/></a:lt2>" +
        "<a:accent1><a:srgbClr val=\"4472C4\"/></a:accent1>" +
        "<a:accent2><a:srgbClr val=\"ED7D31\"/></a:accent2>" +
        "<a:accent3><a:srgbClr val=\"A5A5A5\"/></a:accent3>" +
        "<a:accent4><a:srgbClr val=\"FFC000\"/></a:accent4>" +
        "<a:accent5><a:srgbClr val=\"5B9BD5\"/></a:accent5>" +
        "<a:accent6><a:srgbClr val=\"70AD47\"/></a:accent6>" +
        "<a:hlink><a:srgbClr val=\"0563C1\"/></a:hlink>" +
        "<a:folHlink><a:srgbClr val=\"954F72\"/></a:folHlink>" +
        "</a:clrScheme>" +
        "<a:fontScheme name=\"Office\">" +
        "<a:majorFont><a:latin typeface=\"Calibri Light\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont>" +
        "<a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont>" +
        "</a:fontScheme>" +
        "<a:fmtScheme name=\"Office\">" +
        "<a:fillStyleLst>" +
        "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>" +
        "<a:gradFill rotWithShape=\"1\"><a:gsLst>" +
        "<a:gs pos=\"0\"><a:schemeClr val=\"phClr\"><a:lumMod val=\"110000\"/>" +
        "<a:satMod val=\"105000\"/><a:tint val=\"67000\"/></a:schemeClr></a:gs>" +
        "<a:gs pos=\"50000\"><a:schemeClr val=\"phClr\"><a:lumMod val=\"105000\"/>" +
        "<a:satMod val=\"103000\"/><a:tint val=\"73000\"/></a:schemeClr></a:gs>" +
        "<a:gs pos=\"100000\"><a:schemeClr val=\"phClr\"><a:lumMod val=\"105000\"/>" +
        "<a:satMod val=\"109000\"/><a:tint val=\"81000\"/></a:schemeClr></a:gs>" +
        "</a:gsLst><a:lin ang=\"5400000\" scaled=\"0\"/></a:gradFill>" +
        "<a:gradFill rotWithShape=\"1\"><a:gsLst>" +
        "<a:gs pos=\"0\"><a:schemeClr val=\"phClr\"><a:satMod val=\"103000\"/>" +
        "<a:lumMod val=\"102000\"/><a:tint val=\"94000\"/></a:schemeClr></a:gs>" +
        "<a:gs pos=\"50000\"><a:schemeClr val=\"phClr\"><a:satMod val=\"110000\"/>" +
        "<a:lumMod val=\"100000\"/><a:shade val=\"100000\"/></a:schemeClr></a:gs>" +
        "<a:gs pos=\"100000\"><a:schemeClr val=\"phClr\"><a:lumMod val=\"99000\"/>" +
        "<a:satMod val=\"120000\"/><a:shade val=\"78000\"/></a:schemeClr></a:gs>" +
        "</a:gsLst><a:lin ang=\"5400000\" scaled=\"0\"/></a:gradFill>" +
        "</a:fillStyleLst>" +
        "<a:lnStyleLst>" +
        "<a:ln w=\"12700\" cap=\"flat\" cmpd=\"sng\" algn=\"ctr\">" +
        "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>" +
        "<a:prstDash val=\"solid\"/><a:miter lim=\"800000\"/></a:ln>" +
        "<a:ln w=\"19050\" cap=\"flat\" cmpd=\"sng\" algn=\"ctr\">" +
        "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>" +
        "<a:prstDash val=\"solid\"/><a:miter lim=\"800000\"/></a:ln>" +
        "<a:ln w=\"25400\" cap=\"flat\" cmpd=\"sng\" algn=\"ctr\">" +
        "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>" +
        "<a:prstDash val=\"solid\"/><a:miter lim=\"800000\"/></a:ln>" +
        "</a:lnStyleLst>" +
        "<a:effectStyleLst>" +
        "<a:effectStyle><a:effectLst/></a:effectStyle>" +
        "<a:effectStyle><a:effectLst/></a:effectStyle>" +
        "<a:effectStyle><a:effectLst/></a:effectStyle>" +
        "</a:effectStyleLst>" +
        "<a:bgFillStyleLst>" +
        "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>" +
        "<a:solidFill><a:schemeClr val=\"phClr\"><a:tint val=\"95000\"/>" +
        "<a:satMod val=\"170000\"/></a:schemeClr></a:solidFill>" +
        "<a:gradFill rotWithShape=\"1\"><a:gsLst>" +
        "<a:gs pos=\"0\"><a:schemeClr val=\"phClr\"><a:tint val=\"93000\"/>" +
        "<a:satMod val=\"150000\"/><a:shade val=\"98000\"/><a:lumMod val=\"102000\"/></a:schemeClr></a:gs>" +
        "<a:gs pos=\"50000\"><a:schemeClr val=\"phClr\"><a:tint val=\"98000\"/>" +
        "<a:satMod val=\"130000\"/><a:shade val=\"90000\"/><a:lumMod val=\"103000\"/></a:schemeClr></a:gs>" +
        "<a:gs pos=\"100000\"><a:schemeClr val=\"phClr\"><a:shade val=\"63000\"/>" +
        "<a:satMod val=\"120000\"/></a:schemeClr></a:gs>" +
        "</a:gsLst><a:lin ang=\"5400000\" scaled=\"0\"/></a:gradFill>" +
        "</a:bgFillStyleLst>" +
        "</a:fmtScheme>" +
        "</a:themeElements>" +
        "<a:objectDefaults/><a:extraClrSchemeLst/>" +
        "</a:theme>"

    /** Relationship type prefix; keeps the XML above readable. */
    private const val RT =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    /** The 16:9 canvas every layout below is measured against. */
    private const val SLIDE_SIZE = "12192000,6858000"

    /** Body text size a content slide uses when it has room to spare. */
    private const val BODY_SIZE = 1800

    /** Microsoft YaHei ships with Windows and WPS; both render CJK cleanly. */
    private const val FONT = "Microsoft YaHei"

    // Layout palette: a navy stage, one blue accent, quiet greys.
    private const val NAVY = "1B2A5E"
    private const val ACCENT = "2E5BFF"
    private const val MIST = "C9D6FF"
    private const val INK = "1F2430"
    private const val MUTED = "8A93A6"
    private const val RULE = "E3E7F2"
    private const val PANEL = "F4F6FB"
    private const val WHITE = "FFFFFF"

    /** Titles that mark a slide as the closing one even without a layout. */
    private val CLOSING_TITLES = listOf("谢谢", "感谢", "thank", "q&a")

    private const val NS =
        "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
            "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""
}
