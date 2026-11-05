package com.termux.view;

/**
 * Maps a typed code point to the bytes a terminal expects.
 *
 * <p>Pure functions only, no {@code android.*} types, so the mapping is unit tested without a device.
 */
final class KeyCodePoints {

    private KeyCodePoints() {
    }

    /**
     * Applies the control-key mapping: a letter becomes its C0 control code, and the punctuation keys
     * that traditionally produce controls (including the digit row variants) map to the same values.
     * Anything unmapped is returned unchanged.
     */
    static int applyControl(int codePoint) {
        if (codePoint >= 'a' && codePoint <= 'z') {
            codePoint = codePoint - 'a' + 1;
        } else if (codePoint >= 'A' && codePoint <= 'Z') {
            codePoint = codePoint - 'A' + 1;
        } else if (codePoint == ' ' || codePoint == '2') {
            codePoint = 0;
        } else if (codePoint == '[' || codePoint == '3') {
            codePoint = 27; // ^[ (Esc)
        } else if (codePoint == '\\' || codePoint == '4') {
            codePoint = 28;
        } else if (codePoint == ']' || codePoint == '5') {
            codePoint = 29;
        } else if (codePoint == '^' || codePoint == '6') {
            codePoint = 30; // control-^
        } else if (codePoint == '_' || codePoint == '7' || codePoint == '/') {
            // "Ctrl-/ sends 0x1f which is equivalent of Ctrl-_ since the days of VT102"
            // - http://apple.stackexchange.com/questions/24261/how-do-i-send-c-that-is-control-slash-to-the-terminal
            codePoint = 31;
        } else if (codePoint == '8') {
            codePoint = 127; // DEL
        }
        return codePoint;
    }

    /**
     * Work around bluetooth keyboards sending funny unicode characters instead of the more normal ones
     * from ASCII that terminal programs expect - the desire to input the original characters should be
     * low.
     *
     * <p>Only for non-soft keyboards: the caller gates this on the event source, because a soft keyboard
     * may legitimately type one of these characters.
     */
    static int normalizeExternalKeyboard(int codePoint) {
        switch (codePoint) {
            case 0x02DC: // SMALL TILDE.
                return 0x007E; // TILDE (~).
            case 0x02CB: // MODIFIER LETTER GRAVE ACCENT.
                return 0x0060; // GRAVE ACCENT (`).
            case 0x02C6: // MODIFIER LETTER CIRCUMFLEX ACCENT.
                return 0x005E; // CIRCUMFLEX ACCENT (^).
            default:
                return codePoint;
        }
    }
}
