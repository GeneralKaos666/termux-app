package com.termux.app.ui.appearance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for the appearance screen's state holder: edits are staged, nothing is written to
 * `colors.properties`, the font file or the preferences until `apply()`, and the preview follows the
 * staged font while a bad candidate keeps the font already installed.
 */
class AppearanceControllerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var colorsFile: File
    private lateinit var fontFile: File

    private class FakePreferences(
        var storedFontSize: Int = 14,
        var storedCursorStyle: Int? = null,
        var storedCursorBlinkRate: Int? = null,
        var propertyCursorStyle: Int? = null,
        var storedLigatures: Boolean = true,
    ) : AppearancePreferences {
        override fun getFontSize(): Int = storedFontSize
        override fun setFontSize(size: Int) {
            storedFontSize = size
        }

        override fun getCursorStyleOverride(): Int? = storedCursorStyle
        override fun getDefaultCursorStyle(): Int? = propertyCursorStyle
        override fun setCursorStyle(style: Int?) {
            storedCursorStyle = style
        }

        override fun getCursorBlinkRateOverride(): Int? = storedCursorBlinkRate
        override fun setCursorBlinkRate(rate: Int?) {
            storedCursorBlinkRate = rate
        }

        override fun getLigatures(): Boolean = storedLigatures
        override fun setLigatures(enabled: Boolean) {
            storedLigatures = enabled
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

    private fun controller(preferences: AppearancePreferences = FakePreferences()) =
        AppearanceController(store(preferences))

    private fun ttfBytes(): ByteArray = byteArrayOf(0x00, 0x01, 0x00, 0x00) + ByteArray(64)

    private fun installFont() {
        fontFile.writeBytes(ttfBytes())
    }

    // --- loading -----------------------------------------------------------------------------

    @Test
    fun `opening the screen reads the appearance without writing anything`() {
        val controller = controller()

        assertFalse(colorsFile.exists())
        assertFalse(fontFile.exists())
        assertEquals(ColorSchemes.TERMUX_DEFAULT.colors, controller.state.draft.colors)
        assertFalse(controller.hasUnsavedChanges)
    }

    @Test
    fun `preview font is the installed font when nothing is staged`() {
        installFont()

        val controller = controller()

        assertEquals(fontFile, controller.previewFontFile)
    }

    @Test
    fun `preview font is null when no font is installed`() {
        assertNull(controller().previewFontFile)
    }

    @Test
    fun `preview cursor follows the property default when nothing is staged`() {
        val controller = controller(FakePreferences(propertyCursorStyle = CursorStyle.BAR.value))

        assertEquals(CursorStyle.BAR, controller.previewCursorStyle)
    }

    @Test
    fun `preview cursor follows the staged override and Default again after clearing it`() {
        val controller = controller(FakePreferences(propertyCursorStyle = CursorStyle.BAR.value))

        controller.state.setCursorStyle(CursorStyle.UNDERLINE)
        assertEquals(CursorStyle.UNDERLINE, controller.previewCursorStyle)

        controller.state.setCursorStyle(null)
        assertEquals(CursorStyle.BAR, controller.previewCursorStyle)
    }

    // --- staging -----------------------------------------------------------------------------

    @Test
    fun `a staged colour change is not written until apply`() {
        val controller = controller()

        controller.state.setBackground(0xffffffff.toInt())

        assertTrue(controller.hasUnsavedChanges)
        assertFalse(colorsFile.exists())
    }

    @Test
    fun `a staged font size is not stored until apply`() {
        val preferences = FakePreferences()
        val controller = controller(preferences)

        controller.state.setFontSize(26)

        assertEquals(14, preferences.storedFontSize)

        controller.apply()
        assertEquals(26, preferences.storedFontSize)
    }

    @Test
    fun `a staged cursor setting is not stored until apply`() {
        val preferences = FakePreferences()
        val controller = controller(preferences)

        controller.state.setCursorStyle(CursorStyle.BAR)
        controller.state.setCursorBlinkRate(600)

        assertNull(preferences.storedCursorStyle)
        assertNull(preferences.storedCursorBlinkRate)
        assertTrue(controller.hasUnsavedChanges)
    }

    @Test
    fun `a chosen font is previewed before it is installed`() {
        val controller = controller()
        val source = temp.newFile("MyFont.ttf").apply { writeBytes(ttfBytes()) }

        controller.chooseFont(source)

        assertEquals(source, controller.previewFontFile)
        assertFalse("the font must not be installed before apply", fontFile.exists())
        assertTrue(controller.hasUnsavedChanges)
    }

    @Test
    fun `a chosen font does not write the installed font until apply`() {
        val controller = controller()
        val source = temp.newFile("MyFont.ttf").apply { writeBytes(ttfBytes()) }

        controller.chooseFont(source)

        assertFalse(fontFile.exists())
    }

    @Test
    fun `removing the font previews the monospace fallback`() {
        installFont()
        val controller = controller()

        controller.removeFont()

        assertNull(controller.previewFontFile)
        assertTrue(controller.hasUnsavedChanges)
        assertTrue("the font must stay on disk until apply", fontFile.exists())
    }

    @Test
    fun `removing a font that is not installed is not a change`() {
        val controller = controller()

        controller.removeFont()

        assertFalse(controller.hasUnsavedChanges)
        assertNull(controller.previewFontFile)
    }

    @Test
    fun `reset to default stages the default scheme without writing`() {
        val original = "background=#123456\n"
        colorsFile.writeText(original)
        val controller = controller()
        assertEquals(0xff123456.toInt(), controller.state.draft.colors.background)

        controller.resetToDefault()

        assertEquals(ColorSchemes.TERMUX_DEFAULT.colors, controller.state.draft.colors)
        assertTrue(controller.hasUnsavedChanges)
        assertEquals(original, colorsFile.readText())
    }

    @Test
    fun `discard drops staged edits and the pending font`() {
        val controller = controller()
        val source = temp.newFile("MyFont.ttf").apply { writeBytes(ttfBytes()) }
        controller.state.setFontSize(30)
        controller.chooseFont(source)

        controller.discard()

        assertEquals(controller.state.loaded, controller.state.draft)
        assertNull(controller.previewFontFile)
        assertFalse(controller.hasUnsavedChanges)
        assertFalse(fontFile.exists())
    }

    // --- applying ----------------------------------------------------------------------------

    @Test
    fun `apply writes the staged colours and clears the dirty flag`() {
        val controller = controller()
        controller.state.setBackground(0xffffffff.toInt())
        controller.state.setForeground(0xff000000.toInt())

        val result = controller.apply()

        assertNull(result.fontError)
        val text = colorsFile.readText()
        assertTrue(text, text.contains("background=#ffffff"))
        assertTrue(text, text.contains("foreground=#000000"))
        assertFalse(controller.hasUnsavedChanges)
    }

    @Test
    fun `apply of a colour change alone does not create a cursor override`() {
        val preferences = FakePreferences(storedCursorStyle = null, storedCursorBlinkRate = null)
        val controller = controller(preferences)

        controller.state.setBackground(0xffffffff.toInt())
        controller.apply()

        // The cursor must keep following termux.properties: colours and cursor are independent.
        assertNull(preferences.storedCursorStyle)
        assertNull(preferences.storedCursorBlinkRate)
    }

    @Test
    fun `apply clears a stored cursor override when Default is selected`() {
        val preferences = FakePreferences(storedCursorStyle = CursorStyle.BAR.value, storedCursorBlinkRate = 1200)
        val controller = controller(preferences)
        assertEquals(CursorStyle.BAR, controller.state.draft.cursorStyle)

        controller.state.setCursorStyle(null)
        controller.state.setCursorBlinkRate(null)
        controller.apply()

        assertNull(preferences.storedCursorStyle)
        assertNull(preferences.storedCursorBlinkRate)
    }

    @Test
    fun `apply installs the chosen font and previews the installed file`() {
        val controller = controller()
        val source = temp.newFile("MyFont.ttf").apply { writeBytes(ttfBytes()) }
        controller.chooseFont(source)

        val result = controller.apply()

        assertTrue(result.fontInstalled)
        assertNull(result.fontError)
        assertEquals(source.readBytes().toList(), fontFile.readBytes().toList())
        assertEquals(fontFile, controller.previewFontFile)
        assertEquals(fontFile.path, controller.state.draft.fontPath)
        assertFalse(controller.hasUnsavedChanges)
    }

    @Test
    fun `apply removes an installed font`() {
        installFont()
        val controller = controller()

        controller.removeFont()
        val result = controller.apply()

        assertFalse(fontFile.exists())
        assertFalse(result.fontInstalled)
        assertNull(controller.state.draft.fontPath)
        assertFalse(controller.hasUnsavedChanges)
    }

    @Test
    fun `apply keeps the previous font when the candidate is not a font`() {
        installFont()
        val previous = fontFile.readBytes().toList()
        val controller = controller()
        val source = temp.newFile("not-a-font.ttf").apply { writeText("hello") }
        controller.chooseFont(source)

        val result = controller.apply()

        assertEquals(FontError.INVALID, result.fontError)
        assertEquals(previous, fontFile.readBytes().toList())
        assertEquals(fontFile, controller.previewFontFile)
    }
}
