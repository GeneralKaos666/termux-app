package com.termux.terminal;

import junit.framework.TestCase;

public class LinkRegistryTest extends TestCase {

	private TerminalBuffer newScreen() {
		return new TerminalBuffer(5, 3, 3);
	}

	public void testSetCharStampsTheCurrentLink() {
		TerminalBuffer screen = newScreen();
		int id = screen.registerLink("https://example.com");
		screen.setCurrentLinkId(id);
		assertEquals(id, screen.getCurrentLinkId());
		screen.setChar(0, 0, 'a', 0);
		assertEquals(id, screen.getLinkIdAt(0, 0));
		assertEquals(0, screen.getLinkIdAt(0, 1));
	}

	public void testRegisterLinkDeduplicates() {
		TerminalBuffer screen = newScreen();
		int id1 = screen.registerLink("https://example.com");
		int id2 = screen.registerLink("https://example.com");
		assertTrue(id1 != 0);
		assertEquals(id1, id2);
		assertEquals(id1, screen.registerLink("https://example.com"));
	}

	public void testGetLinkResolvesAndUnknownIsNull() {
		TerminalBuffer screen = newScreen();
		int id = screen.registerLink("https://example.com");
		assertEquals("https://example.com", screen.getLink(id));
		assertNull(screen.getLink(0));
		assertNull(screen.getLink(999));
	}

	public void testSetLinkAtMarksTheRowDirty() {
		TerminalBuffer screen = newScreen();
		int id = screen.registerLink("https://example.com");
		screen.setChar(0, 1, 'a', 0);
		screen.clearDirtyState();
		assertFalse(screen.isRowDirty(0));
		assertFalse(screen.isRowDirty(1));
		assertFalse(screen.isRowDirty(2));
		screen.setLinkAt(0, 1, id);
		assertTrue(screen.isRowDirty(1));
		assertFalse(screen.isRowDirty(0));
		assertFalse(screen.isRowDirty(2));
	}

	public void testEraseClearsLinks() {
		TerminalBuffer screen = newScreen();
		int id = screen.registerLink("https://example.com");
		screen.setCurrentLinkId(id);
		screen.setChar(0, 0, 'a', 0);
		screen.setChar(1, 0, 'b', 0);
		assertEquals(id, screen.getLinkIdAt(0, 0));
		assertEquals(id, screen.getLinkIdAt(0, 1));
		screen.blockSet(0, 0, 5, 1, ' ', 0);
		assertEquals(0, screen.getLinkIdAt(0, 0));
		assertEquals(0, screen.getLinkIdAt(0, 1));
	}

	public void testCapReturnsZeroWhenFull() {
		TerminalBuffer screen = newScreen();
		screen.mMaxLinkIds = 2;
		int idA = screen.registerLink("https://a.example");
		screen.setLinkAt(0, 0, idA);
		int idB = screen.registerLink("https://b.example");
		screen.setLinkAt(1, 0, idB);
		assertTrue(idA != 0);
		assertTrue(idB != 0);
		// Both ids are referenced from cells, so the GC cannot free them and the cap rejects C.
		assertEquals(0, screen.registerLink("https://c.example"));
		assertEquals("https://a.example", screen.getLink(idA));
		assertEquals("https://b.example", screen.getLink(idB));
		assertEquals(0, screen.registerLink("https://c.example"));
	}

	public void testGcFreesUnreferencedIds() {
		TerminalBuffer screen = newScreen();
		screen.mMaxLinkIds = 2;
		int idA = screen.registerLink("https://a.example");
		int idB = screen.registerLink("https://b.example");
		assertTrue(idA != 0);
		assertTrue(idB != 0);
		// Neither id is stamped on a cell, so registering a third link runs the GC and frees them.
		int idC = screen.registerLink("https://c.example");
		assertTrue(idC != 0);
		assertNull(screen.getLink(idA));
		assertNull(screen.getLink(idB));
		assertEquals("https://c.example", screen.getLink(idC));
	}

	public void testScrollKeepsLinksWithTheirRows() {
		TerminalBuffer screen = newScreen();
		int id = screen.registerLink("https://example.com");
		screen.setLinkAt(0, 1, id);
		assertEquals(id, screen.getLinkIdAt(1, 0));
		screen.scrollDownOneLine(0, 3, 0);
		assertEquals(id, screen.getLinkIdAt(0, 0));
		assertEquals(0, screen.getLinkIdAt(2, 0));
	}

	public void testResizeCarriesLinks() {
		TerminalBuffer screen = newScreen();
		int id = screen.registerLink("https://example.com");
		screen.setCurrentLinkId(id);
		screen.setChar(0, 0, 'a', 0);
		assertEquals(id, screen.getLinkIdAt(0, 0));
		screen.resize(3, 3, 3, new int[] {0, 0}, 0, false);
		assertEquals(id, screen.getLinkIdAt(0, 0));
	}

	public void testWideGlyphLinkResolvesOnBothColumns() {
		TerminalBuffer screen = newScreen();
		int id = screen.registerLink("https://example.com");
		screen.setCurrentLinkId(id);
		screen.setChar(0, 0, '\uFF31', 0); // FULLWIDTH LATIN CAPITAL LETTER Q, width 2.
		assertEquals(id, screen.getLinkIdAt(0, 0));
		assertEquals(id, screen.getLinkIdAt(0, 1));
	}

}
