package com.termux.view;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ImeTextEncoderTest {

    private static void assertStroke(int expectedCodePoint, boolean expectedControl,
                                     ImeTextEncoder.KeyStroke stroke) {
        assertEquals(expectedCodePoint, stroke.codePoint);
        assertEquals(expectedControl, stroke.controlHeld);
    }

    @Test
    public void plainAsciiIsOneStrokeEach() {
        List<ImeTextEncoder.KeyStroke> strokes = ImeTextEncoder.encode("abc", false);
        assertEquals(3, strokes.size());
        assertStroke('a', false, strokes.get(0));
        assertStroke('b', false, strokes.get(1));
        assertStroke('c', false, strokes.get(2));
    }

    @Test
    public void shiftUppercasesBeforeAnythingElse() {
        assertStroke('A', false, ImeTextEncoder.encode("a", true).get(0));
    }

    @Test
    public void newlineBecomesControlM() {
        assertStroke('m', true, ImeTextEncoder.encode("\n", false).get(0));
    }

    @Test
    public void controlRangeIsRemappedWithControlHeld() {
        assertStroke('_', true, ImeTextEncoder.encode("\u001f", false).get(0));
        assertStroke('^', true, ImeTextEncoder.encode("\u001e", false).get(0));
        assertStroke(']', true, ImeTextEncoder.encode("\u001d", false).get(0));
        assertStroke('\\', true, ImeTextEncoder.encode("\u001c", false).get(0));
        assertStroke('a', true, ImeTextEncoder.encode("\u0001", false).get(0));
    }

    @Test
    public void escapeIsPassedThroughWithoutControl() {
        assertStroke(27, false, ImeTextEncoder.encode("\u001b", false).get(0));
    }

    @Test
    public void modifierLetterFromASoftKeyboardIsNotNormalized() {
        assertStroke(0x02DC, false, ImeTextEncoder.encode("\u02dc", false).get(0));
    }

    @Test
    public void emojiSurrogatePairIsOneStroke() {
        List<ImeTextEncoder.KeyStroke> strokes = ImeTextEncoder.encode("\uD83D\uDE00", false);
        assertEquals(1, strokes.size());
        assertStroke(0x1F600, false, strokes.get(0));
    }

    @Test
    public void highSurrogateAtTheEndIsReplacement() {
        assertStroke(0xFFFD, false, ImeTextEncoder.encode("a\uD83D", false).get(1));
    }

    @Test
    public void emptyTextHasNoStrokes() {
        assertTrue(ImeTextEncoder.encode("", false).isEmpty());
    }

    @Test
    public void loneLowSurrogateIsReplacement() {
        assertStroke(0xFFFD, false, ImeTextEncoder.encode("\uDE00", false).get(0));
    }

    @Test
    public void highSurrogateFollowedByNonLowKeepsBothCharacters() {
        List<ImeTextEncoder.KeyStroke> strokes = ImeTextEncoder.encode("\uD83Da", false);
        assertEquals(2, strokes.size());
        assertStroke(0xFFFD, false, strokes.get(0));
        assertStroke('a', false, strokes.get(1));
    }
}
