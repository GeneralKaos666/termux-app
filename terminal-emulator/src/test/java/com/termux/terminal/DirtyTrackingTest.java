package com.termux.terminal;

public class DirtyTrackingTest extends TerminalTestCase {

	public void testFreshBufferIsClean() {
		TerminalBuffer screen = new TerminalBuffer(5, 3, 3);
		for (int row = 0; row < 3; row++) assertFalse(screen.isRowDirty(row));
		assertEquals(0, screen.getChangeCounter());
	}

	public void testSetCharMarksOnlyItsRow() {
		TerminalBuffer screen = new TerminalBuffer(5, 3, 3);
		screen.setChar(0, 1, 'a', 0);
		assertFalse(screen.isRowDirty(0));
		assertTrue(screen.isRowDirty(1));
		assertFalse(screen.isRowDirty(2));
		screen.clearDirtyState();
		assertFalse(screen.isRowDirty(1));
		assertEquals(0, screen.getChangeCounter());
	}

	public void testBlockSetAndBlockCopyMarkTouchedRows() {
		TerminalBuffer screen = new TerminalBuffer(5, 3, 3);
		screen.blockSet(0, 1, 2, 2, 'X', 0);
		assertFalse(screen.isRowDirty(0));
		assertTrue(screen.isRowDirty(1));
		assertTrue(screen.isRowDirty(2));
		screen.clearDirtyState();
		screen.blockCopy(0, 0, 2, 1, 0, 2);
		assertFalse(screen.isRowDirty(0));
		assertFalse(screen.isRowDirty(1));
		assertTrue(screen.isRowDirty(2));
	}

	public void testScrollTicksTheCounterAndMarksTheRegion() {
		TerminalBuffer screen = new TerminalBuffer(5, 3, 3);
		screen.setChar(0, 0, 'a', 0);
		screen.setChar(0, 2, 'c', 0);
		screen.clearDirtyState();
		screen.scrollDownOneLine(0, 3, 0);
		assertEquals(1, screen.getChangeCounter());
		for (int row = 0; row < 3; row++) assertTrue(screen.isRowDirty(row));
	}

	public void testResizeMarksAllAndTicks() {
		TerminalBuffer screen = new TerminalBuffer(5, 3, 3);
		screen.resize(4, 3, 6, new int[]{0, 0}, 0, false);
		for (int row = 0; row < 3; row++) assertTrue(screen.isRowDirty(row));
		assertEquals(1, screen.getChangeCounter());
	}

	public void testClearTranscriptMarksAllAndTicks() {
		TerminalBuffer screen = new TerminalBuffer(5, 3, 3);
		screen.clearTranscript();
		for (int row = 0; row < 3; row++) assertTrue(screen.isRowDirty(row));
		assertEquals(1, screen.getChangeCounter());
	}

	public void testSetOrClearEffectMarksRows() {
		TerminalBuffer screen = new TerminalBuffer(5, 3, 3);
		screen.setOrClearEffect(TextStyle.CHARACTER_ATTRIBUTE_BOLD, true, false, true, 0, 3, 1, 0, 2, 3);
		assertFalse(screen.isRowDirty(0));
		assertTrue(screen.isRowDirty(1));
		assertFalse(screen.isRowDirty(2));
	}

	public void testSetLineWrapMarksTheRow() {
		TerminalBuffer screen = new TerminalBuffer(5, 3, 3);
		screen.setLineWrap(1);
		assertFalse(screen.isRowDirty(0));
		assertTrue(screen.isRowDirty(1));
		assertFalse(screen.isRowDirty(2));
		screen.clearDirtyState();
		screen.clearLineWrap(1);
		assertFalse(screen.isRowDirty(0));
		assertTrue(screen.isRowDirty(1));
		assertFalse(screen.isRowDirty(2));
	}

	public void testReadsDoNotMarkDirty() {
		TerminalBuffer screen = new TerminalBuffer(5, 3, 3);
		screen.setChar(0, 0, 'a', 0);
		screen.clearDirtyState();
		screen.getStyleAt(0, 0);
		screen.getTranscriptText();
		screen.getTranscriptTextWithoutJoinedLines();
		screen.getSelectedText(0, 0, 4, 2);
		screen.allocateFullLineIfNecessary(screen.externalToInternalRow(2));
		for (int row = 0; row < 3; row++) assertFalse(screen.isRowDirty(row));
		assertEquals(0, screen.getChangeCounter());
	}
}
