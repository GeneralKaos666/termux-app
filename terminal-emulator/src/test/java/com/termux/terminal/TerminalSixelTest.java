package com.termux.terminal;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Tests for the pure color logic of {@link TerminalSixel}: the initial colour map, palette-index
 * selection and the RGB (0-100) → ARGB conversion. The pixel-drawing side ({@link
 * TerminalSixel#readData}) needs native graphics and is exercised on device instead.
 */
public class TerminalSixelTest {

    private static TerminalSixel newSixel() {
        // The bitmap is unused by the colour logic, so a null one is fine here.
        return new TerminalSixel(null, null);
    }

    @Test
    public void initialColorMapMatchesPublishedPalette() {
        int[] map = newSixel().getColorMap();
        assertEquals(256, map.length);
        assertEquals(0xFF000000, map[0]);
        assertEquals(0xFF3333CC, map[1]);
        assertEquals(0xFFCC2323, map[2]);
        assertEquals(0xFF33CC33, map[3]);
        assertEquals(0xFFCC33CC, map[4]);
        assertEquals(0xFF33CCCC, map[5]);
        assertEquals(0xFFCCCC33, map[6]);
        assertEquals(0xFF777777, map[7]);
        assertEquals(0xFF444444, map[8]);
        assertEquals(0xFF565699, map[9]);
        assertEquals(0xFF994444, map[10]);
        assertEquals(0xFF569956, map[11]);
        assertEquals(0xFF995699, map[12]);
        assertEquals(0xFF569999, map[13]);
        assertEquals(0xFF999956, map[14]);
        assertEquals(0xFFCCCCCC, map[15]);
        // The extended 16-255 range starts unset.
        assertEquals(0, map[16]);
        assertEquals(0, map[255]);
    }

    @Test
    public void initialColorIsFirstPaletteEntry() {
        assertEquals(0xFF000000, newSixel().getColor());
    }

    @Test
    public void setColorSelectsFromPalette() {
        TerminalSixel sixel = newSixel();
        sixel.setColor(1);
        assertEquals(0xFF3333CC, sixel.getColor());
        sixel.setColor(15);
        assertEquals(0xFFCCCCCC, sixel.getColor());
        sixel.setColor(16);
        assertEquals(0, sixel.getColor());
    }

    @Test
    public void setColorOutOfRangeIsIgnored() {
        TerminalSixel sixel = newSixel();
        sixel.setColor(3);
        assertEquals(0xFF33CC33, sixel.getColor());
        sixel.setColor(-1);
        assertEquals(0xFF33CC33, sixel.getColor());
        sixel.setColor(256);
        assertEquals(0xFF33CC33, sixel.getColor());
    }

    @Test
    public void setRGBColorScalesAndStores() {
        TerminalSixel sixel = newSixel();

        sixel.setRGBColor(0, 100, 100, 100);
        assertEquals(0xFFFFFFFF, sixel.getColor());
        assertEquals(0xFFFFFFFF, sixel.getColorMap()[0]);

        // 50/100 → 127 per channel.
        sixel.setRGBColor(1, 50, 0, 0);
        assertEquals(0xFF7F0000, sixel.getColor());

        sixel.setRGBColor(2, 0, 50, 0);
        assertEquals(0xFF007F00, sixel.getColor());

        sixel.setRGBColor(3, 0, 0, 50);
        assertEquals(0xFF00007F, sixel.getColor());
    }

    @Test
    public void setRGBColorClampsAboveRange() {
        TerminalSixel sixel = newSixel();
        // 200/100 → 510 → clamped to 255.
        sixel.setRGBColor(0, 200, 200, 200);
        assertEquals(0xFFFFFFFF, sixel.getColor());
    }

    @Test
    public void setRGBColorStoresIntoPaletteForLaterSelection() {
        TerminalSixel sixel = newSixel();
        sixel.setRGBColor(7, 100, 0, 0); // writes 0xFFFF0000 into index 7.
        assertEquals(0xFFFF0000, sixel.getColorMap()[7]);

        sixel.setColor(7);
        assertEquals(0xFFFF0000, sixel.getColor());
    }

    @Test
    public void setRGBColorOutOfRangeIndexIsIgnored() {
        TerminalSixel sixel = newSixel();
        sixel.setRGBColor(-1, 100, 0, 0);
        assertEquals(0xFF000000, sixel.getColor());
        assertEquals(0, sixel.getColorMap()[255]);

        sixel.setRGBColor(256, 100, 0, 0);
        assertEquals(0xFF000000, sixel.getColor());
    }
}
