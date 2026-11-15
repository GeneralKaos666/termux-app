package com.termux.app.ui.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The acrylic background state clamps what the preferences and the settings screen can push into
 * it, so a bad preference value can never make the picture disappear or the dim opaque.
 */
class TerminalBackgroundStateTest {

    @Test
    fun `a fresh state has no picture and the defaults`() {
        val state = TerminalBackgroundState()
        assertNull(state.bitmap)
        assertEquals(60, state.dimPercent)
        assertEquals(0f, state.blurRadius, 0.001f)
        assertEquals(1f, state.pictureOpacity, 0.001f)
    }

    @Test
    fun `two argument update keeps the picture sharp and fully opaque`() {
        val state = TerminalBackgroundState()
        state.update(null, 60)
        assertEquals(0f, state.blurRadius, 0.001f)
        assertEquals(1f, state.pictureOpacity, 0.001f)
    }

    @Test
    fun `four argument update stores the clamped values`() {
        val state = TerminalBackgroundState()
        state.update(null, 60, 12f, 0.4f)
        assertEquals(60, state.dimPercent)
        assertEquals(12f, state.blurRadius, 0.001f)
        assertEquals(0.4f, state.pictureOpacity, 0.001f)
    }

    @Test
    fun `dim blur and opacity are clamped to their ranges`() {
        val state = TerminalBackgroundState()
        state.update(null, 200, -5f, 0f)
        assertEquals(90, state.dimPercent)
        assertEquals(0f, state.blurRadius, 0.001f)
        assertEquals(0.1f, state.pictureOpacity, 0.001f)

        state.update(null, -20, 40f, 2f)
        assertEquals(0, state.dimPercent)
        assertEquals(40f, state.blurRadius, 0.001f)
        assertEquals(1f, state.pictureOpacity, 0.001f)
    }
}
