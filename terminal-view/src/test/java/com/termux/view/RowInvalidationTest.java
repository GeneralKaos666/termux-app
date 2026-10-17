package com.termux.view;

import junit.framework.TestCase;

public class RowInvalidationTest extends TestCase {

    public void testNoneWhenCounterEqualAndNoDirty() {
        boolean[] dirty = new boolean[10];
        Object result = RowInvalidation.decide(
                5L, 5L, false, dirty, 0, 10, 100, 10);
        assertEquals(RowInvalidation.Result.NONE, result);
    }

    public void testCounterJumpForcesFull() {
        boolean[] dirty = new boolean[5];
        dirty[2] = true;
        Object result = RowInvalidation.decide(
                1L, 2L, false, dirty, 0, 5, 50, 10);
        assertEquals(RowInvalidation.Result.FULL, result);
    }

    public void testCounterDecreaseForcesFull() {
        boolean[] dirty = new boolean[5];
        dirty[1] = true;
        Object result = RowInvalidation.decide(
                5L, 3L, false, dirty, 0, 5, 50, 10);
        assertEquals(RowInvalidation.Result.FULL, result);
    }

    public void testDirtyContiguousSpan() {
        boolean[] dirty = new boolean[10];
        dirty[2] = true;
        dirty[3] = true;
        dirty[4] = true;
        Object result = RowInvalidation.decide(
                5L, 5L, false, dirty, 0, 10, 100, 10);
        assertTrue(result instanceof RowInvalidation.Range);
        RowInvalidation.Range range = (RowInvalidation.Range) result;
        assertEquals(20, range.topPx);
        assertEquals(50, range.bottomPx);
    }

    public void testDirtyScatteredGivesSpan() {
        boolean[] dirty = new boolean[20];
        dirty[1] = true;
        dirty[10] = true;
        dirty[5] = true;
        Object result = RowInvalidation.decide(
                1L, 1L, false, dirty, 0, 20, 200, 10);
        assertTrue(result instanceof RowInvalidation.Range);
        RowInvalidation.Range range = (RowInvalidation.Range) result;
        assertEquals(10, range.topPx);
        assertEquals(110, range.bottomPx);
    }

    public void testDirtySpanIncludesTopRow() {
        boolean[] dirty = new boolean[15];
        dirty[0] = true;
        Object result = RowInvalidation.decide(
                0L, 0L, false, dirty, -2, 15, 150, 10);
        assertTrue(result instanceof RowInvalidation.Range);
        RowInvalidation.Range range = (RowInvalidation.Range) result;
        assertEquals(20, range.topPx);
        assertEquals(30, range.bottomPx);
    }

    public void testTopRowChangeForcesFull() {
        boolean[] dirty = new boolean[10];
        dirty[5] = true;
        Object result = RowInvalidation.decide(
                7L, 7L, true, dirty, 5, 10, 100, 10);
        assertEquals(RowInvalidation.Result.FULL, result);
    }

    public void testSpanClampedToViewHeight() {
        boolean[] dirty = new boolean[30];
        dirty[0] = true;
        dirty[25] = true;
        Object result = RowInvalidation.decide(
                1L, 1L, false, dirty, 0, 30, 50, 2);
        assertTrue(result instanceof RowInvalidation.Range);
        RowInvalidation.Range range = (RowInvalidation.Range) result;
        assertEquals(0, range.topPx);
        assertEquals(50, range.bottomPx);
    }
}
