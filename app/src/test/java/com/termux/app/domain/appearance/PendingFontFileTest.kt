package com.termux.app.domain.appearance

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingFontFileTest {

    @Test
    fun `replacing a candidate deletes the previous one`() {
        val first = File.createTempFile("font-a", ".ttf").apply { writeText("a") }
        val second = File.createTempFile("font-b", ".ttf").apply { writeText("b") }
        val pending = PendingFontFile()

        pending.replace(first)
        pending.replace(second)

        assertFalse(first.exists())
        assertTrue(second.exists())
        assertNotNull(pending.file)
    }

    @Test
    fun `clearing deletes the current candidate`() {
        val file = File.createTempFile("font-c", ".ttf").apply { writeText("c") }
        val pending = PendingFontFile()
        pending.replace(file)
        pending.clear()
        assertFalse(file.exists())
        assertNull(pending.file)
    }
}
