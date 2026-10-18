package com.termux.terminal;

import java.util.List;

/** "\033[" is the Control Sequence Introducer char sequence (CSI). */
public class ControlSequenceIntroducerTest extends TerminalTestCase {

	/** CSI Ps P Scroll down Ps lines (default = 1) (SD). */
	public void testCsiT() {
		withTerminalSized(4, 6).enterString("1\r\n2\r\n3\r\nhi\033[2Tyo\r\nA\r\nB").assertLinesAre("    ", "    ", "1   ", "2 yo", "A   ",
				"Bi  ");
		// Default value (1):
		withTerminalSized(4, 6).enterString("1\r\n2\r\n3\r\nhi\033[Tyo\r\nA\r\nB").assertLinesAre("    ", "1   ", "2   ", "3 yo", "Ai  ",
				"B   ");
	}

	/** CSI Ps S Scroll up Ps lines (default = 1) (SU). */
	public void testCsiS() {
		// The behaviour here is a bit inconsistent between terminals - this is how the OS X Terminal.app does it:
		withTerminalSized(3, 4).enterString("1\r\n2\r\n3\r\nhi\033[2Sy").assertLinesAre("3  ", "hi ", "   ", "  y");
		// Default value (1):
		withTerminalSized(3, 4).enterString("1\r\n2\r\n3\r\nhi\033[Sy").assertLinesAre("2  ", "3  ", "hi ", "  y");
	}

	/** CSI Ps X  Erase Ps Character(s) (default = 1) (ECH). */
	public void testCsiX() {
		// See https://code.google.com/p/chromium/issues/detail?id=212712 where test was extraced from.
		withTerminalSized(13, 2).enterString("abcdefghijkl\b\b\b\b\b\033[X").assertLinesAre("abcdefg ijkl ", "             ");
		withTerminalSized(13, 2).enterString("abcdefghijkl\b\b\b\b\b\033[1X").assertLinesAre("abcdefg ijkl ", "             ");
		withTerminalSized(13, 2).enterString("abcdefghijkl\b\b\b\b\b\033[2X").assertLinesAre("abcdefg  jkl ", "             ");
		withTerminalSized(13, 2).enterString("abcdefghijkl\b\b\b\b\b\033[20X").assertLinesAre("abcdefg      ", "             ");
	}

	/** CSI Pm m  Set SGR parameter(s) from semicolon-separated list Pm. */
	public void testCsiSGRParameters() {
		// Set more parameters (19) than supported (16).  Additional parameters should be silently consumed.
		withTerminalSized(3, 2).enterString("\033[0;38;2;255;255;255;48;2;0;0;0;1;2;3;4;5;7;8;9mabc").assertLinesAre("abc", "   ");
	}

	/** CSI Ps b  Repeat the preceding graphic character Ps times (REP). */
	public void testRepeat() {
		withTerminalSized(3, 2).enterString("a\033[b").assertLinesAre("aa ", "   ");
		withTerminalSized(3, 2).enterString("a\033[2b").assertLinesAre("aaa", "   ");
		// When no char has been output we ignore REP:
		withTerminalSized(3, 2).enterString("\033[b").assertLinesAre("   ", "   ");
		// This shows that REP outputs the last emitted code point and not the one relative to the
		// current cursor position:
		withTerminalSized(5, 2).enterString("abcde\033[2G\033[2b\n").assertLinesAre("aeede", "     ");
	}

	/** CSI 3 J  Clear scrollback (xterm, libvte; non-standard). */
	public void testCsi3J() {
		withTerminalSized(3, 2).enterString("a\r\nb\r\nc\r\nd");
		assertEquals("a\nb\nc\nd", mTerminal.getScreen().getTranscriptText());
		enterString("\033[3J");
		assertEquals("c\nd", mTerminal.getScreen().getTranscriptText());

		withTerminalSized(3, 2).enterString("Lorem_ipsum");
		assertEquals("Lorem_ipsum", mTerminal.getScreen().getTranscriptText());
		enterString("\033[3J");
		assertEquals("ipsum", mTerminal.getScreen().getTranscriptText());

		withTerminalSized(3, 2).enterString("w\r\nx\r\ny\r\nz\033[?1049h\033[3J\033[?1049l");
		assertEquals("y\nz", mTerminal.getScreen().getTranscriptText());
	}

