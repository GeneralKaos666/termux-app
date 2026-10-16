package com.termux.app.ui.appearance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the X11 color parsing and serialization used by the appearance screen. The
 * accepted forms mirror the terminal engine's own parser so a round trip through
 * `colors.properties` keeps the same colors.
 */
class ColorHexTest {

    @Test
    fun `parse accepts the six digit form`() {
        assertEquals(0xff112233.toInt(), parseColor("#112233"))
        assertEquals(0xffaabbcc.toInt(), parseColor("#AABBCC"))
    }

    @Test
    fun `parse scales the short and long forms like the engine`() {
        assertEquals(0xffffffff.toInt(), parseColor("#fff"))
        assertEquals(0xffff0000.toInt(), parseColor("#f00"))
        assertEquals(0xff000000.toInt(), parseColor("#000"))
        assertEquals(0xffffffff.toInt(), parseColor("#ffffffffffff"))
        assertEquals(0xff112233.toInt(), parseColor("#111222333"))
    }

    @Test
    fun `parse accepts the rgb slash form`() {
        assertEquals(0xffff0000.toInt(), parseColor("rgb:ff/00/00"))
        assertEquals(0xff000000.toInt(), parseColor("rgb:0/0/0"))
        assertEquals(0xffffffff.toInt(), parseColor("rgb:ff/ff/ff"))
    }

    @Test
    fun `parse rejects malformed input`() {
        assertNull(parseColor(""))
        assertNull(parseColor("red"))
        assertNull(parseColor("#12"))
        assertNull(parseColor("#12345"))
        assertNull(parseColor("rgb:gg/00/00"))
        assertNull(parseColor("#gg0000"))
    }

    @Test
    fun `hex output is always six lowercase digits`() {
        assertEquals("#112233", colorToHex(0xff112233.toInt()))
        assertEquals("#ffffff", colorToHex(0xffffffff.toInt()))
        assertEquals("#000000", colorToHex(0xff000000.toInt()))
        assertEquals("#0000ff", colorToHex(0xff0000ff.toInt()))
    }

    @Test
    fun `hex output ignores the alpha channel`() {
        assertEquals("#ff8800", colorToHex(0x00ff8800))
    }

    @Test
    fun `hex output round trips through parse`() {
        val colors = listOf(0xff000000.toInt(), 0xff6495ed.toInt(), 0xffffffff.toInt(), 0xffcd00cd.toInt())
        colors.forEach { assertEquals(it, parseColor(colorToHex(it))) }
    }
}
