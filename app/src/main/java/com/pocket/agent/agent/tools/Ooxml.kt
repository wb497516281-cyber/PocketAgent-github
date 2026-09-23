package com.pocket.agent.agent.tools

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal const val XML_HEADER = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""

/** Escapes text for use in an XML element or attribute. */
internal fun xmlEscape(text: String): String = buildString(text.length + 16) {
    text.forEach { ch ->
        when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(ch)
        }
    }
}

/** Writes [entries] as a ZIP package, preserving the given order. */
internal fun writeZipText(entries: List<Pair<String, String>>, out: OutputStream) {
    writeZip(entries.map { (name, text) -> name to text.toByteArray(Charsets.UTF_8) }, out)
}

/**
 * Writes [entries] as a ZIP package, preserving the given order.
 *
 * Entries are bytes rather than text because a presentation carries binary
 * parts - the images a slide embeds - that must not be re-encoded.
 */
internal fun writeZip(entries: List<Pair<String, ByteArray>>, out: OutputStream) {
    ZipOutputStream(out.buffered()).use { zip ->
        entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
}
