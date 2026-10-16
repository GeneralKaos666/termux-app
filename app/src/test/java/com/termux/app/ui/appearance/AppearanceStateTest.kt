package com.termux.app.ui.appearance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the staged appearance state: load/apply/discard/reset, the dirty flag, and the
 * cursor style/blink mappings the store and screen rely on.
 */
class AppearanceStateTest {

    private fun color(hex: Int) = hex

    private fun appearance(
        foreground: Int = 0xffffffff.toInt(),
        fontPath: String? = null,
        fontSize: Int = 14,
        cursorStyle: CursorStyle = CursorStyle.BLOCK,
        cursorBlinkRate: Int = 0,
    ) = TerminalAppearance(
        colors = TerminalColorSet(
            foreground = foreground,
            background = 0xff000000.toInt(),
            cursor = 0xffffffff.toInt(),
            ansi = List(16) { it },
        ),
        fontPath = fontPath,
        fontSize = fontSize,
        cursorStyle = cursorStyle,
        cursorBlinkRate = cursorBlinkRate,
    )

    @Test
    fun `loaded appearance is the draft and starts clean`() {
        val state = AppearanceState(appearance())
        assertEquals(appearance(), state.loaded)
        assertEquals(appearance(), state.draft)
        assertFalse(state.isDirty)
    }

    @Test
    fun `load replaces both loaded and draft and clears dirty`() {
        val state = AppearanceState(appearance())
        state.setForeground(0xff123456.toInt())
        assertTrue(state.isDirty)

        val read = appearance(foreground = 0xffabcdef.toInt())
        state.load(read)

        assertEquals(read, state.loaded)
        assertEquals(read, state.draft)
        assertFalse(state.isDirty)
    }

    @Test
    fun `editing the draft marks the state dirty`() {
        val state = AppearanceState(appearance())
        state.setForeground(0xff112233.toInt())

        assertTrue(state.isDirty)
        assertEquals(0xff112233.toInt(), state.draft.colors.foreground)
        assertEquals(0xffffffff.toInt(), state.loaded.colors.foreground)
    }

    @Test
    fun `apply commits the draft and clears dirty`() {
        val state = AppearanceState(appearance())
        state.setForeground(0xff112233.toInt())
        state.apply()

        assertEquals(0xff112233.toInt(), state.loaded.colors.foreground)
        assertEquals(state.draft, state.loaded)
        assertFalse(state.isDirty)
    }

    @Test
    fun `discard restores the loaded appearance`() {
        val state = AppearanceState(appearance())
        state.setForeground(0xff112233.toInt())
        state.setFontSize(30)
        state.discard()

        assertEquals(0xffffffff.toInt(), state.draft.colors.foreground)
        assertEquals(14, state.draft.fontSize)
        assertFalse(state.isDirty)
    }

    @Test
    fun `reset to default restores the Termux default colors and cursor`() {
        val state = AppearanceState(appearance(fontPath = "/tmp/my.ttf", fontSize = 22, cursorStyle = CursorStyle.BAR, cursorBlinkRate = 500))
        state.setBackground(0xffffffff.toInt())
        state.setAnsiColor(3, 0xff00ff00.toInt())

        state.resetToDefault()

        assertEquals(ColorSchemes.TERMUX_DEFAULT.colors, state.draft.colors)
        assertEquals(CursorStyle.BLOCK, state.draft.cursorStyle)
        assertEquals(0, state.draft.cursorBlinkRate)
        assertTrue(state.isDirty)
    }

    @Test
    fun `reset to default keeps the font family and size`() {
        val state = AppearanceState(appearance(fontPath = "/tmp/my.ttf", fontSize = 22))
        state.resetToDefault()

        assertEquals("/tmp/my.ttf", state.draft.fontPath)
        assertEquals(22, state.draft.fontSize)
    }

    @Test
    fun `set ansi color replaces only the addressed index`() {
        val state = AppearanceState(appearance())
        state.setAnsiColor(5, 0xff00ff00.toInt())

        assertEquals(0xff00ff00.toInt(), state.draft.colors.ansi[5])
        assertEquals(4, state.draft.colors.ansi[4])
        assertEquals(16, state.draft.colors.ansi.size)
    }

    @Test
    fun `set ansi color rejects an out of range index`() {
        val state = AppearanceState(appearance())
        assertThrows(IllegalArgumentException::class.java) { state.setAnsiColor(16, 0xff000000.toInt()) }
        assertThrows(IllegalArgumentException::class.java) { state.setAnsiColor(-1, 0xff000000.toInt()) }
    }

    @Test
    fun `a color set requires exactly sixteen ansi colors`() {
        assertThrows(IllegalArgumentException::class.java) {
            TerminalColorSet(0xff000000.toInt(), 0xff000000.toInt(), 0xff000000.toInt(), List(15) { 0 })
        }
    }

    @Test
    fun `cursor style maps engine values and falls back to block`() {
        assertEquals(CursorStyle.BLOCK, CursorStyle.fromValue(0))
        assertEquals(CursorStyle.UNDERLINE, CursorStyle.fromValue(1))
        assertEquals(CursorStyle.BAR, CursorStyle.fromValue(2))
        assertEquals(CursorStyle.BLOCK, CursorStyle.fromValue(3))
        assertEquals(CursorStyle.BLOCK, CursorStyle.fromValue(-1))
        assertEquals(CursorStyle.BLOCK, CursorStyle.fromValue(null))
        assertEquals(0, CursorStyle.BLOCK.value)
        assertEquals(1, CursorStyle.UNDERLINE.value)
        assertEquals(2, CursorStyle.BAR.value)
    }

    @Test
    fun `blink rate is sanitized to zero or the engine range`() {
        assertEquals(0, CursorBlink.sanitize(0))
        assertEquals(100, CursorBlink.sanitize(100))
        assertEquals(750, CursorBlink.sanitize(750))
        assertEquals(2000, CursorBlink.sanitize(2000))
        assertEquals(0, CursorBlink.sanitize(99))
        assertEquals(0, CursorBlink.sanitize(2001))
        assertEquals(0, CursorBlink.sanitize(-5))
    }

    @Test
    fun `setting a blink rate outside the engine range disables blinking`() {
        val state = AppearanceState(appearance())
        state.setCursorBlinkRate(50)
        assertEquals(0, state.draft.cursorBlinkRate)

        state.setCursorBlinkRate(600)
        assertEquals(600, state.draft.cursorBlinkRate)
    }

    @Test
    fun `setting a font path can clear it`() {
        val state = AppearanceState(appearance(fontPath = "/tmp/my.ttf"))
        assertEquals("/tmp/my.ttf", state.draft.fontPath)
        state.setFontPath(null)
        assertNull(state.draft.fontPath)
    }
}
