package com.termux.terminal;

import static org.junit.Assert.assertArrayEquals;

import com.termux.terminal.kitty.KittyGraphicsCommand;
import com.termux.terminal.kitty.KittyGraphicsParser;
import com.termux.terminal.kitty.KittyImageSizing;

import org.junit.Test;

/** Tests for the pure kitty image sizing helper. */
public class KittyImageSizingTest {

    @Test
    public void explicitPixelSizeWins() {
        KittyGraphicsCommand cmd = KittyGraphicsParser.parse("Ga=T,w=7,h=9;", null);
        assertArrayEquals(new int[] {7, 9},
            KittyImageSizing.targetPixels(cmd, 10, 20, 1, 1));
    }

    @Test
    public void cellSpanIsMultipliedByTheCellSize() {
        KittyGraphicsCommand cmd = KittyGraphicsParser.parse("Ga=T,c=2,r=1;", null);
        assertArrayEquals(new int[] {20, 20},
            KittyImageSizing.targetPixels(cmd, 10, 20, 1, 1));
    }

    @Test
    public void decodedSizeIsKeptWhenNoSizeKeysAreGiven() {
        KittyGraphicsCommand cmd = KittyGraphicsParser.parse("Ga=T;", null);
        assertArrayEquals(new int[] {4, 5},
            KittyImageSizing.targetPixels(cmd, 10, 20, 4, 5));
    }

    @Test
    public void zeroHintsNeverResize() {
        KittyGraphicsCommand cmd = KittyGraphicsParser.parse("Ga=T,c=0,r=0,w=0,h=0;", null);
        assertArrayEquals(new int[] {4, 5},
            KittyImageSizing.targetPixels(cmd, 0, 0, 4, 5));
    }
}
