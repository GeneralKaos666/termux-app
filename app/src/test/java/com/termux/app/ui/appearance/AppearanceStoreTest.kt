package com.termux.app.ui.appearance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for reading and writing the appearance: `colors.properties` (managed keys rewritten,
 * every other line kept), the custom font file, the existing font-size preference and the
 * app-local cursor preferences.
 */
class AppearanceStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var colorsFile: File
    private lateinit var fontFile: File

    private class FakePreferences(
        var storedFontSize: Int = 14,
        var storedCursorStyle: Int? = null,
        var storedCursorBlinkRate: Int? = null,
    ) : AppearancePreferences {
        override fun getFontSize(): Int = storedFontSize
        override fun setFontSize(size: Int) {
            storedFontSize = size
        }

        override fun getCursorStyle(): Int? = storedCursorStyle
        override fun setCursorStyle(style: Int?) {
            storedCursorStyle = style
        }

        override fun getCursorBlinkRate(): Int? = storedCursorBlinkRate
        override fun setCursorBlinkRate(rate: Int?) {
            storedCursorBlinkRate = rate
        }
    }

    @Before
    fun setUp() {
        colorsFile = File(temp.root, ".termux/colors.properties")
        fontFile = File(temp.root, ".termux/font.ttf")
        colorsFile.parentFile!!.mkdirs()
    }

    private fun store(preferences: AppearancePreferences = FakePreferences()): AppearanceStore =
        AppearanceStore(colorsFile, fontFile, preferences)

    private fun appearance(
        foreground: Int = 0xff112233.toInt(),
        background: Int = 0xff445566.toInt(),
        cursor: Int = 0xff778899.toInt(),
        fontSize: Int = 18,
        cursorStyle: CursorStyle = CursorStyle.UNDERLINE,
        cursorBlinkRate: Int = 600,
    ) = TerminalAppearance(
        colors = TerminalColorSet(foreground, background, cursor, List(16) { 0xff000000.toInt() + it }),
        fontPath = null,
        fontSize = fontSize,
        cursorStyle = cursorStyle,
        cursorBlinkRate = cursorBlinkRate,
    )

    // --- reading -----------------------------------------------------------------------------

    @Test
    fun `read falls back to the Termux default scheme when no colors file exists`() {
        val read = store().read()

        assertEquals(ColorSchemes.TERMUX_DEFAULT.colors, read.colors)
        assertNull(read.fontPath)
    }

    @Test
    fun `read parses the managed color keys`() {
        colorsFile.writeText(
            """
            foreground=#ff0000
            background=#00ff00
            cursor=#0000ff
            color0=#101010
            color15=#f0f0f0
            """.trimIndent() + "\n",
        )

        val colors = store().read().colors

        assertEquals(0xffff0000.toInt(), colors.foreground)
        assertEquals(0xff00ff00.toInt(), colors.background)
        assertEquals(0xff0000ff.toInt(), colors.cursor)
        assertEquals(0xff101010.toInt(), colors.ansi[0])
        assertEquals(0xfff0f0f0.toInt(), colors.ansi[15])
        assertEquals(ColorSchemes.TERMUX_DEFAULT.colors.ansi[7], colors.ansi[7])
    }

    @Test
    fun `read tolerates unmanaged color keys such as the extended palette`() {
        colorsFile.writeText(
            """
            foreground=#ff0000
            color16=#121212
            color255=#eeeeee
            """.trimIndent() + "\n",
        )

        assertEquals(0xffff0000.toInt(), store().read().colors.foreground)
    }

    @Test
    fun `read picks a contrasting cursor when the file does not set one`() {
        colorsFile.writeText("background=#000000\n")
        assertEquals(0xffffffff.toInt(), store().read().colors.cursor)

        colorsFile.writeText("background=#ffffff\n")
        assertEquals(0xff000000.toInt(), store().read().colors.cursor)
    }

    @Test
    fun `read falls back to the default for an unparseable value`() {
        colorsFile.writeText("foreground=not-a-color\nbackground=#123456\n")

        val read = store().read()

        assertEquals(ColorSchemes.TERMUX_DEFAULT.colors.foreground, read.colors.foreground)
        assertEquals(0xff123456.toInt(), read.colors.background)
    }

    @Test
    fun `read reports a custom font only when the font file has content`() {
        val read = store().read()
        assertNull(read.fontPath)

        fontFile.writeText("")
        assertNull(store().read().fontPath)

        fontFile.writeBytes(ttfBytes())
        assertEquals(fontFile.path, store().read().fontPath)
    }

    @Test
    fun `read takes font size and cursor settings from the preferences`() {
        val preferences = FakePreferences(storedFontSize = 21, storedCursorStyle = CursorStyle.BAR.value, storedCursorBlinkRate = 400)
        val read = store(preferences).read()

        assertEquals(21, read.fontSize)
        assertEquals(CursorStyle.BAR, read.cursorStyle)
        assertEquals(400, read.cursorBlinkRate)
    }

    @Test
    fun `read falls back to the default cursor when no preference is effective`() {
        val read = store(FakePreferences(storedCursorStyle = null, storedCursorBlinkRate = null)).read()

        assertEquals(CursorStyle.BLOCK, read.cursorStyle)
        assertEquals(0, read.cursorBlinkRate)
    }

    // --- writing -----------------------------------------------------------------------------

    @Test
    fun `write serializes the managed colors`() {
        store().write(appearance())

        val text = colorsFile.readText()
        assertTrue(text, text.contains("foreground=#112233"))
        assertTrue(text, text.contains("background=#445566"))
        assertTrue(text, text.contains("cursor=#778899"))
        assertTrue(text, text.contains("color0=#000000"))
        assertTrue(text, text.contains("color15=#00000f"))
    }

    @Test
    fun `write preserves comments and unmanaged keys`() {
        colorsFile.writeText(
            """
            # my hand written theme
            color16=#121212
            foreground=#ff0000

            color255=#eeeeee
            """.trimIndent() + "\n",
        )

        store().write(appearance())

        val lines = colorsFile.readLines()
        assertTrue(lines.contains("# my hand written theme"))
        assertTrue(lines.contains("color16=#121212"))
        assertTrue(lines.contains("color255=#eeeeee"))
        assertTrue(lines.contains("foreground=#112233"))
        assertEquals(1, lines.count { it.startsWith("foreground=") })
    }

    @Test
    fun `write of a missing file appends every managed key once`() {
        store().write(appearance())

        val lines = colorsFile.readLines().filter { it.isNotBlank() }
        val managed = listOf("foreground", "background", "cursor") + (0..15).map { "color$it" }
        assertEquals(managed, lines.map { it.substringBefore('=') })
        assertEquals(managed.size, lines.size)
    }

    @Test
    fun `write then read is stable`() {
        val store = store()
        val written = appearance()
        store.write(written)

        val read = store.read()
        assertEquals(written.colors, read.colors)
        assertEquals(written.fontSize, read.fontSize)
        assertEquals(written.cursorStyle, read.cursorStyle)
        assertEquals(written.cursorBlinkRate, read.cursorBlinkRate)

        store.write(read)
        assertEquals(read.colors, store.read().colors)
    }

    @Test
    fun `write stores the font size and the cursor preferences`() {
        val preferences = FakePreferences()
        store(preferences).write(appearance(fontSize = 26, cursorStyle = CursorStyle.BAR, cursorBlinkRate = 1200))

        assertEquals(26, preferences.storedFontSize)
        assertEquals(CursorStyle.BAR.value, preferences.storedCursorStyle)
        assertEquals(1200, preferences.storedCursorBlinkRate)
    }

    @Test
    fun `write stores an out of range blink rate as disabled`() {
        val preferences = FakePreferences()
        store(preferences).write(appearance(cursorBlinkRate = 50))

        assertEquals(0, preferences.storedCursorBlinkRate)
    }

    // --- font handling -----------------------------------------------------------------------

    @Test
    fun `write installs a valid custom font`() {
        val source = temp.newFile("MyFont.ttf").apply { writeBytes(ttfBytes()) }
        val store = store()

        val result = store.write(appearance(), fontSource = source)

        assertNull(result.fontError)
        assertTrue(result.fontInstalled)
        assertEquals(source.readBytes().toList(), fontFile.readBytes().toList())
    }

    @Test
    fun `write keeps the previous font when the candidate is not a font`() {
        fontFile.writeBytes(ttfBytes())
        val previous = fontFile.readBytes().toList()
        val source = temp.newFile("not-a-font.ttf").apply { writeText("hello") }
        val store = store()

        val result = store.write(appearance(), fontSource = source)

        assertEquals(FontError.INVALID, result.fontError)
        assertTrue(result.fontInstalled)
        assertEquals(previous, fontFile.readBytes().toList())
    }

    @Test
    fun `write rejects an oversized font`() {
        val source = temp.newFile("huge.ttf")
        source.outputStream().use { out ->
            out.write(ttfBytes())
            out.write(ByteArray((FontValidator.MAX_FONT_BYTES - 4 + 1).toInt()))
        }
        val store = store()

        val result = store.write(appearance(), fontSource = source)

        assertEquals(FontError.TOO_LARGE, result.fontError)
        assertEquals(false, fontFile.exists())
    }

    @Test
    fun `write reports a missing candidate file`() {
        val store = store()

        val result = store.write(appearance(), fontSource = File(temp.root, "gone.ttf"))

        assertEquals(FontError.NOT_FOUND, result.fontError)
        assertEquals(false, result.fontInstalled)
    }

    @Test
    fun `write without a candidate leaves the installed font alone`() {
        fontFile.writeBytes(ttfBytes())
        val store = store()

        val result = store.write(appearance(), fontSource = null)

        assertNull(result.fontError)
        assertTrue(result.fontInstalled)
        assertTrue(fontFile.exists())
    }

    @Test
    fun `remove font deletes the installed file and is idempotent`() {
        fontFile.writeBytes(ttfBytes())
        val store = store()

        assertTrue(store.removeFont())
        assertEquals(false, fontFile.exists())
        assertTrue(store.removeFont())
        assertNull(store.read().fontPath)
    }

    @Test
    fun `font validator accepts the known font signatures`() {
        listOf(
            byteArrayOf(0x00, 0x01, 0x00, 0x00),
            "true".toByteArray(),
            "OTTO".toByteArray(),
            "ttcf".toByteArray(),
        ).forEach { signature ->
            val file = temp.newFile()
            file.writeBytes(signature)
            assertNull("signature ${signature.joinToString { "%02x".format(it) }}", FontValidator.validate(file))
        }
        assertNotNull(FontValidator.validate(temp.newFile()))
    }

    private fun ttfBytes(): ByteArray = byteArrayOf(0x00, 0x01, 0x00, 0x00) + ByteArray(64)
}
