package com.pocket.agent.agent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The header reader is what tells the deck writer how wide a picture is
 * relative to its height. The fixtures are files real encoders wrote, kept
 * under src/test/resources, so a parser that passes here will read a picture
 * that arrives from the network, not just the ones built by hand.
 */
class ImageHeaderTest {

    @Test
    fun `reads a png header`() {
        assertEquals(ImageFormat.PNG, ImageHeader.formatOf(bytes("quad-640x480.png")))
        assertEquals(640 to 480, ImageHeader.sizeOf(bytes("quad-640x480.png")))
    }

    @Test
    fun `reads a gif header`() {
        assertEquals(ImageFormat.GIF, ImageHeader.formatOf(bytes("small-120x90.gif")))
        assertEquals(120 to 90, ImageHeader.sizeOf(bytes("small-120x90.gif")))
    }

    @Test
    fun `reads a bmp header`() {
        assertEquals(ImageFormat.BMP, ImageHeader.formatOf(bytes("tall-64x128.bmp")))
        assertEquals(64 to 128, ImageHeader.sizeOf(bytes("tall-64x128.bmp")))
    }

    @Test
    fun `reads a tiff header`() {
        assertEquals(ImageFormat.TIFF, ImageHeader.formatOf(bytes("grey-256x128.tiff")))
        assertEquals(256 to 128, ImageHeader.sizeOf(bytes("grey-256x128.tiff")))
    }

    @Test
    fun `reads a jpeg header`() {
        assertEquals(ImageFormat.JPEG, ImageHeader.formatOf(bytes("wide-320x160.jpeg")))
        assertEquals(320 to 160, ImageHeader.sizeOf(bytes("wide-320x160.jpeg")))
    }

    @Test
    fun `steps over the exif segment in front of a jpeg frame`() {
        // Most photos from a camera carry an APP1 segment before the frame;
        // stopping at the first marker would read the thumbnail, not the image.
        assertEquals(320 to 160, ImageHeader.sizeOf(bytes("exif-320x160.jpeg")))
    }

    @Test
    fun `reads a lossy webp header`() {
        assertEquals(ImageFormat.WEBP, ImageHeader.formatOf(bytes("lossy-96x64.webp")))
        assertEquals(96 to 64, ImageHeader.sizeOf(bytes("lossy-96x64.webp")))
    }

    @Test
    fun `reads a lossless webp header`() {
        assertEquals(ImageFormat.WEBP, ImageHeader.formatOf(bytes("lossless-300x200.webp")))
        assertEquals(300 to 200, ImageHeader.sizeOf(bytes("lossless-300x200.webp")))
    }

    @Test
    fun `reads an extended webp header`() {
        // Extended files carry a 24 bit canvas size in the chunk header,
        // whether the pixels behind it are one frame or an animation.
        assertEquals(ImageFormat.WEBP, ImageHeader.formatOf(bytes("alpha-1000x500.webp")))
        assertEquals(1000 to 500, ImageHeader.sizeOf(bytes("alpha-1000x500.webp")))
        assertEquals(640 to 320, ImageHeader.sizeOf(bytes("animated-640x320.webp")))
    }

    @Test
    fun `reads the canvas size out of an extended webp header`() {
        val webp = webp("VP8X") { at ->
            write24Le(at, 999)
            write24Le(at + 3, 499)
        }
        // 24 bit canvas size, stored as the size minus one.
        assertEquals(1000 to 500, ImageHeader.sizeOf(webp))
    }

    @Test
    fun `reads the bit packed size out of a lossless webp header`() {
        val webp = webp("VP8L") { at -> write32Le(at, 299 or (199 shl 14)) }
        // Fourteen bits of width, then fourteen of height, one over.
        assertEquals(300 to 200, ImageHeader.sizeOf(webp))
    }

    @Test
    fun `a truncated png still names its container but gives no size`() {
        val cut = bytes("truncated-640x480.png")
        assertEquals(ImageFormat.PNG, ImageHeader.formatOf(cut))
        assertNull(ImageHeader.sizeOf(cut))
    }

    @Test
    fun `a file that is not an image reports nothing at all`() {
        listOf("not-an-image.txt").forEach { name ->
            assertNull(ImageHeader.formatOf(bytes(name)))
            assertNull(ImageHeader.sizeOf(bytes(name)))
        }
        assertNull(ImageHeader.formatOf(ByteArray(0)))
        assertNull(ImageHeader.formatOf(ByteArray(64)))
    }

    private fun bytes(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }

    /**
     * A WebP large enough for the writer to look at, with [chunk] as the fourcc
     * at offset 12 and [fill] laying out that flavour's own fields.
     */
    /**
     * A WebP large enough for the writer to look at, with [chunk] as the fourcc
     * at offset 12 and [fill] laying out that flavour's own fields.
     *
     * The fields start behind the chunk header: at 21 for the bit packed
     * lossless size, which trails its signature byte, and at 24 for the
     * extended canvas size, which trails its flags and padding.
     */
    private fun webp(chunk: String, fill: ByteArray.(Int) -> Unit): ByteArray =
        ByteArray(40).also { bytes ->
            "RIFF".forEachIndexed { index, ch -> bytes[index] = ch.code.toByte() }
            "WEBP".forEachIndexed { index, ch -> bytes[8 + index] = ch.code.toByte() }
            chunk.forEachIndexed { index, ch -> bytes[12 + index] = ch.code.toByte() }
            bytes.fill(0xFF.toByte(), 16, 20)
            val lossless = chunk == "VP8L"
            bytes[20] = if (lossless) 0x2F else 0x00
            fill(bytes, if (lossless) 21 else 24)
        }

    private fun ByteArray.write24Le(at: Int, value: Int) {
        this[at] = (value and 0xFF).toByte()
        this[at + 1] = ((value shr 8) and 0xFF).toByte()
        this[at + 2] = ((value shr 16) and 0xFF).toByte()
    }

    private fun ByteArray.write32Le(at: Int, value: Int) {
        repeat(4) { index -> this[at + index] = ((value shr (8 * index)) and 0xFF).toByte() }
    }
}
