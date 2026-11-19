package com.termux.app.ui.splash

import org.junit.Assert.assertEquals
import org.junit.Test

class TypewriterTimingTest {

    @Test
    fun `short text keeps the floor duration`() {
        assertEquals(240, typingDurationMs(1))
        assertEquals(240, typingDurationMs(7))
    }

    @Test
    fun `duration scales with the character count`() {
        assertEquals(20 * 32, typingDurationMs(20))
        assertEquals(28 * 32, typingDurationMs(28))
    }

    @Test
    fun `long text is capped`() {
        assertEquals(900, typingDurationMs(29))
        assertEquals(900, typingDurationMs(200))
    }
}
