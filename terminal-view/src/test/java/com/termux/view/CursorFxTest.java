package com.termux.view;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CursorFxTest {

    @Test
    public void blinkAlphaFullAtPeriodStartAndEnd() {
        assertEquals(1f, CursorFx.blinkAlpha(0, 1000), 0.0001f);
        assertEquals(1f, CursorFx.blinkAlpha(1000, 1000), 0.0001f);
        assertEquals(1f, CursorFx.blinkAlpha(2000, 1000), 0.0001f);
    }

    @Test
    public void blinkAlphaZeroAtHalfPeriod() {
        assertEquals(0f, CursorFx.blinkAlpha(500, 1000), 0.0001f);
        assertEquals(0f, CursorFx.blinkAlpha(1500, 1000), 0.0001f);
    }

    @Test
    public void blinkAlphaQuarterPeriodIsHalf() {
        assertEquals(0.5f, CursorFx.blinkAlpha(250, 1000), 0.0001f);
        assertEquals(0.5f, CursorFx.blinkAlpha(750, 1000), 0.0001f);
    }

    @Test
    public void blinkAlphaDisabledPeriodIsFull() {
        assertEquals(1f, CursorFx.blinkAlpha(123, 0), 0.0001f);
        assertEquals(1f, CursorFx.blinkAlpha(123, -5), 0.0001f);
    }

    @Test
    public void easeTowardsMovesProportionally() {
        assertEquals(5f, CursorFx.easeTowards(0f, 10f, 0.5f), 0.0001f);
        assertEquals(10f, CursorFx.easeTowards(0f, 10f, 1f), 0.0001f);
        assertEquals(0f, CursorFx.easeTowards(0f, 10f, 0f), 0.0001f);
    }

    @Test
    public void easeTowardsClampsFactor() {
        assertEquals(10f, CursorFx.easeTowards(0f, 10f, 2f), 0.0001f);
        assertEquals(0f, CursorFx.easeTowards(0f, 10f, -1f), 0.0001f);
    }

    @Test
    public void easeTowardsConvergesFromAbove() {
        assertEquals(7.5f, CursorFx.easeTowards(10f, 0f, 0.25f), 0.0001f);
    }
}