    public void testReportPixelSize() {
        int columns = 3;
        int rows = 3;
        withTerminalSized(columns, rows);
        int cellWidth = TerminalTest.INITIAL_CELL_WIDTH_PIXELS;
        int cellHeight = TerminalTest.INITIAL_CELL_HEIGHT_PIXELS;
        assertEnteringStringGivesResponse("\033[14t", "\033[4;" + (rows*cellHeight) + ";" + (columns*cellWidth) + "t");
        assertEnteringStringGivesResponse("\033[16t", "\033[6;" + cellHeight + ";" + cellWidth + "t");
        columns = 23;
        rows = 33;
        resize(columns, rows);
        assertEnteringStringGivesResponse("\033[14t", "\033[4;" + (rows*cellHeight) + ";" + (columns*cellWidth) + "t");
        assertEnteringStringGivesResponse("\033[16t", "\033[6;" + cellHeight + ";" + cellWidth + "t");
        cellWidth = 8;
        cellHeight = 18;
        mTerminal.resize(columns, rows, cellWidth, cellHeight);
        assertEnteringStringGivesResponse("\033[14t", "\033[4;" + (rows*cellHeight) + ";" + (columns*cellWidth) + "t");
        assertEnteringStringGivesResponse("\033[16t", "\033[6;" + cellHeight + ";" + cellWidth + "t");
    }

    /**
     * See <a href="https://sw.kovidgoyal.net/kitty/underlines/">Colored and styled underlines</a>:
     *
     * <pre>
     *  <ESC>[4:0m  # no underline
     *  <ESC>[4:1m  # straight underline
     *  <ESC>[4:2m  # double underline
     *  <ESC>[4:3m  # curly underline
     *  <ESC>[4:4m  # dotted underline
     *  <ESC>[4:5m  # dashed underline
     *  <ESC>[4m    # straight underline (for backwards compat)
     *  <ESC>[24m   # no underline (for backwards compat)
     * </pre>
     * <p>
     * The parsed variants are stored in bits 11-13 of the cell style word (see
     * {@link TextStyle#decodeUnderlineStyle}) in addition to the underline attribute bit.
     */
    public void testUnderlineVariants() {
        for (String suffix : List.of("", ":1", ":2", ":3", ":4", ":5")) {
            for (String stop : List.of("24", "4:0")) {
                withTerminalSized(3, 3);
                enterString("\033[4" + suffix + "m").assertLinesAre("   ", "   ", "   ");
                assertEquals(TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE, mTerminal.mEffect);
                // Plain "4" means a straight underline, and ":n" stores the n:th kitty style.
                int expectedStyle = suffix.isEmpty() ? TextStyle.UNDERLINE_STYLE_SINGLE : Integer.parseInt(suffix.substring(1));
                assertEquals(expectedStyle, mTerminal.mUnderlineStyle);
                enterString("\033[4;1m").assertLinesAre("   ", "   ", "   ");
                assertEquals(TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE, mTerminal.mEffect);
                enterString("\033[" + stop + "m").assertLinesAre("   ", "   ", "   ");
                // The earlier "\033[4;1m" left bold on, so the stop only removes the underline and its style:
                assertEquals(TextStyle.CHARACTER_ATTRIBUTE_BOLD, mTerminal.mEffect);
                assertEquals(TextStyle.UNDERLINE_STYLE_NONE, mTerminal.mUnderlineStyle);
            }
        }
    }

