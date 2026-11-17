package com.termux.view

/**
 * Maps a typed code point to the bytes a terminal expects.
 *
 * Pure functions only, no `android.*` types, so the mapping is unit tested without a device.
 */
object KeyCodePoints {

    /**
     * Applies the control-key mapping: a letter becomes its C0 control code, and the punctuation keys
     * that traditionally produce controls (including the digit row variants) map to the same values.
     * Anything unmapped is returned unchanged.
     */
    @JvmStatic
    fun applyControl(codePoint: Int): Int {
        var result = codePoint
        if (result in 'a'.code..'z'.code) {
            result = result - 'a'.code + 1
        } else if (result in 'A'.code..'Z'.code) {
            result = result - 'A'.code + 1
        } else if (result == ' '.code || result == '2'.code) {
            result = 0
        } else if (result == '['.code || result == '3'.code) {
            result = 27 // ^[ (Esc)
        } else if (result == '\\'.code || result == '4'.code) {
            result = 28
        } else if (result == ']'.code || result == '5'.code) {
            result = 29
        } else if (result == '^'.code || result == '6'.code) {
            result = 30 // control-^
        } else if (result == '_'.code || result == '7'.code || result == '/'.code) {
            // "Ctrl-/ sends 0x1f which is equivalent of Ctrl-_ since the days of VT102"
            // - http://apple.stackexchange.com/questions/24261/how-do-i-send-c-that-is-control-slash-to-the-terminal
            result = 31
        } else if (result == '8'.code) {
            result = 127 // DEL
        }
        return result
    }

    /**
     * Work around bluetooth keyboards sending funny unicode characters instead of the more normal ones
     * from ASCII that terminal programs expect - the desire to input the original characters should be
     * low.
     *
     * Only for non-soft keyboards: the caller gates this on the event source, because a soft keyboard
     * may legitimately type one of these characters.
     */
    @JvmStatic
    fun normalizeExternalKeyboard(codePoint: Int): Int {
        return when (codePoint) {
            0x02DC -> // SMALL TILDE.
                0x007E // TILDE (~).

            0x02CB -> // MODIFIER LETTER GRAVE ACCENT.
                0x0060 // GRAVE ACCENT (`).

            0x02C6 -> // MODIFIER LETTER CIRCUMFLEX ACCENT.
                0x005E // CIRCUMFLEX ACCENT (^).

            else -> codePoint
        }
    }
}
