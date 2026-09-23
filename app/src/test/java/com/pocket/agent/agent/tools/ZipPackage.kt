package com.pocket.agent.agent.tools

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * A ZIP package read back into entry name to text pairs.

 * The writers emit byte arrays; the only way to know the package is well
 * formed without an emulator is to open it again and look inside, which is
 * what the Word and PowerPoint tests do through this helper.
 */
internal class ZipPackage(private val entries: Map<String, String>) {

    val names: Set<String> get() = entries.keys

    operator fun get(name: String): String? = entries[name]

    fun count(name: String, needle: String): Int {
        val text = entries[name] ?: return 0
        return text.split(needle).size - 1
    }

    companion object {
        fun from(bytes: ByteArray): ZipPackage {
            val entries = LinkedHashMap<String, String>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                    }
                    entry = zip.nextEntry
                }
            }
            return ZipPackage(entries)
        }
    }
}