    public void testManyParameters() {
        StringBuilder b = new StringBuilder("\033[");
        for (int i = 0; i < 30; i++) {
            b.append("0;");
        }
        b.append("4:2");
        // This clearing of underline should be ignored as the parameters pass the threshold for too many parameters:
        b.append("4:0m");
        withTerminalSized(3, 3)
            .enterString(b.toString())
            .assertLinesAre("   ", "   ", "   ");
        assertEquals(TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE, mTerminal.mEffect);
        // The 30 "0;" parameters fill indexes 0-29, "4" lands at index 30 and then the "24" + "0" digits accumulate
        // into the single last slot as 240 (no separator survives the parameter limit). That out-of-range value is
        // clamped to a straight underline, so the dropped trailing parameters cannot leave a stale style behind.
        assertEquals(TextStyle.UNDERLINE_STYLE_SINGLE, mTerminal.mUnderlineStyle);
    }

    /**
     * The underline style must reach the stored cell, not just the writer field, for every kitty variant plus the
     * xterm double underline and the clears.
     */
    public void testUnderlineStyleIsStoredPerCell() {
        assertUnderlineStyleAtCell("4:1", TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE, TextStyle.UNDERLINE_STYLE_SINGLE);
        assertUnderlineStyleAtCell("4:2", TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE, TextStyle.UNDERLINE_STYLE_DOUBLE);
        assertUnderlineStyleAtCell("4:3", TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE, TextStyle.UNDERLINE_STYLE_CURLY);
        assertUnderlineStyleAtCell("4:4", TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE, TextStyle.UNDERLINE_STYLE_DOTTED);
        assertUnderlineStyleAtCell("4:5", TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE, TextStyle.UNDERLINE_STYLE_DASHED);
        // Plain "4" is a straight underline and also resets any earlier styled underline:
        assertUnderlineStyleAtCell("4", TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE, TextStyle.UNDERLINE_STYLE_SINGLE);
        // Out-of-range sub parameters are clamped to a straight underline, still with the underline bit set:
        assertUnderlineStyleAtCell("4:9", TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE, TextStyle.UNDERLINE_STYLE_SINGLE);
        // "SGR 21" is xterm's double underline:
        assertUnderlineStyleAtCell("21", TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE, TextStyle.UNDERLINE_STYLE_DOUBLE);

        // Clearing after a styled underline must remove both the attribute bit and the stored style:
        withTerminalSized(3, 3).enterString("\033[4:3m\033[24mX");
        assertEquals(0, TextStyle.decodeEffect(getStyleAt(0, 0)) & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE);
        assertEquals(TextStyle.UNDERLINE_STYLE_NONE, TextStyle.decodeUnderlineStyle(getStyleAt(0, 0)));

        withTerminalSized(3, 3).enterString("\033[4:3m\033[4:0mX");
        assertEquals(0, TextStyle.decodeEffect(getStyleAt(0, 0)) & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE);
        assertEquals(TextStyle.UNDERLINE_STYLE_NONE, TextStyle.decodeUnderlineStyle(getStyleAt(0, 0)));
    }

    private void assertUnderlineStyleAtCell(String sgr, int expectedEffect, int expectedStyle) {
        withTerminalSized(3, 3).enterString("\033[" + sgr + "mX");
        long style = getStyleAt(0, 0);
        assertEquals(expectedEffect, TextStyle.decodeEffect(style) & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE);
        assertEquals(expectedStyle, TextStyle.decodeUnderlineStyle(style));
    }

    public void testResetClearsUnderlineStyle() {
        withTerminalSized(3, 3).enterString("\033[4:3mA\033[0mB");
        assertEquals(TextStyle.UNDERLINE_STYLE_CURLY, TextStyle.decodeUnderlineStyle(getStyleAt(0, 0)));
        assertEquals(TextStyle.UNDERLINE_STYLE_NONE, TextStyle.decodeUnderlineStyle(getStyleAt(0, 1)));
        assertEquals(TextStyle.UNDERLINE_STYLE_NONE, mTerminal.mUnderlineStyle);
    }

