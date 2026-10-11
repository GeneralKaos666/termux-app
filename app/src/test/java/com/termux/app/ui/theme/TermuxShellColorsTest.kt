package com.termux.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the A5 shell fallback palette (dark chrome with readable contrast). */
class TermuxShellColorsTest {

    @Test
    fun `fallback chrome is dark`() {
        assertEquals(Color(0xFF000000), TermuxFallbackColors.background)
        assertEquals(Color(0xFF000000), TermuxFallbackColors.surface)
    }

    @Test
    fun `fallback content contrasts against its surfaces`() {
        assertTrue(contrast(TermuxFallbackColors.onBackground, TermuxFallbackColors.background) >= 4.5f)
        assertTrue(contrast(TermuxFallbackColors.onSurface, TermuxFallbackColors.surface) >= 4.5f)
        assertTrue(contrast(TermuxFallbackColors.onSurfaceVariant, TermuxFallbackColors.surfaceVariant) >= 4.5f)
        assertTrue(contrast(TermuxFallbackColors.onPrimaryContainer, TermuxFallbackColors.primaryContainer) >= 4.5f)
        assertTrue(contrast(TermuxFallbackColors.onSecondaryContainer, TermuxFallbackColors.secondaryContainer) >= 4.5f)
    }

    /** WCAG relative luminance contrast ratio. */
    private fun contrast(a: Color, b: Color): Float {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    private fun luminance(c: Color): Float {
        fun channel(s: Float): Float =
            if (s <= 0.03928f) s / 12.92f
            else Math.pow(((s + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        return 0.2126f * channel(c.red) + 0.7152f * channel(c.green) + 0.0722f * channel(c.blue)
    }
}
