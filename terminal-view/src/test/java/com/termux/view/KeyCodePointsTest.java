package com.termux.view;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class KeyCodePointsTest {

    @Test
    public void controlMapsLettersToTheirControlCode() {
        for (int i = 0; i < 26; i++) {
            assertEquals(i + 1, KeyCodePoints.applyControl('a' + i));
            assertEquals(i + 1, KeyCodePoints.applyControl('A' + i));
        }
    }

    @Test
    public void controlMapsSpaceAndTwoToNul() {
        assertEquals(0, KeyCodePoints.applyControl(' '));
        assertEquals(0, KeyCodePoints.applyControl('2'));
    }

    @Test
    public void controlMapsBracketsAndDigits() {
        assertEquals(27, KeyCodePoints.applyControl('['));
        assertEquals(27, KeyCodePoints.applyControl('3'));
        assertEquals(28, KeyCodePoints.applyControl('\\'));
        assertEquals(28, KeyCodePoints.applyControl('4'));
        assertEquals(29, KeyCodePoints.applyControl(']'));
        assertEquals(29, KeyCodePoints.applyControl('5'));
        assertEquals(30, KeyCodePoints.applyControl('^'));
        assertEquals(30, KeyCodePoints.applyControl('6'));
        assertEquals(31, KeyCodePoints.applyControl('_'));
        assertEquals(31, KeyCodePoints.applyControl('7'));
        assertEquals(31, KeyCodePoints.applyControl('/'));
        assertEquals(127, KeyCodePoints.applyControl('8'));
    }

    @Test
    public void controlLeavesOtherCodePointsAlone() {
        assertEquals('9', KeyCodePoints.applyControl('9'));
        assertEquals('0', KeyCodePoints.applyControl('0'));
        assertEquals('1', KeyCodePoints.applyControl('1'));
        assertEquals('!', KeyCodePoints.applyControl('!'));
        assertEquals(0x20AC, KeyCodePoints.applyControl(0x20AC));
        assertEquals(27, KeyCodePoints.applyControl(27));
    }

    @Test
    public void externalKeyboardModifierLettersAreNormalized() {
        assertEquals('~', KeyCodePoints.normalizeExternalKeyboard(0x02DC));
        assertEquals('`', KeyCodePoints.normalizeExternalKeyboard(0x02CB));
        assertEquals('^', KeyCodePoints.normalizeExternalKeyboard(0x02C6));
        assertEquals('a', KeyCodePoints.normalizeExternalKeyboard('a'));
        assertEquals(0x20AC, KeyCodePoints.normalizeExternalKeyboard(0x20AC));
    }
}
