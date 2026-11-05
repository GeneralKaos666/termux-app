package com.termux.terminal;

import junit.framework.TestCase;

/**
 * A transcript reserves rows for the configured history (2000 by default, up to 50000), but each row
 * costs about ten bytes per column. A very wide grid (a small font in landscape) therefore used to
 * multiply the reservation by the column count and could exhaust the heap on rotation. The buffer
 * caps the transcript's cell count and never drops below the visible screen.
 */
public class TranscriptMemoryTest extends TestCase {

    private static final int CELL_WIDTH = 13;
    private static final int CELL_HEIGHT = 15;
    private static final int CONFIGURED_ROWS = 2000;

    public void testWideResizeCapsTheTranscriptRows() {
        TerminalEmulator emulator = emulator(80, 24);

        emulator.resize(354, 60, CELL_WIDTH, CELL_HEIGHT);

        TerminalBuffer screen = emulator.getScreen();
        assertEquals(354, screen.mColumns);
        assertEquals(60, screen.mScreenRows);
        assertTrue("transcript must hold the screen, was " + screen.mTotalRows, screen.mTotalRows >= 60);
        assertTrue("354 columns must cap the 2000-row transcript, was " + screen.mTotalRows,
            screen.mTotalRows < CONFIGURED_ROWS);
        assertTrue("transcript cells must stay within the 80-column budget, was " + screen.mTotalRows,
            (long) screen.mTotalRows * 354 <= (long) CONFIGURED_ROWS * 80);
    }

    public void testNarrowGridKeepsTheConfiguredRows() {
        TerminalEmulator emulator = emulator(40, 24);

        assertEquals(CONFIGURED_ROWS, emulator.getScreen().mTotalRows);
    }

    public void testCapNeverGoesBelowTheScreenRows() {
        TerminalEmulator emulator = new TerminalEmulator(new TerminalTestCase.MockTerminalOutput(),
            2000, 250, CELL_WIDTH, CELL_HEIGHT, CONFIGURED_ROWS, null);

        TerminalBuffer screen = emulator.getScreen();
        assertEquals(250, screen.mTotalRows);
    }

    public void testNarrowingRestoresTheConfiguredTranscriptRows() {
        TerminalEmulator emulator = emulator(80, 24);
        emulator.resize(354, 60, CELL_WIDTH, CELL_HEIGHT);

        emulator.resize(80, 24, CELL_WIDTH, CELL_HEIGHT);

        assertEquals(CONFIGURED_ROWS, emulator.getScreen().mTotalRows);
    }

    private static TerminalEmulator emulator(int columns, int rows) {
        return new TerminalEmulator(new TerminalTestCase.MockTerminalOutput(), columns, rows,
            CELL_WIDTH, CELL_HEIGHT, CONFIGURED_ROWS, null);
    }

}
