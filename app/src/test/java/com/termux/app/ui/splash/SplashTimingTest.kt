package com.termux.app.ui.splash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashTimingTest {

    @Test
    fun `splash is instant when animations are off`() {
        assertEquals(0, splashDurationMs(false))
    }

    @Test
    fun `splash is short when enabled`() {
        assertTrue(splashDurationMs(true) in 300..600)
    }
}
