package com.termux.app.ui.floating

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for the pure float window geometry helpers. */
class FloatWindowGeometryTest {

    @Test
    fun `windowType selects the application overlay on SDK 26 and above`() {
        assertEquals(FloatWindowGeometry.TYPE_APPLICATION_OVERLAY, FloatWindowGeometry.windowType(26))
        assertEquals(FloatWindowGeometry.TYPE_APPLICATION_OVERLAY, FloatWindowGeometry.windowType(28))
        assertEquals(FloatWindowGeometry.TYPE_APPLICATION_OVERLAY, FloatWindowGeometry.windowType(33))
    }

    @Test
    fun `windowType selects the legacy phone type below SDK 26`() {
        assertEquals(FloatWindowGeometry.TYPE_PHONE, FloatWindowGeometry.windowType(21))
        assertEquals(FloatWindowGeometry.TYPE_PHONE, FloatWindowGeometry.windowType(24))
    }

    @Test
    fun `clampX clamps past the right edge to the maximum`() {
        assertEquals(200, FloatWindowGeometry.clampX(500, 200, 400))
    }

    @Test
    fun `clampX clamps negative x to zero`() {
        assertEquals(0, FloatWindowGeometry.clampX(-5, 200, 400))
    }

    @Test
    fun `clampX passes through a value already within bounds`() {
        assertEquals(120, FloatWindowGeometry.clampX(120, 200, 400))
    }

    @Test
    fun `clampY clamps past the bottom edge to the maximum`() {
        assertEquals(200, FloatWindowGeometry.clampY(500, 200, 400))
    }

    @Test
    fun `clampY clamps negative y to zero`() {
        assertEquals(0, FloatWindowGeometry.clampY(-5, 200, 400))
    }

    @Test
    fun `clampY passes through a value already within bounds`() {
        assertEquals(120, FloatWindowGeometry.clampY(120, 200, 400))
    }

    @Test
    fun `clampWidth clamps below the minimum up to the minimum`() {
        assertEquals(200, FloatWindowGeometry.clampWidth(50, 200, 1000))
    }

    @Test
    fun `clampWidth clamps above the maximum down to the maximum`() {
        assertEquals(1000, FloatWindowGeometry.clampWidth(1200, 200, 1000))
    }

    @Test
    fun `clampWidth passes through a value already within bounds`() {
        assertEquals(500, FloatWindowGeometry.clampWidth(500, 200, 1000))
    }

    @Test
    fun `clampHeight clamps below the minimum up to the minimum`() {
        assertEquals(100, FloatWindowGeometry.clampHeight(50, 100, 800))
    }

    @Test
    fun `clampHeight clamps above the maximum down to the maximum`() {
        assertEquals(800, FloatWindowGeometry.clampHeight(1200, 100, 800))
    }

    @Test
    fun `clampHeight passes through a value already within bounds`() {
        assertEquals(400, FloatWindowGeometry.clampHeight(400, 100, 800))
    }
}
