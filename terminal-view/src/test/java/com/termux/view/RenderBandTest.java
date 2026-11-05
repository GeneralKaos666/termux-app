package com.termux.view;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RenderBandTest {
    private static void assertBand(int expectedFirst, int expectedLast, RenderBand band) {
        assertEquals(expectedFirst, band.firstRow);
        assertEquals(expectedLast, band.lastRowExclusive);
    }

    @Test
    public void fullBandWhenClipIsUnknown() {
        assertBand(-5, 19, RenderBand.forClip(-5, 24, 20, false, 0, 0));
        // A false getClipBounds() return leaves stale values in the Rect; they must not be used.
        assertBand(-5, 19, RenderBand.forClip(-5, 24, 20, false, 100, 200));
    }

    @Test
    public void fullBandWhenClipIsEmpty() {
        assertBand(0, 24, RenderBand.forClip(0, 24, 20, true, 0, 0));
    }

    @Test
    public void fullBandWhenRowHeightIsZero() {
        assertBand(0, 24, RenderBand.forClip(0, 24, 0, true, 100, 200));
    }

    @Test
    public void clipInsideTheBandKeepsOneRowOfSlack() {
        // rows 5-9 at 20px are y=100..199; slack -> rows 4..10 inclusive -> last exclusive 11
        assertBand(4, 11, RenderBand.forClip(0, 24, 20, true, 100, 200));
    }

    @Test
    public void clipLargerThanTheScreenIsClamped() {
        assertBand(-2, 22, RenderBand.forClip(-2, 24, 20, true, -1000, 5000));
    }

    @Test
    public void clipEntirelyAboveTheBandFallsBackToFullBand() {
        assertBand(0, 24, RenderBand.forClip(0, 24, 20, true, -1000, -500));
    }

    @Test
    public void clipEntirelyBelowTheBandFallsBackToFullBand() {
        assertBand(0, 24, RenderBand.forClip(0, 24, 20, true, 5000, 6000));
    }

    @Test
    public void negativeClipTopClampsToTheTopRow() {
        // floorDiv(-10, 20) is -1, so the first row would be topRow-2; the max() clamps it to topRow.
        assertBand(0, 3, RenderBand.forClip(0, 24, 20, true, -10, 40));
    }
}
