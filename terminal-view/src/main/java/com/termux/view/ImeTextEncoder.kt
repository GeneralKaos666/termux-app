package com.termux.view

import com.termux.terminal.TerminalEmulator

/**
 * Turns the text an IME commits into the code points and control state a terminal expects.
 *
 * Pure encoding only, no `android.*` types, so the IME text path is unit tested without a
 * device. One [KeyStroke] per code point, in input order.
 */
object ImeTextEncoder {

    /** One code point to feed to `TerminalView.inputCodePoint`. */
    class KeyStroke(
        @JvmField val codePoint: Int,
        @JvmField val controlHeld: Boolean,
    )

    @JvmStatic
    fun encode(text: CharSequence, shiftDown: Boolean): List<KeyStroke> {
        val textLengthInChars = text.length
        val strokes = ArrayList<KeyStroke>(textLengthInChars)
        var i = 0
        while (i < textLengthInChars) {
            val firstChar = text[i]
            var codePoint: Int
            if (Character.isHighSurrogate(firstChar)) {
                // A high surrogate is only a valid lead when a low surrogate follows it. Otherwise emit the
                // replacement character and leave the next char untouched, so a malformed pair does not turn
                // into an unrelated code point and does not swallow the character after it.
                if (i + 1 < textLengthInChars && Character.isLowSurrogate(text[i + 1])) {
                    codePoint = Character.toCodePoint(firstChar, text[i + 1])
                    i++
                } else {
                    codePoint = TerminalEmulator.UNICODE_REPLACEMENT_CHAR
                }
            } else if (Character.isLowSurrogate(firstChar)) {
                // A lone low surrogate would reach TerminalSession.writeCodePoint, which rejects the surrogate
                // range, so substitute it here.
                codePoint = TerminalEmulator.UNICODE_REPLACEMENT_CHAR
            } else {
                codePoint = firstChar.code
            }

            // Check onKeyDown() for details.
            if (shiftDown) {
                codePoint = Character.toUpperCase(codePoint)
            }

            var ctrlHeld = false
            if (codePoint <= 31 && codePoint != 27) {
                if (codePoint == '\n'.code) {
                    // The AOSP keyboard and descendants seems to send \n as text when the enter key is pressed,
                    // instead of a key event like most other keyboard apps. A terminal expects \r for the enter
                    // key (although when icrnl is enabled this doesn't make a difference - run 'stty -icrnl' to
                    // check the behaviour).
                    codePoint = '\r'.code
                }

                // E.g. penti keyboard for ctrl input.
                ctrlHeld = true
                codePoint = when (codePoint) {
                    31 -> '_'.code
                    30 -> '^'.code
                    29 -> ']'.code
                    28 -> '\\'.code
                    else -> codePoint + 96
                }
            }

            strokes.add(KeyStroke(codePoint, ctrlHeld))
            i++
        }
        return strokes
    }
}
