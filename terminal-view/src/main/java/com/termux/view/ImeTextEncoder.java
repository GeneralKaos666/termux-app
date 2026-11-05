package com.termux.view;

import com.termux.terminal.TerminalEmulator;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the text an IME commits into the code points and control state a terminal expects.
 *
 * <p>Pure encoding only, no {@code android.*} types, so the IME text path is unit tested without a
 * device. One {@link KeyStroke} per code point, in input order.
 */
final class ImeTextEncoder {

    private ImeTextEncoder() {
    }

    /** One code point to feed to {@code TerminalView.inputCodePoint}. */
    static final class KeyStroke {
        final int codePoint;
        final boolean controlHeld;

        KeyStroke(int codePoint, boolean controlHeld) {
            this.codePoint = codePoint;
            this.controlHeld = controlHeld;
        }
    }

    static List<KeyStroke> encode(CharSequence text, boolean shiftDown) {
        final int textLengthInChars = text.length();
        List<KeyStroke> strokes = new ArrayList<>(textLengthInChars);
        for (int i = 0; i < textLengthInChars; i++) {
            char firstChar = text.charAt(i);
            int codePoint;
            if (Character.isHighSurrogate(firstChar)) {
                // A high surrogate is only a valid lead when a low surrogate follows it. Otherwise emit the
                // replacement character and leave the next char untouched, so a malformed pair does not turn
                // into an unrelated code point and does not swallow the character after it.
                if (i + 1 < textLengthInChars && Character.isLowSurrogate(text.charAt(i + 1))) {
                    codePoint = Character.toCodePoint(firstChar, text.charAt(i + 1));
                    i++;
                } else {
                    codePoint = TerminalEmulator.UNICODE_REPLACEMENT_CHAR;
                }
            } else if (Character.isLowSurrogate(firstChar)) {
                // A lone low surrogate would reach TerminalSession.writeCodePoint, which rejects the surrogate
                // range, so substitute it here.
                codePoint = TerminalEmulator.UNICODE_REPLACEMENT_CHAR;
            } else {
                codePoint = firstChar;
            }

            // Check onKeyDown() for details.
            if (shiftDown)
                codePoint = Character.toUpperCase(codePoint);

            boolean ctrlHeld = false;
            if (codePoint <= 31 && codePoint != 27) {
                if (codePoint == '\n') {
                    // The AOSP keyboard and descendants seems to send \n as text when the enter key is pressed,
                    // instead of a key event like most other keyboard apps. A terminal expects \r for the enter
                    // key (although when icrnl is enabled this doesn't make a difference - run 'stty -icrnl' to
                    // check the behaviour).
                    codePoint = '\r';
                }

                // E.g. penti keyboard for ctrl input.
                ctrlHeld = true;
                switch (codePoint) {
                    case 31:
                        codePoint = '_';
                        break;
                    case 30:
                        codePoint = '^';
                        break;
                    case 29:
                        codePoint = ']';
                        break;
                    case 28:
                        codePoint = '\\';
                        break;
                    default:
                        codePoint += 96;
                        break;
                }
            }

            strokes.add(new KeyStroke(codePoint, ctrlHeld));
        }
        return strokes;
    }
}
