package com.pocket.agent.agent.tools

import kotlin.math.abs

/** The image containers a slide is allowed to embed. */
internal enum class ImageFormat(val extension: String, val mimeType: String) {
    PNG("png", "image/png"),
    JPEG("jpeg", "image/jpeg"),
    GIF("gif", "image/gif"),
    BMP("bmp", "image/bmp"),
    WEBP("webp", "image/webp"),
    TIFF("tiff", "image/tiff"),
}

/**
 * Reads an image's container type and pixel size out of its own header bytes.
 *
 * The deck writer fits a picture into its slot by aspect ratio, and the bitmap
 * factory it would normally ask is an Android class the writer's JVM tests
 * cannot see. The six containers the downloader accepts all state their
 * dimensions near the front of the file, so a header read is enough: the writer
 * stays a pure function of its inputs, and a slide can be laid out around a
 * picture it never decodes into pixels.
 */
internal object ImageHeader {

    /** Which container [bytes] holds, or null when it is not a supported image. */
    fun formatOf(bytes: ByteArray): ImageFormat? = when {
        matches(bytes, 0, PNG_MAGIC) -> ImageFormat.PNG
        matches(bytes, 0, GIF_MAGIC) -> ImageFormat.GIF
        matches(bytes, 0, BMP_MAGIC) -> ImageFormat.BMP
        matches(bytes, 0, JPEG_MAGIC) -> ImageFormat.JPEG
        matches(bytes, 0, TIFF_II) || matches(bytes, 0, TIFF_MM) -> ImageFormat.TIFF
        // WebP is a RIFF container: the WEBP fourcc sits four bytes in, behind
        // the chunk length, so it is tested last.
        matches(bytes, 0, RIFF_MAGIC) && matches(bytes, 8, WEBP_MAGIC) -> ImageFormat.WEBP
        else -> null
    }

    /** Width and height in pixels, or null when the header cannot be read. */
    fun sizeOf(bytes: ByteArray): Pair<Int, Int>? = when (formatOf(bytes)) {
        ImageFormat.PNG -> pngSize(bytes)
        ImageFormat.JPEG -> jpegSize(bytes)
        ImageFormat.GIF -> gifSize(bytes)
        ImageFormat.BMP -> bmpSize(bytes)
        ImageFormat.WEBP -> webpSize(bytes)
        ImageFormat.TIFF -> tiffSize(bytes)
        null -> null
    }

    private fun matches(bytes: ByteArray, at: Int, signature: ByteArray): Boolean =
        at + signature.size <= bytes.size &&
            signature.indices.all { index -> bytes[at + index] == signature[index] }

