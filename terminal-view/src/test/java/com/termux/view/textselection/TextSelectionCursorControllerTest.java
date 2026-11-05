package com.termux.view.textselection;

import junit.framework.TestCase;

/**
 * Column mapping for the selection handles. The row text is what {@code TerminalBuffer.getSelectedText}
 * returns: a cell's base code point followed by its continuation code points, so a ZWJ family, a flag
 * and a skin toned emoji each occupy one cell. The mapping must walk those cells, not the code points.
 */
public class TextSelectionCursorControllerTest extends TestCase {

    /** The family emoji U+1F468 ZWJ U+1F469 ZWJ U+1F467: eight chars, one two-column cluster. */
    private static final String ZWJ_FAMILY = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67";
    /** Regional indicator B + R: one two-column flag cluster. */
    private static final String FLAG_BR = "\uD83C\uDDE7\uD83C\uDDF7";
    /** Thumbs up + medium skin tone modifier: one two-column cluster. */
    private static final String THUMBS_UP_MEDIUM = "\uD83D\uDC4D\uD83C\uDFFD";

    public void testColumnPastAZwjClusterIsKept() {
        assertEquals(4, TextSelectionCursorController.validCursorColumn("A" + ZWJ_FAMILY + "B", 4));
    }

    public void testColumnPastACombiningMarkIsKept() {
        assertEquals(3, TextSelectionCursorController.validCursorColumn("Ae\u0301B", 3));
    }

    public void testColumnPastAFlagIsKept() {
        assertEquals(4, TextSelectionCursorController.validCursorColumn("A" + FLAG_BR + "B", 4));
    }

    public void testColumnPastASkinToneClusterIsKept() {
        assertEquals(4, TextSelectionCursorController.validCursorColumn("A" + THUMBS_UP_MEDIUM + "B", 4));
    }

    public void testColumnInsideAZwjClusterSnapsToItsEnd() {
        assertEquals(3, TextSelectionCursorController.validCursorColumn("A" + ZWJ_FAMILY + "B", 2));
    }

    public void testColumnInsideAWideCellSnapsToItsEnd() {
        assertEquals(3, TextSelectionCursorController.validCursorColumn("A\u65E5B", 2));
    }

    public void testPlainAsciiColumnsAreUnchanged() {
        assertEquals(0, TextSelectionCursorController.validCursorColumn("ABCDE", 0));
        assertEquals(3, TextSelectionCursorController.validCursorColumn("ABCDE", 3));
        assertEquals(5, TextSelectionCursorController.validCursorColumn("ABCDE", 5));
    }

    public void testColumnBeyondTheLineIsKept() {
        assertEquals(9, TextSelectionCursorController.validCursorColumn("AB", 9));
    }

    public void testEmptyLineKeepsTheRequestedColumn() {
        assertEquals(7, TextSelectionCursorController.validCursorColumn("", 7));
        assertEquals(7, TextSelectionCursorController.validCursorColumn(null, 7));
    }

}
