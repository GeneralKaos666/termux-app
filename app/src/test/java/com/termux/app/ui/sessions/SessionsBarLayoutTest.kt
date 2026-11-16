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
}
