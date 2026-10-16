package com.termux.app.ui.appearance

import android.content.Context
import com.termux.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

/**
 * Tests the precedence the terminal uses for the cursor: the app-local preference the appearance
 * screen writes wins, and the `termux.properties` value is the fallback until one is set. The
 * property source is injected so the rule is tested without reading a real `termux.properties`.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CursorPreferencesTest {

    private val propertyStyle = TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE
    private val propertyBlinkRate = 500

    private fun context(): Context = RuntimeEnvironment.getApplication()

    private fun preferences(): CursorPreferences = CursorPreferences(
        context = context(),
        propertyCursorStyle = { propertyStyle },
        propertyCursorBlinkRate = { propertyBlinkRate },
    )

    @Test
    fun `with no stored preference the cursor falls back to the property value`() {
        val cursor = preferences()

        assertEquals(propertyStyle, cursor.cursorStyle())
        assertEquals(propertyBlinkRate, cursor.cursorBlinkRate())
    }

    @Test
    fun `a stored preference wins over the property value`() {
        val cursor = preferences()

        cursor.setCursorStyle(CursorStyle.BAR.value)
        cursor.setCursorBlinkRate(250)

        assertEquals(CursorStyle.BAR.value, cursor.cursorStyle())
        assertEquals(250, cursor.cursorBlinkRate())
    }

    @Test
    fun `clearing the preference falls back to the property value again`() {
        val cursor = preferences()

        cursor.setCursorStyle(CursorStyle.BLOCK.value)
        cursor.setCursorBlinkRate(0)
        cursor.setCursorStyle(null)
        cursor.setCursorBlinkRate(null)

        assertEquals(propertyStyle, cursor.cursorStyle())
        assertEquals(propertyBlinkRate, cursor.cursorBlinkRate())
    }

    @Test
    fun `with no preference and no property the cursor setting is unknown`() {
        val cursor = CursorPreferences(
            context = context(),
            propertyCursorStyle = { null },
            propertyCursorBlinkRate = { null },
        )

        assertNull(cursor.cursorStyle())
        assertNull(cursor.cursorBlinkRate())
    }

    @Test
    fun `the override getters report whether an app-local preference exists`() {
        val cursor = preferences()

        assertNull(cursor.cursorStyleOverride())
        assertNull(cursor.cursorBlinkRateOverride())

        cursor.setCursorStyle(CursorStyle.BAR.value)
        cursor.setCursorBlinkRate(0)

        assertEquals(CursorStyle.BAR.value, cursor.cursorStyleOverride())
        assertEquals(0, cursor.cursorBlinkRateOverride())
    }

    @Test
    fun `the default cursor style reports the property value`() {
        val cursor = preferences()

        assertEquals(propertyStyle, cursor.defaultCursorStyle())
    }
}
