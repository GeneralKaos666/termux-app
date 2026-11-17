package com.termux.app.terminal

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class TerminalPasteTest {

    @Test
    fun `no emulator and no text are no-ops`() {
        assertFalse(pasteText(null, "ls"))
        assertFalse(pasteText(null, null))
    }

    @Test
    fun `text reaches a live emulator`() {
        val emulator = TerminalEmulator(NoOutput(), 80, 24, 13, 15, 48, null)
        assertTrue(pasteText(emulator, "ls"))
        assertFalse(pasteText(emulator, null))
    }

    private class NoOutput : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) = Unit
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }
}
