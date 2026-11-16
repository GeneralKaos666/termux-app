package com.termux.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellChromeStateTest {

    @Test
    fun `transparency maps to the inverse background alpha`() {
        assertEquals(1f, chromeBackgroundAlpha(0), 0f)
        assertEquals(0.7f, chromeBackgroundAlpha(30), 0f)
        assertEquals(0f, chromeBackgroundAlpha(100), 0f)
    }

    @Test
    fun `out-of-range percentages are clamped to the 0-100 range`() {
        assertEquals(1f, chromeBackgroundAlpha(-10), 0f)
        assertEquals(0f, chromeBackgroundAlpha(250), 0f)
    }

    @Test
    fun `a bell flash starts on and clears`() {
        val state = ShellChromeState()
        assertEquals(false, state.bellFlash)

        state.flashBell()
        assertEquals(true, state.bellFlash)
        assertEquals(1, state.bellSequence)

        state.clearBellFlash()
        assertEquals(false, state.bellFlash)
        assertEquals(1, state.bellSequence)
    }

    @Test
    fun `a repeated bell bumps the sequence so the host restarts its timer`() {
        val state = ShellChromeState()
        state.flashBell()
        state.flashBell()
        assertEquals(2, state.bellSequence)
    }

    @Test
    fun `state clamps the stored percentage`() {
        val state = ShellChromeState()
        assertEquals(30, state.barTransparencyPercent)

        state.update(75)
        assertEquals(75, state.barTransparencyPercent)

        state.update(-5)
        assertEquals(0, state.barTransparencyPercent)

        state.update(101)
        assertEquals(100, state.barTransparencyPercent)
    }
}
