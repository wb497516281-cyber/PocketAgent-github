package com.pocket.agent.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamesTest {

    @Test
    fun `accepts ordinary names`() {
        assertNull(FileNames.error("note.md"))
        assertNull(FileNames.error("测试.txt"))
        assertNull(FileNames.error("no-extension"))
        assertNull(FileNames.error(".hidden"))
    }

    @Test
    fun `rejects empty and blank names`() {
        assertTrue(FileNames.check("") is NameCheck.Invalid)
        assertTrue(FileNames.check("   ") is NameCheck.Invalid)
    }

    @Test
    fun `rejects path separators and parent references`() {
        assertTrue(FileNames.check("a/b.md") is NameCheck.Invalid)
        assertTrue(FileNames.check("a\\b.md") is NameCheck.Invalid)
        assertTrue(FileNames.check("../escape.md") is NameCheck.Invalid)
        assertTrue(FileNames.check("..") is NameCheck.Invalid)
        assertTrue(FileNames.check(".") is NameCheck.Invalid)
    }

    @Test
    fun `rejects null and control characters`() {
        assertTrue(FileNames.check("bad\u0000name.md") is NameCheck.Invalid)
        assertTrue(FileNames.check("bad\nname.md") is NameCheck.Invalid)
    }

    @Test
    fun `rejects windows reserved names`() {
        assertTrue(FileNames.check("con.md") is NameCheck.Invalid)
        assertTrue(FileNames.check("PRN.txt") is NameCheck.Invalid)
        assertTrue(FileNames.check("aux") is NameCheck.Invalid)
    }

    @Test
    fun `rejects trailing dot or space and over-long names`() {
        assertTrue(FileNames.check("note.") is NameCheck.Invalid)
        assertTrue(FileNames.check("note ") is NameCheck.Invalid)
        assertTrue(FileNames.check("x".repeat(200)) is NameCheck.Invalid)
    }

    @Test
    fun `reports the reason for a rejection`() {
        assertEquals("文件名不能包含 /、\\ 或空字符", FileNames.error("a/b"))
    }

    @Test
    fun `keeps the one megabyte ceiling`() {
        assertEquals(1024L * 1024L, FileNames.MAX_FILE_BYTES)
    }
}
