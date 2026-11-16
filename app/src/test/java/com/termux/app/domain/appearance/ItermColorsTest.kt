package com.termux.app.domain.appearance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

/**
 * Tests for the iTerm2 color-preset reader and writer. Robolectric supplies `XmlPullParser`, which
 * plain JVM unit tests do not have.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ItermColorsTest {

    private fun solarizedFixture(): String =
        javaClass.getResourceAsStream("/iterm/solarized.itermcolors")!!
            .bufferedReader()
            .readText()

    @Test
    fun `parses solarized fixture`() {
        val colors = parseItermColors(solarizedFixture())
        assertNotNull(colors)
        colors!!
        assertEquals(0xff073642.toInt(), colors.ansi[0])
        assertEquals(0xffdc322f.toInt(), colors.ansi[1])
        assertEquals(0xfffdf6e3.toInt(), colors.ansi[15])
        assertEquals(0xff839496.toInt(), colors.foreground)
        assertEquals(0xff002b36.toInt(), colors.background)
        assertEquals(0xff839496.toInt(), colors.cursor)
        // Only the 16 ANSI entries exist in the file; the extended palette is the engine default.
        assertEquals(INDEXED_COLOR_COUNT, colors.ansi.size)
        assertEquals(0xff000000.toInt(), colors.ansi[16])
        assertEquals(0xffeeeeee.toInt(), colors.ansi[255])
    }

    @Test
    fun `missing ansi key returns null`() {
        val broken = solarizedFixture().replace("<key>Ansi 9 Color</key>", "<key>Ansi Nine Color</key>")
        assertNull(parseItermColors(broken))
    }

    @Test
    fun `channel above one returns null`() {
        val broken = solarizedFixture().replaceFirst("<real>0.25882352941176473</real>", "<real>1.5</real>")
        assertNull(parseItermColors(broken))
    }

    @Test
    fun `missing background returns null`() {
        val broken = solarizedFixture().replace("<key>Background Color</key>", "<key>Backdrop Color</key>")
        assertNull(parseItermColors(broken))
    }

    @Test
    fun `malformed xml returns null`() {
        assertNull(parseItermColors("not a plist"))
    }

    @Test
    fun `export then parse round trips`() {
        val original = ColorSchemes.TERMUX_DEFAULT.colors
        val exported = exportItermColors(original)
        assertTrue(exported.contains("<key>Ansi 0 Color</key>"))
        assertTrue(exported.contains("<key>Foreground Color</key>"))
        assertEquals(original, parseItermColors(exported))
    }
}
