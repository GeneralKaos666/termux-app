package com.termux.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link SixelParser}, the sixel DCS-data parser extracted from {@link TerminalEmulator}.
 * The {@code screen.sixel*}` calls no-op when no sixel has started, so these tests exercise the
 * parsing and validation logic without touching native graphics.
 */
public class SixelParserTest {

    private TerminalBuffer screen;

    @Before
    public void setUp() {
        TerminalEmulator emulator = new TerminalEmulator(new TerminalTestCase.MockTerminalOutput(), 80, 24, 13, 15, 48, null);
        screen = emulator.getScreen();
    }

    private SixelParser.Result parse(String dcs) {
        return SixelParser.process(dcs, screen, null, "SixelParser", 1024);
    }

    @Test
    public void dataCharactersAreValid() {
        assertTrue(parse("????").valid);
    }

    @Test
    public void basicColorIntroducerIsValid() {
        assertTrue(parse("#3").valid);
    }

    @Test
    public void colorAboveRangeIsInvalid() {
        assertFalse(parse("#256").valid);
    }

    @Test
    public void rgbColorIsValid() {
        assertTrue(parse("#1;2;50;50;50").valid);
    }

    @Test
    public void hlsColorIsIgnoredNotFatal() {
        // HLS is unsupported: the parser logs and ignores, but the DCS is not a fatal parse error.
        assertTrue(parse("#1;1;50;50;50").valid);
    }

    @Test
    public void unsupportedColorSpaceIsInvalid() {
        assertFalse(parse("#1;3;50;50;50").valid);
    }

    @Test
    public void repeatAboveMaxIsIgnoredNotFatal() {
        // A repeat above the max logs and ignores rather than failing the whole DCS.
        assertTrue(parse("!9000?").valid);
    }

    @Test
    public void invalidCharacterIsInvalid() {
        assertFalse(parse("&").valid);
    }

    @Test
    public void whitespaceIsSkipped() {
        assertTrue(parse(" \t\n\r").valid);
    }

    @Test
    public void rasterAttributesComputeCapacity() {
        // 100x100 → 10200 expected length; with a 1024 max length the parts-limited capacity wins.
        SixelParser.Result result = parse("\"1;1;100;100");
        assertTrue(result.valid);
        assertEquals(624, result.newArgsCapacity);
    }

    @Test
    public void smallRasterAttributesLeaveCapacityUnchanged() {
        SixelParser.Result result = parse("\"1;1;10;10");
        assertTrue(result.valid);
        assertEquals(-1, result.newArgsCapacity);
    }

    // --- setup args -----------------------------------------------------------

    private static SixelParser.Setup setup(String dcs) {
        return SixelParser.parseSetupArgs(dcs, 0);
    }

    @Test
    public void setupArgsPlainQ() {
        SixelParser.Setup s = setup("q");
        assertTrue(s.args != null);
        assertFalse(s.definitivelyNotSixel);
    }

    @Test
    public void setupArgsAllThreeParameters() {
        SixelParser.Setup s = setup("12;34;56;q");
        assertEquals(12, s.args[0]);
        assertEquals(34, s.args[1]);
        assertEquals(56, s.args[2]);
        assertFalse(s.definitivelyNotSixel);
    }

    @Test
    public void setupArgsPartialParameters() {
        assertEquals(1, setup("1;q").args[0]);
        assertEquals(2, setup("1;2;q").args[1]);
        assertEquals(1, setup("1q").args[0]);
    }

    @Test
    public void setupArgsMoreThanThreeIsDefinitivelyNotSixel() {
        SixelParser.Setup s = setup("1;2;3;4q");
        assertTrue(s.args == null);
        assertTrue(s.definitivelyNotSixel);
    }
}
