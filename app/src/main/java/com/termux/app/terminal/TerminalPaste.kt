package com.termux.app.terminal

import com.termux.terminal.TerminalEmulator

/** Pastes [text] into [emulator]; false when there is nothing to paste into or nothing to paste. */
internal fun pasteText(emulator: TerminalEmulator?, text: String?): Boolean {
    if (emulator == null || text == null) return false
    emulator.paste(text)
    return true
}
