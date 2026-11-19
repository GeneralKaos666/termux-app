package com.termux.app.ui.sessions

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionsBarLayoutTest {

    @Test
    fun `leading edge is unfaded at the start`() {
        assertEquals(
            0f,
            edgeFadeAlpha(scrollFraction = 0f, atStart = true, atEnd = false, leading = true),
            0.001f,
        )
    }

    @Test
    fun `mid scroll fades both edges`() {
        assertEquals(1f, edgeFadeAlpha(0.5f, atStart = false, atEnd = false, leading = true), 0.001f)
        assertEquals(1f, edgeFadeAlpha(0.5f, atStart = false, atEnd = false, leading = false), 0.001f)
    }

    @Test
    fun `trailing edge is unfaded at the end`() {
        assertEquals(
            0f,
            edgeFadeAlpha(scrollFraction = 1f, atStart = false, atEnd = true, leading = false),
            0.001f,
        )
    }

    @Test
    fun `a negligible scroll fades nothing`() {
        assertEquals(0f, edgeFadeAlpha(0.001f, atStart = false, atEnd = false, leading = true), 0.001f)
    }

    @Test
    fun `a fully visible tab asks for no scroll`() {
        assertEquals(
            0,
            minimalScrollDelta(tabOffset = 40, tabSize = 60, viewportStart = 20, viewportEnd = 200),
        )
    }

    @Test
    fun `a tab starting before the viewport asks for the leading overlap`() {
        assertEquals(
            -10,
            minimalScrollDelta(tabOffset = 10, tabSize = 60, viewportStart = 20, viewportEnd = 200),
        )
    }

    @Test
    fun `a tab ending past the viewport asks for the trailing overlap`() {
        assertEquals(
            30,
            minimalScrollDelta(tabOffset = 170, tabSize = 60, viewportStart = 20, viewportEnd = 200),
        )
    }

    @Test
    fun `a tab wider than the viewport asks for the leading overlap`() {
        assertEquals(
            -40,
            minimalScrollDelta(tabOffset = -20, tabSize = 400, viewportStart = 20, viewportEnd = 200),
        )
    }
}