    public void testDecscDecrcRoundTripUnderlineStyle() {
        withTerminalSized(3, 3);
        enterString("\033[4:4m");
        assertEquals(TextStyle.UNDERLINE_STYLE_DOTTED, mTerminal.mUnderlineStyle);
        assertEquals(TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE, mTerminal.mEffect);
        enterString("\0337"); // DECSC - save cursor.
        enterString("\033[24m");
        assertEquals(TextStyle.UNDERLINE_STYLE_NONE, mTerminal.mUnderlineStyle);
        assertEquals(0, mTerminal.mEffect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE);
        enterString("\0338"); // DECRC - restore cursor.
        assertEquals(TextStyle.UNDERLINE_STYLE_DOTTED, mTerminal.mUnderlineStyle);
        assertEquals(TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE, mTerminal.mEffect);
    }

    /**
     * SGR 58 (underline color) must reach the stored cell, not just the writer field:
     * <pre>
     *  <ESC>[58;5;n m   # indexed underline color
     *  <ESC>[58;2;r;g;b m  # 24-bit underline color
     *  <ESC>[59m   # back to the default (inherit foreground)
     * </pre>
     */
    public void testUnderlineColorIndexedIsStoredPerCell() {
        withTerminalSized(3, 3).enterString("A\033[58;5;9mB");
        assertEquals(9, mTerminal.getScreen().getUnderlineColorAt(0, 1));
        // The cell written before the SGR keeps the default:
        assertEquals(TextStyle.COLOR_INDEX_FOREGROUND, mTerminal.getScreen().getUnderlineColorAt(0, 0));
        assertEquals(9, mTerminal.mUnderlineColor);
    }

    public void testUnderlineColorRgbIsStoredPerCell() {
        withTerminalSized(3, 3).enterString("\033[58;2;10;20;30mX");
        assertEquals(0xff0a141e, mTerminal.getScreen().getUnderlineColorAt(0, 0));
    }

    public void testSgr59ResetsToDefaultForSubsequentCells() {
        withTerminalSized(3, 3).enterString("\033[58;5;9mA\033[59mB");
        assertEquals(9, mTerminal.getScreen().getUnderlineColorAt(0, 0));
        assertEquals(TextStyle.COLOR_INDEX_FOREGROUND, mTerminal.getScreen().getUnderlineColorAt(0, 1));
        assertEquals(TextStyle.COLOR_INDEX_FOREGROUND, mTerminal.mUnderlineColor);
    }

    public void testDefaultTerminalNeverAllocatesUnderlineColorArray() {
        withTerminalSized(3, 3).enterString("plain text");
        assertNull(mTerminal.getScreen().mLines[mTerminal.getScreen().externalToInternalRow(0)].mUnderlineColors);
    }

    public void testSgr0RestoresDefaultUnderlineColor() {
        withTerminalSized(3, 3).enterString("\033[58;5;9mA\033[0mB");
        assertEquals(9, mTerminal.getScreen().getUnderlineColorAt(0, 0));
        assertEquals(TextStyle.COLOR_INDEX_FOREGROUND, mTerminal.getScreen().getUnderlineColorAt(0, 1));
        assertEquals(TextStyle.COLOR_INDEX_FOREGROUND, mTerminal.mUnderlineColor);
    }

    public void testResetRestoresDefaultUnderlineColor() {
        withTerminalSized(3, 3).enterString("\033[58;5;9m");
        assertEquals(9, mTerminal.mUnderlineColor);
        mTerminal.reset();
        assertEquals(TextStyle.COLOR_INDEX_FOREGROUND, mTerminal.mUnderlineColor);
    }

    public void testDecscDecrcRestoresUnderlineColor() {
        withTerminalSized(3, 3);
        enterString("\033[58;5;9m");
        enterString("\0337"); // DECSC - save cursor.
        enterString("\033[58;5;3m");
        assertEquals(3, mTerminal.mUnderlineColor);
        enterString("\0338"); // DECRC - restore cursor.
        assertEquals(9, mTerminal.mUnderlineColor);
        enterString("X");
        assertEquals(9, mTerminal.getScreen().getUnderlineColorAt(0, 0));
    }

}
