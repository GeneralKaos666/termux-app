package com.termux.view;

import com.termux.terminal.TerminalBuffer;

import junit.framework.TestCase;

/**
 * Buffer-backed tests for {@link RowInvalidation#dirtyByIndex}: they drive a real
 * {@link com.termux.terminal.TerminalBuffer} and check that the viewport-relative flags are the buffer's
 * dirty flags shifted by {@code topRow}.
 *
 * <p>The buffer used here is {@code new TerminalBuffer(5, 10, 3)}: 5 columns, 10 rows of ring buffer,
 * 3 rows on screen. Scrolling with {@code scrollDownOneLine(0, 3, style)} is the only public way to
 * grow the transcript, and two calls make two transcript rows, addressable as external rows {@code -2}
 * and {@code -1}.
 *
 * <p>{@code TextStyle.NORMAL} is package-private in {@code com.termux.terminal}, so the literal
 * {@code 0L} is used as the style: it is an opaque word as far as this mapping is concerned.
 */
public class RowInvalidationBufferTest extends TestCase {

    /** The style passed to every write; see the class comment for why it is not {@code TextStyle.NORMAL}. */
    private static final long STYLE = 0L;

    /** Creates a 5-column, 3-screen-row buffer backed by 10 rows, as the brief prescribes. */
    private static TerminalBuffer newScreen() {
        return new TerminalBuffer(5, 10, 3);
    }

    /** Pushes two screen rows into the transcript, so external rows -2 and -1 become readable. */
    private static TerminalBuffer newScrolledScreen() {
        TerminalBuffer screen = newScreen();
        screen.scrollDownOneLine(0, 3, STYLE);
        screen.scrollDownOneLine(0, 3, STYLE);
        screen.clearDirtyState();
        return screen;
    }

    /** Asserts the flag array element by element, so a failure names the index that differs. */
    private static void assertFlags(boolean[] actual, boolean... expected) {
        assertEquals("length", expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals("index " + i, expected[i], actual[i]);
        }
    }

    /** The constructor's initial blank screen is not a change: a fresh buffer reports nothing dirty. */
    public void testFreshBufferIsAllClean() {
        TerminalBuffer screen = newScreen();
        assertFlags(RowInvalidation.dirtyByIndex(screen, 0, 3), false, false, false);
    }

    /** Writing one screen row dirties exactly that index, not its neighbours. */
    public void testOnlyTheWrittenRowIsDirty() {
        TerminalBuffer screen = newScreen();
        screen.setChar(0, 1, 'a', STYLE);
        assertFlags(RowInvalidation.dirtyByIndex(screen, 0, 3), false, true, false);
    }

    /** A negative top row addresses the transcript band above the screen (external rows -2, -1, 0). */
    public void testTopRowNegativeReadsTheTranscriptBand() {
        TerminalBuffer screen = newScrolledScreen();
        assertEquals("two scrolls make two transcript rows", 2, screen.getActiveTranscriptRows());

        assertFlags(RowInvalidation.dirtyByIndex(screen, -2, 3), false, false, false);

        screen.setChar(0, 1, 'a', STYLE);
        screen.setChar(0, 2, 'b', STYLE);

        assertFlags(RowInvalidation.dirtyByIndex(screen, 0, 3), false, true, true);
        // Rows 1 and 2 are not in the -2..0 window, so this window stays clean.
        assertFlags(RowInvalidation.dirtyByIndex(screen, -2, 3), false, false, false);
    }

    /**
     * Pins the {@code topRow = -1} offset: index {@code 0} is external row -1 (the transcript) and index
     * {@code 1} is external row 0 (the top screen row).
     *
     * <p>A single transcript row cannot be dirtied through the public write API after
     * {@link TerminalBuffer#clearDirtyState()}: {@code TerminalBuffer.setChar} rejects {@code row < 0}
     * with an {@link IllegalArgumentException}, and the only other public marker,
     * {@link TerminalBuffer#markAllDirty()}, flips every row at once. So the transcript row's own flag is
     * asserted clean here (a real read of a genuinely clean row), the offset is pinned through a write to
     * screen row 0, and the "transcript row is dirty" case is pinned separately with {@code markAllDirty}.
     */
    public void testTopRowMinusOneOffsetAfterWriteToScreenRowZero() {
        TerminalBuffer screen = newScrolledScreen();
        screen.setChar(0, 0, 'a', STYLE);
        // rows -1, 0, 1 → transcript row clean, written row dirty, next row clean.
        assertFlags(RowInvalidation.dirtyByIndex(screen, -1, 3), false, true, false);
    }

    /** The transcript rows really are read: marking every row dirty turns the transcript band dirty too. */
    public void testDirtyTranscriptRowIsReportedForNegativeTopRow() {
        TerminalBuffer screen = newScrolledScreen();
        screen.markAllDirty();
        assertFlags(RowInvalidation.dirtyByIndex(screen, -1, 3), true, true, true);
        assertFlags(RowInvalidation.dirtyByIndex(screen, -2, 3), true, true, true);
    }

    /** No buffer or no visible rows means an empty array, which {@code decide} accepts. */
    public void testNullScreenAndNonPositiveRowsYieldEmptyArray() {
        assertEquals(0, RowInvalidation.dirtyByIndex(null, 0, 3).length);
        assertEquals(0, RowInvalidation.dirtyByIndex(newScreen(), 0, 0).length);
        assertEquals(0, RowInvalidation.dirtyByIndex(newScreen(), 0, -1).length);

        RowInvalidation.Decision result = RowInvalidation.decide(
                7L, 7L, false, RowInvalidation.dirtyByIndex(null, 0, 3), 3, 30, 10);
        assertSame(RowInvalidation.Decision.NONE, result);
    }
}
