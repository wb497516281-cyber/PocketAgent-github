package com.pocket.agent.agent.tools

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import java.io.OutputStream

/**
 * Renders parsed Markdown into a PDF with the platform's own [PdfDocument].
 *
 * Text is laid out through [StaticLayout], so paragraphs wrap on their own and
 * CJK line breaking comes for free. Pages are started lazily: a block that no
 * longer fits on the current page closes it and opens the next one, which is
 * what keeps a long weekly report from running off the bottom edge. The writer
 * is Android-only by nature and therefore not unit tested; the block parser it
 * consumes is.
 */
internal object PdfWriter {

    /** A4 at 72 dpi, expressed in PostScript points. */
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 44f
    private const val TEXT_WIDTH = PAGE_WIDTH - 2 * MARGIN

    private val TEXT_COLOR = Color.rgb(0x1A, 0x1A, 0x1A)
    private val MUTED_COLOR = Color.rgb(0x4A, 0x4A, 0x4A)
    private val CODE_BACKGROUND = Color.rgb(0xF1, 0xF1, 0xF1)

    fun render(blocks: List<MarkdownBlock>, out: OutputStream) {
        val document = PdfDocument()
        val stream = PageStream(document)
        blocks.forEach(stream::draw)
        stream.finish()
        try {
            document.writeTo(out)
        } finally {
            document.close()
        }
    }

    /** Walks the block list, opening and closing pages as the cursor moves. */
    private class PageStream(private val document: PdfDocument) {

        private val backgroundPaint = Paint().apply {
            color = CODE_BACKGROUND
            style = Paint.Style.FILL
        }

        private var page: PdfDocument.Page? = null
        private var y = 0f
        private var pageCount = 0

        fun draw(block: MarkdownBlock) {
            when (block) {
                is MarkdownBlock.Heading -> heading(block)
                is MarkdownBlock.Paragraph -> paragraph(block.text)
                is MarkdownBlock.Bullet -> bullet(block.text)
                is MarkdownBlock.Code -> code(block.text)
            }
        }

        private fun heading(block: MarkdownBlock.Heading) {
            val size = when (block.level) {
                1 -> 21f
                2 -> 17f
                3 -> 14.5f
                else -> 13f
            }
            space(6f + block.level.coerceAtMost(3) * 3f)
            place(block.text, paint(size = size, bold = true))
            space(4f)
        }

        private fun paragraph(text: String) {
            space(4f)
            place(text, paint(size = 11.5f))
            space(7f)
        }

        private fun bullet(text: String) {
            place("\u2022  $text", paint(size = 11.5f), indent = 14f)
            space(3f)
        }

        private fun code(text: String) {
            space(6f)
            place(text, paint(size = 10.5f, mono = true), indent = 10f, background = true)
            space(6f)
        }

        private fun space(amount: Float) {
            y += amount
        }

        /**
         * Lays out [text] at the cursor and advances past it, starting a new
         * page first when the block would not fit. An empty layout is skipped
         * so blank blocks never burn a page.
         */
        private fun place(
            text: CharSequence,
            paint: TextPaint,
            indent: Float = 0f,
            background: Boolean = false,
        ) {
            if (text.isBlank()) return
            val width = (TEXT_WIDTH - indent).toInt().coerceAtLeast(96)
            val layout = StaticLayout.Builder
                .obtain(text, 0, text.length, paint, width)
                .setIncludePad(false)
                .build()
            val height = layout.height.toFloat()
            if (y + height > PAGE_HEIGHT - MARGIN && y > MARGIN) {
                finishPage()
            }
            val canvas = ensurePage()
            val top = y
            if (background) {
                canvas.drawRect(
                    MARGIN + indent - 6f,
                    top - 3f,
                    PAGE_WIDTH - MARGIN + 6f,
                    top + height + 3f,
                    backgroundPaint,
                )
            }
            canvas.save()
            canvas.translate(MARGIN + indent, top)
            layout.draw(canvas)
            canvas.restore()
            y = top + height
        }

        private fun ensurePage(): Canvas {
            page?.let { return it.canvas }
            pageCount++
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageCount).create()
            val started = document.startPage(info)
            page = started
            y = MARGIN
            return started.canvas
        }

        private fun finishPage() {
            val current = page ?: return
            document.finishPage(current)
            page = null
            y = 0f
        }

        /** Closes the last page, opening one first so an empty body still yields a readable file. */
        fun finish() {
            if (page == null) ensurePage()
            finishPage()
        }
    }

    private fun paint(size: Float, bold: Boolean = false, mono: Boolean = false): TextPaint =
        TextPaint().apply {
            isAntiAlias = true
            textSize = size
            color = if (mono) MUTED_COLOR else TEXT_COLOR
            typeface = when {
                mono -> Typeface.MONOSPACE
                bold -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                else -> Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            }
        }
}
