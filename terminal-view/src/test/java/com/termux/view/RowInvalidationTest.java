package com.termux.view;

import junit.framework.TestCase;

/**
 * Unit tests for {@link RowInvalidation}. The helper is viewport-relative: index {@code 0} of the
 * dirty flags is the top visible row, and the returned band is already in view pixels, so no test
 * ever passes a scroll offset.
 */
public class RowInvalidationTest extends TestCase {

    public void testNoneWhenCounterEqualAndNoDirty() {
        boolean[] dirty = new boolean[10];
        RowInvalidation.Decision result = RowInvalidation.decide(
                5L, 5L, false, dirty, 10, 100, 10);
        assertSame(RowInvalidation.Decision.NONE, result);
        assertEquals(RowInvalidation.Result.NONE, result.kind);
    }

    public void testNullDirtyFlagsGivesNone() {
        RowInvalidation.Decision result = RowInvalidation.decide(
                5L, 5L, false, null, 10, 100, 10);
        assertSame(RowInvalidation.Decision.NONE, result);
    }

    public void testCounterJumpForcesFull() {
        boolean[] dirty = new boolean[5];
        dirty[2] = true;
        RowInvalidation.Decision result = RowInvalidation.decide(
                1L, 2L, false, dirty, 5, 50, 10);
        assertSame(RowInvalidation.Decision.FULL, result);
        assertEquals(RowInvalidation.Result.FULL, result.kind);
    }

    public void testCounterDecreaseForcesFull() {
        boolean[] dirty = new boolean[5];
        dirty[1] = true;
        RowInvalidation.Decision result = RowInvalidation.decide(
                5L, 3L, false, dirty, 5, 50, 10);
        assertSame(RowInvalidation.Decision.FULL, result);
    }

    public void testDirtyContiguousSpan() {
        boolean[] dirty = new boolean[10];
        dirty[2] = true;
        dirty[3] = true;
        dirty[4] = true;
        RowInvalidation.Decision result = RowInvalidation.decide(
                5L, 5L, false, dirty, 10, 100, 10);
        assertEquals(RowInvalidation.Result.RANGE, result.kind);
        assertEquals(20, result.topPx);
        assertEquals(50, result.bottomPx);
    }

    public void testDirtyScatteredGivesSpan() {
        boolean[] dirty = new boolean[20];
        dirty[1] = true;
        dirty[10] = true;
        dirty[5] = true;
        RowInvalidation.Decision result = RowInvalidation.decide(
                1L, 1L, false, dirty, 20, 200, 10);
        assertEquals(RowInvalidation.Result.RANGE, result.kind);
        assertEquals(10, result.topPx);
        assertEquals(110, result.bottomPx);
    }

    public void testDirtySpanIncludesTopRow() {
        // Viewport-relative: index 2 is the third visible row, at 20px, whatever the scroll offset.
        boolean[] dirty = new boolean[15];
        dirty[2] = true;
        RowInvalidation.Decision thirdRow = RowInvalidation.decide(
                0L, 0L, false, dirty, 15, 150, 10);
        assertEquals(RowInvalidation.Result.RANGE, thirdRow.kind);
        assertEquals(20, thirdRow.topPx);
        assertEquals(30, thirdRow.bottomPx);

        // The first visible row's band starts at the top edge of the view.
        boolean[] topDirty = new boolean[15];
        topDirty[0] = true;
        RowInvalidation.Decision firstRow = RowInvalidation.decide(
                0L, 0L, false, topDirty, 15, 150, 10);
        assertEquals(RowInvalidation.Result.RANGE, firstRow.kind);
        assertEquals(0, firstRow.topPx);
        assertEquals(10, firstRow.bottomPx);
    }

    public void testTopRowChangeForcesFull() {
        boolean[] dirty = new boolean[10];
        dirty[5] = true;
        RowInvalidation.Decision result = RowInvalidation.decide(
                7L, 7L, true, dirty, 10, 100, 10);
        assertSame(RowInvalidation.Decision.FULL, result);
    }

    public void testSpanClampedToViewHeight() {
        boolean[] dirty = new boolean[30];
        dirty[0] = true;
        dirty[25] = true;
        RowInvalidation.Decision result = RowInvalidation.decide(
                1L, 1L, false, dirty, 30, 50, 2);
        assertEquals(RowInvalidation.Result.RANGE, result.kind);
        assertEquals(0, result.topPx);
        assertEquals(50, result.bottomPx);
    }

    /** Dirty rows below the visible area: the band would be empty, so repaint everything. */
    public void testDirtyIndexBeyondViewHeightForcesFull() {
        boolean[] dirty = new boolean[10];
        dirty[5] = true;
        RowInvalidation.Decision result = RowInvalidation.decide(
                1L, 1L, false, dirty, 10, 30, 10);
        assertEquals(RowInvalidation.Result.FULL, result.kind);
    }

    public void testNonPositiveViewHeightIsUnbounded() {
        boolean[] dirty = new boolean[10];
        dirty[2] = true;
        RowInvalidation.Decision zeroHeight = RowInvalidation.decide(
                1L, 1L, false, dirty, 10, 0, 10);
        assertEquals(RowInvalidation.Result.RANGE, zeroHeight.kind);
        assertEquals(20, zeroHeight.topPx);
        assertEquals(30, zeroHeight.bottomPx);

        RowInvalidation.Decision negativeHeight = RowInvalidation.decide(
                1L, 1L, false, dirty, 10, -100, 10);
        assertEquals(RowInvalidation.Result.RANGE, negativeHeight.kind);
        assertEquals(20, negativeHeight.topPx);
        assertEquals(30, negativeHeight.bottomPx);
    }

    public void testNonPositiveLineSpacingForcesFull() {
        boolean[] dirty = new boolean[10];
        dirty[2] = true;
        RowInvalidation.Decision zeroSpacing = RowInvalidation.decide(
                1L, 1L, false, dirty, 10, 100, 0);
        assertEquals(RowInvalidation.Result.FULL, zeroSpacing.kind);

        RowInvalidation.Decision negativeSpacing = RowInvalidation.decide(
                1L, 1L, false, dirty, 10, 100, -10);
        assertEquals(RowInvalidation.Result.FULL, negativeSpacing.kind);
    }

    public void testDirtyFlagsBeyondRowCountIgnored() {
        // Flags are longer than the visible row count: index 8 must not widen the band.
        boolean[] dirty = new boolean[10];
        dirty[1] = true;
        dirty[8] = true;
        RowInvalidation.Decision result = RowInvalidation.decide(
                1L, 1L, false, dirty, 3, 100, 10);
        assertEquals(RowInvalidation.Result.RANGE, result.kind);
        assertEquals(10, result.topPx);
        assertEquals(20, result.bottomPx);
    }

    public void testDirtyFlagsShorterThanRowCount() {
        boolean[] dirty = new boolean[5];
        dirty[3] = true;
        RowInvalidation.Decision result = RowInvalidation.decide(
                1L, 1L, false, dirty, 20, 200, 10);
        assertEquals(RowInvalidation.Result.RANGE, result.kind);
        assertEquals(30, result.topPx);
        assertEquals(40, result.bottomPx);
    }
}
