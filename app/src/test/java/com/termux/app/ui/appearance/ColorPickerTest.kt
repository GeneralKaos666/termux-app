package com.termux.app.ui.appearance

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the hand-written HSV colour maths behind the picker. The conversions mirror
 * `android.graphics.Color.colorToHSV`/`HSVToColor` so the screen needs no dependency (and no
 * `android.graphics.Color`, which plain JVM tests cannot execute).
 */
class ColorPickerTest {

    @Test
    fun `rgb to hsv of the primaries`() {
        assertEquals(HsvColor(0f, 1f, 1f), rgbToHsv(0xffff0000.toInt()))
        assertEquals(HsvColor(120f, 1f, 1f), rgbToHsv(0xff00ff00.toInt()))
        assertEquals(HsvColor(240f, 1f, 1f), rgbToHsv(0xff0000ff.toInt()))
        assertEquals(HsvColor(60f, 1f, 1f), rgbToHsv(0xffffff00.toInt()))
        assertEquals(HsvColor(300f, 1f, 1f), rgbToHsv(0xffff00ff.toInt()))
        assertEquals(HsvColor(180f, 1f, 1f), rgbToHsv(0xff00ffff.toInt()))
    }

    @Test
    fun `rgb to hsv of greys has no hue or saturation`() {
        val black = rgbToHsv(0xff000000.toInt())
        assertEquals(0f, black.hue, 0.001f)
        assertEquals(0f, black.saturation, 0.001f)
        assertEquals(0f, black.value, 0.001f)

        val white = rgbToHsv(0xffffffff.toInt())
        assertEquals(0f, white.hue, 0.001f)
        assertEquals(0f, white.saturation, 0.001f)
        assertEquals(1f, white.value, 0.001f)

        val mid = rgbToHsv(0xff808080.toInt())
        assertEquals(0f, mid.hue, 0.001f)
        assertEquals(0f, mid.saturation, 0.001f)
        assertEquals(128f / 255f, mid.value, 0.001f)
    }

    @Test
    fun `hsv to rgb of the primaries`() {
        assertEquals(0xffff0000.toInt(), hsvToRgb(HsvColor(0f, 1f, 1f)))
        assertEquals(0xff00ff00.toInt(), hsvToRgb(HsvColor(120f, 1f, 1f)))
        assertEquals(0xff0000ff.toInt(), hsvToRgb(HsvColor(240f, 1f, 1f)))
        assertEquals(0xffffff00.toInt(), hsvToRgb(HsvColor(60f, 1f, 1f)))
    }

    @Test
    fun `hsv to rgb of greys ignores hue`() {
        assertEquals(0xffffffff.toInt(), hsvToRgb(HsvColor(0f, 0f, 1f)))
        assertEquals(0xff000000.toInt(), hsvToRgb(HsvColor(123f, 0f, 0f)))
        assertEquals(0xff808080.toInt(), hsvToRgb(HsvColor(210f, 0f, 128f / 255f)))
    }

    @Test
    fun `hsv to rgb wraps hue outside the circle`() {
        assertEquals(0xffff0000.toInt(), hsvToRgb(HsvColor(360f, 1f, 1f)))
        assertEquals(0xffff00ff.toInt(), hsvToRgb(HsvColor(-60f, 1f, 1f)))
        assertEquals(0xffff0000.toInt(), hsvToRgb(HsvColor(720f, 1f, 1f)))
    }

    @Test
    fun `hsv to rgb clamps saturation and value`() {
        // Saturation above 1 clamps to 1: hue 0 at half value is mid red.
        assertEquals(0xff800000.toInt(), hsvToRgb(HsvColor(0f, 2f, 0.5f)))
        // Value above 1 clamps to 1.
        assertEquals(0xffff0000.toInt(), hsvToRgb(HsvColor(0f, 1f, 2f)))
        // Negative values clamp to 0.
        assertEquals(0xff000000.toInt(), hsvToRgb(HsvColor(0f, -1f, -1f)))
    }

    @Test
    fun `rgb to hsv round trips through hsv to rgb`() {
        val colors = listOf(0xff123456, 0xffabcdef, 0xff6495ed, 0xff00cd00, 0xffe5e5e5, 0xff7f7f7f)
        for (color in colors) {
            assertEquals(
                "round trip for #%06x".format(color),
                color.toInt(),
                hsvToRgb(rgbToHsv(color.toInt())),
            )
        }
    }

    @Test
    fun `rgb to hsv ignores alpha`() {
        assertEquals(rgbToHsv(0x00123456), rgbToHsv(0xff123456.toInt()))
        assertEquals(0xff123456.toInt(), hsvToRgb(rgbToHsv(0xff123456.toInt())) or 0xff000000.toInt())
    }
}
