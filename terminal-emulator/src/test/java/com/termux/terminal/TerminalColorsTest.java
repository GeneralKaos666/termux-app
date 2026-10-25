package com.termux.terminal;

import junit.framework.TestCase;

import java.util.Arrays;

/** Direct tests for the OSC 4/10/11/12 colour parser ({@link TerminalColors}). */
public class TerminalColorsTest extends TestCase {

    public void testParseHexRgb() {
        assertEquals(0xFF000000, TerminalColors.parse("#000"));
        assertEquals(0xFFFFFFFF, TerminalColors.parse("#fff"));
        assertEquals(0xFFFF0000, TerminalColors.parse("#f00"));
        assertEquals(0xFF888888, TerminalColors.parse("#888"));
    }

    public void testParseHexRrggbb() {
        assertEquals(0xFFFF0000, TerminalColors.parse("#FF0000"));
        assertEquals(0xFF00FF00, TerminalColors.parse("#00FF00"));
        assertEquals(0xFF0000FF, TerminalColors.parse("#0000FF"));
        assertEquals(0xFF808080, TerminalColors.parse("#808080"));
        assertEquals(0xFF123456, TerminalColors.parse("#123456"));
    }

    public void testParseHexExtended() {
        // 12-bit (#RRRGGGBBB) and 16-bit (#RRRRGGGGBBBB), scaled to 8 bits.
        assertEquals(0xFFFF0000, TerminalColors.parse("#FFF000000"));
        assertEquals(0xFF7F7F7F, TerminalColors.parse("#7FF7FF7FF"));
        assertEquals(0xFFFF0000, TerminalColors.parse("#FFFF00000000"));
    }

    public void testParseRgbColon() {
        assertEquals(0xFFFF0000, TerminalColors.parse("rgb:ff/00/00"));
        assertEquals(0xFF00FF00, TerminalColors.parse("rgb:00/ff/00"));
        assertEquals(0xFFFF0000, TerminalColors.parse("rgb:f/0/0"));
    }

    public void testParseInvalid() {
        assertEquals(0, TerminalColors.parse(""));
        assertEquals(0, TerminalColors.parse("foo"));
        assertEquals(0, TerminalColors.parse("#12"));
        assertEquals(0, TerminalColors.parse("#1234"));
        assertEquals(0, TerminalColors.parse("#GGGGGG"));
        assertEquals(0, TerminalColors.parse("rgb:ff/00"));
    }

    public void testTryParseColorUpdatesOnlyOnSuccess() {
        TerminalColors colors = new TerminalColors();
        colors.tryParseColor(1, "#ff0000");
        assertEquals(0xFFFF0000, colors.mCurrentColors[1]);
        int before = colors.mCurrentColors[2];
        colors.tryParseColor(2, "invalid");
        assertEquals(before, colors.mCurrentColors[2]);
    }

    public void testResetRestoresThemeDefault() {
        TerminalColors colors = new TerminalColors();
        colors.tryParseColor(5, "#123456");
        assertFalse(Arrays.equals(colors.mCurrentColors, TerminalColors.COLOR_SCHEME.mDefaultColors));
        colors.reset();
        assertTrue(Arrays.equals(colors.mCurrentColors, TerminalColors.COLOR_SCHEME.mDefaultColors));
    }

    public void testResetSingleIndexRestoresThemeDefault() {
        TerminalColors colors = new TerminalColors();
        colors.tryParseColor(7, "#123456");
        assertEquals(0xFF123456, colors.mCurrentColors[7]);
        colors.reset(7);
        assertEquals(TerminalColors.COLOR_SCHEME.mDefaultColors[7], colors.mCurrentColors[7]);
    }
}
