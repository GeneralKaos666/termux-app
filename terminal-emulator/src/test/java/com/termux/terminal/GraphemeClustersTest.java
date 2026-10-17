package com.termux.terminal;

import junit.framework.TestCase;

public class GraphemeClustersTest extends TestCase {

	private static final int REGIONAL_INDICATOR_A = 0x1F1E6;
	private static final int REGIONAL_INDICATOR_B = 0x1F1E7;
	private static final int ZWJ = 0x200D;
	private static final int VS15 = 0xFE0E;
	private static final int VS16 = 0xFE0F;
	private static final int SUPPLEMENTARY_VARIATION_SELECTOR = 0xE0100;
	private static final int EMOJI_MAN = 0x1F468;
	private static final int EMOJI_WOMAN = 0x1F469;
	private static final int EMOJI_BOY = 0x1F466;
	private static final int SKIN_TONE_MODIFIER = 0x1F3FB;
	private static final int COMBINING_DIARESIS = 0x0308;
	private static final int WIDE_CHAR = 0x679C;

	private static char[] charsOf(int... codePoints) {
		StringBuilder builder = new StringBuilder();
		for (int codePoint : codePoints) builder.appendCodePoint(codePoint);
		return builder.toString().toCharArray();
	}

	public void testBaseWidth() {
		assertEquals(1, GraphemeClusters.baseWidth('a'));
		assertEquals(2, GraphemeClusters.baseWidth(WIDE_CHAR));
		assertEquals(0, GraphemeClusters.baseWidth(COMBINING_DIARESIS));
		assertEquals(0, GraphemeClusters.baseWidth(ZWJ));
		// A lone regional indicator is two columns wide, like the wide emoji it can become.
		assertEquals(2, GraphemeClusters.baseWidth(REGIONAL_INDICATOR_A));
		// Variation selectors take no column of their own.
		assertEquals(0, GraphemeClusters.baseWidth(VS15));
		assertEquals(0, GraphemeClusters.baseWidth(VS16));
		assertEquals(0, GraphemeClusters.baseWidth(SUPPLEMENTARY_VARIATION_SELECTOR));
	}

	public void testIsContinuationOfZwj() {
		assertTrue(GraphemeClusters.isContinuation(ZWJ, EMOJI_WOMAN));
		assertTrue(GraphemeClusters.isContinuation(ZWJ, 'a'));
		// Without the ZWJ in front, a wide emoji is not a continuation:
		assertFalse(GraphemeClusters.isContinuation(EMOJI_MAN, EMOJI_WOMAN));
	}

	public void testIsContinuationOfRegionalIndicator() {
		assertTrue(GraphemeClusters.isContinuation(REGIONAL_INDICATOR_A, REGIONAL_INDICATOR_B));
		// A regional indicator followed by anything else is not a continuation:
		assertFalse(GraphemeClusters.isContinuation(REGIONAL_INDICATOR_A, 'a'));
		assertFalse(GraphemeClusters.isContinuation('a', REGIONAL_INDICATOR_B));
		assertFalse(GraphemeClusters.isContinuation(EMOJI_MAN, REGIONAL_INDICATOR_B));
	}

	public void testIsContinuationOfSkinToneModifier() {
		assertTrue(GraphemeClusters.isContinuation(EMOJI_MAN, SKIN_TONE_MODIFIER));
		assertTrue(GraphemeClusters.isContinuation('a', SKIN_TONE_MODIFIER));
		assertFalse(GraphemeClusters.isContinuation(EMOJI_MAN, EMOJI_BOY));
	}

	public void testIsContinuationOfVariationSelector() {
		assertTrue(GraphemeClusters.isContinuation(EMOJI_MAN, VS16));
		assertTrue(GraphemeClusters.isContinuation(EMOJI_MAN, VS15));
		assertTrue(GraphemeClusters.isContinuation(EMOJI_MAN, SUPPLEMENTARY_VARIATION_SELECTOR));
		// A variation selector is not a one way street: what follows it is a new cluster.
		assertFalse(GraphemeClusters.isContinuation(VS16, EMOJI_MAN));
	}

	public void testIsContinuationOfZeroWidth() {
		assertTrue(GraphemeClusters.isContinuation('a', COMBINING_DIARESIS));
		// ZWJ itself has no display width, so it is always absorbed:
		assertTrue(GraphemeClusters.isContinuation('a', ZWJ));
		assertFalse(GraphemeClusters.isContinuation('a', 'b'));
	}

	public void testClusterEndAndWidthOfSimpleChars() {
		char[] text = charsOf('a');
		assertEquals(1, GraphemeClusters.clusterEnd(text, 0, text.length));
		assertEquals(1, GraphemeClusters.clusterWidth(text, 0, text.length));
		assertTrue(GraphemeClusters.clusterEnd(text, 0, text.length) > 0);

		text = charsOf('a', 'b');
		assertEquals(1, GraphemeClusters.clusterEnd(text, 0, text.length));
		assertEquals(2, GraphemeClusters.clusterEnd(text, 1, text.length));
	}

	public void testClusterEndAndWidthOfCombining() {
		char[] text = charsOf('a', COMBINING_DIARESIS);
		assertEquals(2, GraphemeClusters.clusterEnd(text, 0, text.length));
		assertEquals(1, GraphemeClusters.clusterWidth(text, 0, text.length));
	}

	public void testClusterEndAndWidthOfFlagPair() {
		char[] text = charsOf(REGIONAL_INDICATOR_A, REGIONAL_INDICATOR_B);
		assertEquals(4, GraphemeClusters.clusterEnd(text, 0, text.length));
		assertEquals(2, GraphemeClusters.clusterWidth(text, 0, text.length));
	}

	public void testClusterEndAndWidthOfZwjSequence() {
		char[] text = charsOf(EMOJI_MAN, ZWJ, EMOJI_WOMAN, ZWJ, EMOJI_BOY);
		assertEquals(8, GraphemeClusters.clusterEnd(text, 0, text.length));
		assertEquals(2, GraphemeClusters.clusterWidth(text, 0, text.length));
	}

	public void testClusterEndAndWidthOfSkinTone() {
		char[] text = charsOf(EMOJI_MAN, SKIN_TONE_MODIFIER);
		assertEquals(4, GraphemeClusters.clusterEnd(text, 0, text.length));
		assertEquals(2, GraphemeClusters.clusterWidth(text, 0, text.length));
	}

	public void testClusterEndOfEmptyText() {
		char[] text = charsOf(REGIONAL_INDICATOR_A);
		// An empty cluster is impossible: clusterEnd() always advances past the base code point.
		assertEquals(2, GraphemeClusters.clusterEnd(text, 0, text.length));
	}

}
