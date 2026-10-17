package com.termux.terminal;

import junit.framework.TestCase;

public class TextStyleTest extends TestCase {

	private static final int[] ALL_EFFECTS = new int[]{0, TextStyle.CHARACTER_ATTRIBUTE_BOLD, TextStyle.CHARACTER_ATTRIBUTE_ITALIC,
			TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE, TextStyle.CHARACTER_ATTRIBUTE_BLINK, TextStyle.CHARACTER_ATTRIBUTE_INVERSE,
			TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE, TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH, TextStyle.CHARACTER_ATTRIBUTE_PROTECTED,
			TextStyle.CHARACTER_ATTRIBUTE_DIM};

	public void testEncodingSingle() {
		for (int fx : ALL_EFFECTS) {
			for (int fg = 0; fg < TextStyle.NUM_INDEXED_COLORS; fg++) {
				for (int bg = 0; bg < TextStyle.NUM_INDEXED_COLORS; bg++) {
					long encoded = TextStyle.encode(fg, bg, fx);
					assertEquals(fg, TextStyle.decodeForeColor(encoded));
					assertEquals(bg, TextStyle.decodeBackColor(encoded));
					assertEquals(fx, TextStyle.decodeEffect(encoded));
				}
			}
		}
	}

	public void testEncoding24Bit() {
		int[] values = {255, 240, 127, 1, 0};
		for (int red : values) {
			for (int green : values) {
				for (int blue : values) {
					int argb = 0xFF000000 | (red << 16) | (green << 8) | blue;
					long encoded = TextStyle.encode(argb, 0, 0);
					assertEquals(argb, TextStyle.decodeForeColor(encoded));
					encoded = TextStyle.encode(0, argb, 0);
					assertEquals(argb, TextStyle.decodeBackColor(encoded));
				}
			}
		}
	}


	public void testEncodingCombinations() {
		for (int f1 : ALL_EFFECTS) {
			for (int f2 : ALL_EFFECTS) {
				int combined = f1 | f2;
				assertEquals(combined, TextStyle.decodeEffect(TextStyle.encode(0, 0, combined)));
			}
		}
	}

	public void testEncodingStrikeThrough() {
		long encoded = TextStyle.encode(TextStyle.COLOR_INDEX_FOREGROUND, TextStyle.COLOR_INDEX_BACKGROUND,
				TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH);
		assertTrue((TextStyle.decodeEffect(encoded) & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0);
	}

	public void testEncodingProtected() {
		long encoded = TextStyle.encode(TextStyle.COLOR_INDEX_FOREGROUND, TextStyle.COLOR_INDEX_BACKGROUND,
				TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH);
		assertEquals(0, (TextStyle.decodeEffect(encoded) & TextStyle.CHARACTER_ATTRIBUTE_PROTECTED));
		encoded = TextStyle.encode(TextStyle.COLOR_INDEX_FOREGROUND, TextStyle.COLOR_INDEX_BACKGROUND,
				TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH | TextStyle.CHARACTER_ATTRIBUTE_PROTECTED);
		assertTrue((TextStyle.decodeEffect(encoded) & TextStyle.CHARACTER_ATTRIBUTE_PROTECTED) != 0);
	}

	/** Applying an underline style must not disturb the other fields of an encoded style word. */
	public void testUnderlineStyleRoundTrip() {
		for (int underlineStyle = TextStyle.UNDERLINE_STYLE_SINGLE; underlineStyle <= TextStyle.UNDERLINE_STYLE_DASHED; underlineStyle++) {
			long encoded = TextStyle.encode(TextStyle.COLOR_INDEX_FOREGROUND, TextStyle.COLOR_INDEX_BACKGROUND,
					TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE);
			long styled = TextStyle.encodeUnderline(encoded, underlineStyle);
			assertEquals(underlineStyle, TextStyle.decodeUnderlineStyle(styled));
			assertEquals(TextStyle.decodeEffect(encoded), TextStyle.decodeEffect(styled));
			assertEquals(TextStyle.decodeForeColor(encoded), TextStyle.decodeForeColor(styled));
			assertEquals(TextStyle.decodeBackColor(encoded), TextStyle.decodeBackColor(styled));
		}
	}

	/** A plain style word, and one carried through a plain {@link TextStyle#encode}, has no underline style. */
	public void testUnderlineStyleNone() {
		assertEquals(TextStyle.UNDERLINE_STYLE_NONE, TextStyle.decodeUnderlineStyle(TextStyle.NORMAL));
		long styled = TextStyle.encodeUnderline(TextStyle.NORMAL | TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE,
				TextStyle.UNDERLINE_STYLE_NONE);
		assertEquals(TextStyle.UNDERLINE_STYLE_NONE, TextStyle.decodeUnderlineStyle(styled));
	}

	/** The underline style must survive a style word that uses 24-bit (true) colors. */
	public void testUnderlineStyleDoesNotDisturbTrueColor() {
		int foreColor = 0xFF123456;
		int backColor = 0xFFABCDEF;
		long encoded = TextStyle.encode(foreColor, backColor, TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE);
		long styled = TextStyle.encodeUnderline(encoded, TextStyle.UNDERLINE_STYLE_CURLY);
		assertEquals(foreColor, TextStyle.decodeForeColor(styled));
		assertEquals(backColor, TextStyle.decodeBackColor(styled));
		assertEquals(TextStyle.decodeEffect(encoded), TextStyle.decodeEffect(styled));
		assertEquals(TextStyle.UNDERLINE_STYLE_CURLY, TextStyle.decodeUnderlineStyle(styled));
	}

}
