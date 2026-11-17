package com.termux.app.api.file

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceivedFileNamesTest {

    @Test
    fun `plain names are kept`() {
        assertEquals("notes.txt", safeReceivedFileName("notes.txt"))
        assertEquals("notes.txt", safeReceivedFileName("  notes.txt  "))
        assertEquals("a b.txt", safeReceivedFileName("a b.txt"))
    }

    @Test
    fun `escaping and path names are rejected`() {
        assertNull(safeReceivedFileName(null))
        assertNull(safeReceivedFileName(""))
        assertNull(safeReceivedFileName("   "))
        assertNull(safeReceivedFileName("."))
        assertNull(safeReceivedFileName(".."))
        assertNull(safeReceivedFileName("../.bashrc"))
        assertNull(safeReceivedFileName("sub/file.txt"))
        assertNull(safeReceivedFileName("..\\windows.txt"))
        assertNull(safeReceivedFileName("/data/data/com.termux/.bashrc"))
        assertNull(safeReceivedFileName("a\u0000b"))
        assertNull(safeReceivedFileName("line\nbreak"))
    }

    @Test
    fun `containment compares canonical parents`() {
        val dir = File("/data/data/com.termux/files/home/downloads")
        assertTrue(isDirectChildOf(dir, File(dir, "notes.txt")))
        assertFalse(isDirectChildOf(dir, File(dir, "../.bashrc")))
        assertFalse(isDirectChildOf(dir, File("/data/data/com.termux/files/home/downloads2/x")))
        assertFalse(isDirectChildOf(dir, dir))
    }
}
