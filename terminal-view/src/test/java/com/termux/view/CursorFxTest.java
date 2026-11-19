package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
    public void blinkAlphaHoldsNearFullAwayFromHalfPeriod() {
        // 10% into the cycle the triangle is at 0.8; smoothstep lifts it to 0.896 so the cursor
        // reads solid instead of washed out for most of the blink.
        assertEquals(0.896f, CursorFx.blinkAlpha(100, 1000), 0.0001f);
        assertEquals(0.896f, CursorFx.blinkAlpha(900, 1000), 0.0001f);
    }

    @Test
    public void blinkAlphaHandlesNegativeElapsed() {
        assertEquals(0.5f, CursorFx.blinkAlpha(-250, 1000), 0.0001f);
        assertEquals(1f, CursorFx.blinkAlpha(-1000, 1000), 0.0001f);
    }

    @Test
    public void blinkAlphaDisabledPeriodIsFull() {
        assertEquals(1f, CursorFx.blinkAlpha(123, 0), 0.0001f);
        assertEquals(1f, CursorFx.blinkAlpha(123, -5), 0.0001f);
    }

    @Test
    public void easeTowardsHoldsStillWithoutTime() {
        assertEquals(3f, CursorFx.easeTowards(3f, 10f, 0f, 26f), 0.0001f);
    }

    @Test
    public void easeTowardsSnapsWithZeroHalfLife() {
        assertEquals(10f, CursorFx.easeTowards(0f, 10f, 16f, 0f), 0.0001f);
    }

    @Test
    public void easeTowardsHalvesDistanceOverOneHalfLife() {
        assertEquals(5f, CursorFx.easeTowards(0f, 10f, 26f, 26f), 0.0001f);
    }

    @Test
    public void easeTowardsCoversThreeQuartersOverTwoHalfLives() {
        assertEquals(7.5f, CursorFx.easeTowards(0f, 10f, 52f, 26f), 0.0001f);
    }

    @Test
    public void easeTowardsIsMonotonicInTime() {
        assertTrue(CursorFx.easeTowards(0f, 10f, 40f, 26f) > CursorFx.easeTowards(0f, 10f, 16f, 26f));
    }

    @Test
    public void easeTowardsNeverOvershoots() {
        assertEquals(10f, CursorFx.easeTowards(0f, 10f, 100000f, 26f), 0.01f);
    }

    @Test
    public void easeTowardsConvergesFromAbove() {
        assertEquals(5f, CursorFx.easeTowards(10f, 0f, 26f, 26f), 0.0001f);
    }
}
