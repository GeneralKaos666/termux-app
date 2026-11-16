package com.termux.app.ui.palette

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the command palette's keyboard shortcut. */
class PaletteShortcutTest {

    @Test
    fun `ctrl shift k opens the palette`() {
        assertTrue(isPaletteShortcut(KeyEvent.KEYCODE_K, ctrl = true, shift = true, alt = false))
    }

    @Test
    fun `plain ctrl k belongs to the shell`() {
        assertFalse(isPaletteShortcut(KeyEvent.KEYCODE_K, ctrl = true, shift = false, alt = false))
    }

    @Test
    fun `ctrl alt k belongs to the shell`() {
        // Ctrl+Alt+K arrives at readline as ESC Ctrl-K, i.e. kill-line: it must not be swallowed.
        assertFalse(isPaletteShortcut(KeyEvent.KEYCODE_K, ctrl = true, shift = false, alt = true))
    }

    @Test
    fun `all three modifiers is not the shortcut`() {
        assertFalse(isPaletteShortcut(KeyEvent.KEYCODE_K, ctrl = true, shift = true, alt = true))
    }

    @Test
    fun `another key with the same modifiers is not the shortcut`() {
        assertFalse(isPaletteShortcut(KeyEvent.KEYCODE_J, ctrl = true, shift = true, alt = false))
    }

    @Test
    fun `the shortcut needs ctrl`() {
        assertFalse(isPaletteShortcut(KeyEvent.KEYCODE_K, ctrl = false, shift = true, alt = false))
    }
}
