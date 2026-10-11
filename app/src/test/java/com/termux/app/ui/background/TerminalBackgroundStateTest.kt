package com.termux.app.ui.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for the terminal background state defaults and dim clamping. */
class TerminalBackgroundStateTest {

    @Test
    fun `defaults are no picture and 60 percent dim`() {
        val state = TerminalBackgroundState()
        assertNull(state.bitmap)
        assertEquals(60, state.dimPercent)
    }

    @Test
    fun `dim is clamped to the 0-90 range`() {
        val state = TerminalBackgroundState()
        state.update(null, -5)
        assertEquals(0, state.dimPercent)
        state.update(null, 150)
        assertEquals(90, state.dimPercent)
    }

    @Test
    fun `update replaces the picture and dim together`() {
        val state = TerminalBackgroundState()
        state.update(null, 40)
        assertEquals(40, state.dimPercent)
        assertNull(state.bitmap)
        state.update(null, 10)
        assertEquals(10, state.dimPercent)
    }
}
