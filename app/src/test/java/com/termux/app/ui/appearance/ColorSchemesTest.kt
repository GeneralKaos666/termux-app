package com.termux.app.ui.appearance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the bundled color scheme presets. */
class ColorSchemesTest {

    /** Every preset must carry all 18 managed colors as opaque, serializable hex. */
    @Test
    fun `every preset is complete and serializable`() {
        ColorSchemes.presets.forEach { preset ->
            assertTrue("preset name is blank", preset.name.isNotBlank())
            assertEquals("${preset.name} ansi count", 16, preset.colors.ansi.size)

            val all = listOf(preset.colors.foreground, preset.colors.background, preset.colors.cursor) +
                preset.colors.ansi
            all.forEach { color ->
                val hex = colorToHex(color)
                assertEquals("$color is not opaque", 0xff, color ushr 24 and 0xff)
                assertEquals("$hex does not round trip", color, parseColor(hex))
                assertTrue("$hex is not six digits", Regex("^#[0-9a-f]{6}$").matches(hex))
            }
        }
    }

    @Test
    fun `the expected presets are offered`() {
        assertEquals(
            listOf(
                "Termux Default",
                "Nord",
                "Dracula",
                "Solarized Dark",
                "Solarized Light",
                "Gruvbox Dark",
                "One Dark",
            ),
            ColorSchemes.presets.map { it.name },
        )
    }

    @Test
    fun `preset names are unique`() {
        val names = ColorSchemes.presets.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `the Termux default preset matches the engine default scheme`() {
        val colors = ColorSchemes.TERMUX_DEFAULT.colors
        assertEquals(0xffffffff.toInt(), colors.foreground)
        assertEquals(0xff000000.toInt(), colors.background)
        assertEquals(0xffffffff.toInt(), colors.cursor)
        assertEquals(0xff000000.toInt(), colors.ansi[0])
        assertEquals(0xffcd0000.toInt(), colors.ansi[1])
        assertEquals(0xff6495ed.toInt(), colors.ansi[4])
        assertEquals(0xff7f7f7f.toInt(), colors.ansi[8])
        assertEquals(0xff5c5cff.toInt(), colors.ansi[12])
        assertEquals(0xffffffff.toInt(), colors.ansi[15])
    }

    @Test
    fun `presets are looked up by name`() {
        assertEquals(ColorSchemes.DRACULA, ColorSchemes.byName("Dracula"))
        assertNull(ColorSchemes.byName("No Such Scheme"))
    }

    @Test
    fun `the Nord palette matches the published values`() {
        val colors = ColorSchemes.NORD.colors
        assertEquals(0xffd8dee9.toInt(), colors.foreground)
        assertEquals(0xff2e3440.toInt(), colors.background)
        assertEquals(0xffd8dee9.toInt(), colors.cursor)
        assertEquals(0xffbf616a.toInt(), colors.ansi[1])
        assertEquals(0xff8fbcbb.toInt(), colors.ansi[14])
    }

    @Test
    fun `the Dracula palette matches the published values`() {
        val colors = ColorSchemes.DRACULA.colors
        assertEquals(0xfff8f8f2.toInt(), colors.foreground)
        assertEquals(0xff282a36.toInt(), colors.background)
        assertEquals(0xff50fa7b.toInt(), colors.ansi[2])
        assertEquals(0xff6272a4.toInt(), colors.ansi[8])
    }
}