    // PNG: 8 byte signature, a chunk header, then IHDR width and height.
    private fun pngSize(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 24 || !matches(bytes, 12, IHDR_MAGIC)) return null
        val width = bytes.uint32Be(16).toInt()
        val height = bytes.uint32Be(20).toInt()
        return if (width > 0 && height > 0) width to height else null
    }

    // GIF: the "GIF87a" or "GIF89a" prefix, then the logical screen size as two
    // 16 bit little endian values.
    private fun gifSize(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 10) return null
        val width = bytes.uint16Le(6)
        val height = bytes.uint16Le(8)
        return if (width > 0 && height > 0) width to height else null
    }

    // BMP: "BM", a 14 byte file header, then width and height as 4 byte signed
    // integers. A negative height marks a top down bitmap.
    private fun bmpSize(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 26) return null
        val width = bytes.uint32Le(18).toInt()
        val height = abs(bytes.int32Le(22))
        return if (width > 0 && height > 0) width to height else null
    }

    // WebP: "RIFF", chunk length, "WEBP", then one of three chunk flavours.
    private fun webpSize(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 30) return null
        return when (String(bytes, 12, 4, Charsets.US_ASCII)) {
            // Extended format: canvas size minus one, 24 bit little endian.
            "VP8X" -> (bytes.uint24Le(24) + 1) to (bytes.uint24Le(27) + 1)
            // Lossless: 14 bits of width minus one, then 14 of height.
            "VP8L" -> {
                val packed = bytes.uint32Le(21)
                ((packed and 0x3FFF).toInt() + 1) to
                    (((packed shr 14) and 0x3FFF).toInt() + 1)
            }
            // Lossy: frame tag, start code, then two 14 bit dimensions.
            "VP8 " -> {
                val width = bytes.uint16Le(26) and 0x3FFF
                val height = bytes.uint16Le(28) and 0x3FFF
                if (width > 0 && height > 0) width to height else null
            }
            else -> null
        }
    }

    // TIFF: an endianness marker, 42, the offset of the first IFD, then entries
    // whose tags 256 and 257 are the width and the height.
    private fun tiffSize(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 8) return null
        val big = matches(bytes, 0, TIFF_MM)
        val u16: (Int) -> Int =
            if (big) { at -> bytes.uint16Be(at) } else { at -> bytes.uint16Le(at) }
        val u32: (Int) -> Long =
            if (big) { at -> bytes.uint32Be(at) } else { at -> bytes.uint32Le(at) }
        val ifd = u32(4).toInt()
        if (ifd < 8 || ifd + 2 > bytes.size) return null
        var width = 0
        var height = 0
        val entries = u16(ifd)
        for (entry in 0 until entries) {
            val at = ifd + 2 + entry * 12
            if (at + 12 > bytes.size) break
            val tag = u16(at)
            if (tag != 0x0100 && tag != 0x0101) continue
            // SHORT sits in the first two bytes of the value field; LONG uses
            // all four, both in the file's own byte order.
            val value = if (u16(at + 2) == 3) u16(at + 8).toLong() else u32(at + 8)
            if (tag == 0x0100) width = value.toInt() else height = value.toInt()
            if (width > 0 && height > 0) break
        }
        return if (width > 0 && height > 0) width to height else null
    }

    // JPEG: marker segments until a start of frame, whose height precedes its
    // width. EXIF and the other APP segments are stepped over on the way.
    private fun jpegSize(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 4 || !matches(bytes, 0, JPEG_MAGIC)) return null
        var at = 2
        while (at + 4 <= bytes.size) {
            if (bytes[at] != 0xFF.toByte()) {
                at++
                continue
            }
            val marker = bytes.uint8(at + 1)
            // Fill bytes precede a marker; SOI, TEM and the restart markers
            // carry no length of their own.
            if (marker == 0xFF || marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
                at += 2
                continue
            }
            if (marker == 0xD9) return null
            val length = bytes.uint16Be(at + 2)
            if (length < 2) return null
            // SOF0, SOF1, SOF2 and their siblings - everything that is not a
            // Huffman table or a progressive marker - carry the frame size.
            val isFrame = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isFrame) {
                if (at + 9 >= bytes.size) return null
                val height = bytes.uint16Be(at + 5)
                val width = bytes.uint16Be(at + 7)
                return if (width > 0 && height > 0) width to height else null
            }
            at += 2 + length
        }
        return null
    }

    private fun ByteArray.uint8(at: Int): Int = this[at].toInt() and 0xFF

    private fun ByteArray.uint16Be(at: Int): Int = (uint8(at) shl 8) or uint8(at + 1)

    private fun ByteArray.uint16Le(at: Int): Int = uint8(at) or (uint8(at + 1) shl 8)

    private fun ByteArray.uint24Le(at: Int): Int =
        uint8(at) or (uint8(at + 1) shl 8) or (uint8(at + 2) shl 16)

    private fun ByteArray.uint32Be(at: Int): Long =
        (uint8(at).toLong() shl 24) or (uint8(at + 1).toLong() shl 16) or
            (uint8(at + 2).toLong() shl 8) or uint8(at + 3).toLong()

    private fun ByteArray.uint32Le(at: Int): Long =
        uint8(at).toLong() or (uint8(at + 1).toLong() shl 8) or
            (uint8(at + 2).toLong() shl 16) or (uint8(at + 3).toLong() shl 24)

    private fun ByteArray.int32Le(at: Int): Int = uint32Le(at).toInt()

    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val IHDR_MAGIC = "IHDR".toByteArray(Charsets.US_ASCII)
    private val GIF_MAGIC = "GIF8".toByteArray(Charsets.US_ASCII)
    private val BMP_MAGIC = "BM".toByteArray(Charsets.US_ASCII)
    private val RIFF_MAGIC = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WEBP_MAGIC = "WEBP".toByteArray(Charsets.US_ASCII)
    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val TIFF_II = "II".toByteArray(Charsets.US_ASCII)
    private val TIFF_MM = "MM".toByteArray(Charsets.US_ASCII)
}
