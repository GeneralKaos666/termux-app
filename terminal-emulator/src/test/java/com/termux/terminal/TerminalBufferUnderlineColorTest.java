package com.termux.terminal;

import junit.framework.TestCase;

/**
 * Buffer-level coverage for the {@code mUnderlineColors} side array's erase/scroll/reflow plumbing,
 * the gap the WS5 spec recorded as "not covered by tests". These mirror the link-id tests in
 * {@link LinkRegistryTest}, which exercise the same {@link TerminalBuffer} paths.
 */
public class TerminalBufferUnderlineColorTest extends TestCase {

    private TerminalBuffer newScreen() {
        return new TerminalBuffer(5, 3, 3);
    }

    public void testEraseClearsUnderlineColors() {
        TerminalBuffer screen = newScreen();
        screen.setChar(0, 0, 'a', 0, 0, 9);
        screen.setChar(1, 0, 'b', 0, 0, 9);
        assertEquals(9, screen.getUnderlineColorAt(0, 0));
        assertEquals(9, screen.getUnderlineColorAt(0, 1));
        screen.blockSet(0, 0, 5, 1, ' ', 0);
        assertEquals(TextStyle.COLOR_INDEX_FOREGROUND, screen.getUnderlineColorAt(0, 0));
        assertEquals(TextStyle.COLOR_INDEX_FOREGROUND, screen.getUnderlineColorAt(0, 1));
    }

    public void testScrollKeepsUnderlineColorsWithTheirRows() {
        TerminalBuffer screen = newScreen();
        screen.setChar(0, 1, 'a', 0, 0, 9);
        assertEquals(9, screen.getUnderlineColorAt(1, 0));
        screen.scrollDownOneLine(0, 3, 0);
        assertEquals(9, screen.getUnderlineColorAt(0, 0));
        assertEquals(TextStyle.COLOR_INDEX_FOREGROUND, screen.getUnderlineColorAt(2, 0));
    }

    public void testResizeCarriesUnderlineColors() {
        TerminalBuffer screen = newScreen();
        screen.setChar(0, 0, 'a', 0, 0, 9);
        assertEquals(9, screen.getUnderlineColorAt(0, 0));
        screen.resize(3, 3, 3, new int[] {0, 0}, 0, false);
        assertEquals(9, screen.getUnderlineColorAt(0, 0));
    }
}
