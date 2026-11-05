package com.termux.view;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TerminalGeometryTest {

    @Test
    public void cursorColumnIsTheTruncatedCell() {
        assertEquals(3, TerminalGeometry.cursorColumn(3.9f * 10, 10f));
        assertEquals(3, TerminalGeometry.cursorColumn(3.5f * 10, 10f));
    }

    @Test
    public void cursorRowIncludesTheFortyPixelOffset() {
        // Preserves the existing -40 in getCursorY; this is not derived, it is pinned.
        assertEquals(2, TerminalGeometry.cursorRow(40 + 2 * 20, 20f, 0));
        assertEquals(0, TerminalGeometry.cursorRow(40 + 2 * 20, 20f, -2));
    }

    @Test
    public void pointXClampsToTheColumnCount() {
        assertEquals(200, TerminalGeometry.pointX(20, 80, 10f));
        assertEquals(800, TerminalGeometry.pointX(200, 80, 10f));
    }

    @Test
    public void pointXRoundTripsWithCursorColumn() {
        int column = TerminalGeometry.cursorColumn(35.4f, 10f);
        assertEquals(30, TerminalGeometry.pointX(column, 80, 10f));
    }

    @Test
    public void pointYIsRelativeToTheTopRow() {
        assertEquals(0, TerminalGeometry.pointY(0, 0, 20f));
        assertEquals(20, TerminalGeometry.pointY(1, 0, 20f));
        assertEquals(0, TerminalGeometry.pointY(1, 1, 20f));
        assertEquals(-20, TerminalGeometry.pointY(0, 1, 20f));
    }
}
