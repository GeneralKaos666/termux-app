package com.termux.view;

import junit.framework.TestCase;

/**
 * Unit tests for {@link RowInvalidation}. The helper is viewport-relative: index {@code 0} of the
 * dirty flags is the top visible row, and the returned band is already in view pixels, so no test
 * ever passes a scroll offset.
 *
 * <p>The band is offset by the row origin ({@code fontLineSpacingAndAscent}), because a row's cell is
 * drawn below the top edge of the view by that amount; these tests pass {@code 4} for it wherever the
 * row height is {@code 10}.
 */
public class RowInvalidationTest extends TestCase {

    private static final int ORIGIN = 4;

    public void testNoneWhenCounterEqualAndNoDirty() {
        boolean[] dirty = new boolean[10];
        RowInvalidation.Decision result = RowInvalidation.decide(
                5L, 5L, false, dirty, 10, 100, 10, ORIGIN);
        assertSame(RowInvalidation.Decision.NONE, result);
        assertEquals(RowInvalidation.Result.NONE, result.kind);
    }

    public void testNullDirtyFlagsGivesNone() {
        RowInvalidation.Decision result = RowInvalidation.decide(
                5L, 5L, false, null, 10, 100, 10, ORIGIN);
        assertSame(RowInvalidation.Decision.NONE, result);
    }

    public void testCounterJumpForcesFull() {
        boolean[] dirty = new boolean[5];
        dirty[2] = true;
        RowInvalidation.Decision result = RowInvalidation.decide(
                1L, 2L, false, dirty, 5, 50, 10, ORIGIN);
        assertSame(RowInvalidation.Decision.FULL, result);
        assertEquals(RowInvalidation.Result.FULL, result.kind);
    }

    public void testCounterDecreaseForcesFull() {
        boolean[] dirty = new boolean[5];
        dirty[1] = true;
        RowInvalidation.Decision result = RowInvalidation.decide(
                5L, 3L, false, dirty, 5, 50, 10, ORIGIN);
        assertSame(RowInvalidation.Decision.FULL, result);
    }

    public void testDirtyContiguousSpan() {
        boolean[] dirty = new boolean[10];
        dirty[2] = true;
        dirty[3] = true;
        dirty[4] = true;
        RowInvalidation.Decision result = RowInvalidation.decide(
                5L, 5L, false, dirty, 10, 100, 10, ORIGIN);
        assertEquals(RowInvalidation.Result.RANGE, result.kind);
        assertEquals(ORIGIN + 20, result.topPx);
        assertEquals(ORIGIN + 50, result.bottomPx);
    }

    public void testDirtyScatteredGivesSpan() {
        boolean[] dirty = new boolean[20];
        dirty[1] = true;
        dirty[10] = true;
        dirty[5] = true;
        RowInvalidation.Decision result = RowInvalidation.decide(
                1L, 1L, false, dirty, 20, 200, 10, ORIGIN);
        assertEquals(RowInvalidation.Result.RANGE, result.kind);
        assertEquals(ORIGIN + 10, result.topPx);
        assertEquals(ORIGIN + 110, result.bottomPx);
    }

    public void testDirtySpanIncludesTopRow() {
        // Viewport-relative: index 2 is the third visible row, at 20px plus the row origin, whatever
        // the scroll offset.
        boolean[] dirty = new boolean[15];
        dirty[2] = true;
        RowInvalidation.Decision thirdRow = RowInvalidation.decide(
                0L, 0L, false, dirty, 15, 150, 10, ORIGIN);
        assertEquals(RowInvalidation.Result.RANGE, thirdRow.kind);
        assertEquals(ORIGIN + 20, thirdRow.topPx);
        assertEquals(ORIGIN + 30, thirdRow.bottomPx);

        // The first visible row's band starts at the row origin, not at the top edge of the view.
        boolean[] topDirty = new boolean[15];
        topDirty[0] = true;
        RowInvalidation.Decision firstRow = RowInvalidation.decide(
                0L, 0L, false, topDirty, 15, 150, 10, ORIGIN);
        assertEquals(RowInvalidation.Result.RANGE, firstRow.kind);
        assertEquals(ORIGIN, firstRow.topPx);
        assertEquals(ORIGIN + 10, firstRow.bottomPx);
    }

    public void testTopRowChangeForcesFull() {
        boolean[] dirty = new boolean[10];
        dirty[5] = true;
        RowInvalidation.Decision result = RowInvalidation.decide(
                7L, 7L, true, dirty, 10, 100, 10, ORIGIN);
        assertSame(RowInvalidation.Decision.FULL, result);
    }

    public void testSpanClampedToViewHeight() {
        boolean[] dirty = new boolean[30];
        dirty[0] = true;
        dirty[25] = true;
        RowInvalidation.Decision result = RowInvalidation.decide(
                1L, 1L, false, dirty, 30, 50, 2, 1);
        assertEquals(RowInvalidation.Result.RANGE, result.kind);
        assertEquals(1, result.topPx);
        assertEquals(50, result.bottomPx);
    }

    /** Dirty rows below the visible area: the band would be empty, so repaint everything. */
    public void testDirtyIndexBeyondViewHeightForcesFull() {
        boolean[] dirty = new boolean[10];
        dirty[5] = true;
        RowInvalidation.Decision result = RowInvalidation.decide(
                1L, 1L, false, dirty, 10, 30, 10, ORIGIN);
        assertEquals(RowInvalidation.Result.FULL, result.kind);
    }

    public void testNonPositiveViewHeightIsUnbounded() {
        boolean[] dirty = new boolean[10];
        dirty[2] = true;
        RowInvalidation.Decision zeroHeight = RowInvalidation.decide(
                1L, 1L, false, dirty, 10, 0, 10, ORIGIN);
        assertEquals(RowInvalidation.Result.RANGE, zeroHeight.kind);
        assertEquals(ORIGIN + 20, zeroHeight.topPx);
        assertEquals(ORIGIN + 30, zeroHeight.bottomPx);

        RowInvalidation.Decision negativeHeight = RowInvalidation.decide(
                1L, 1L, false, dirty, 10, -100, 10, ORIGIN);
        assertEquals(RowInvalidation.Result.RANGE, negativeHeight.kind);
        assertEquals(ORIGIN + 20, negativeHeight.topPx);
        assertEquals(ORIGIN + 30, negativeHeight.bottomPx);
    }

    public void testNonPositiveLineSpacingForcesFull() {
        boolean[] dirty = new boolean[10];
        dirty[2] = true;
        RowInvalidation.Decision zeroSpacing = RowInvalidation.decide(
                1L, 1L, false, dirty, 10, 100, 0, ORIGIN);
        assertEquals(RowInvalidation.Result.FULL, zeroSpacing.kind);

        RowInvalidation.Decision negativeSpacing = RowInvalidation.decide(
                1L, 1L, false, dirty, 10, 100, -10, ORIGIN);
        assertEquals(RowInvalidation.Result.FULL, negativeSpacing.kind);
    }

    public void testDirtyFlagsBeyondRowCountIgnored() {
        // Flags are longer than the visible row count: index 8 must not widen the band.
        boolean[] dirty = new boolean[10];
        dirty[1] = true;
        dirty[8] = true;
        RowInvalidation.Decision result = RowInvalidation.decide(
                1L, 1L, false, dirty, 3, 100, 10, ORIGIN);
        assertEquals(RowInvalidation.Result.RANGE, result.kind);
        assertEquals(ORIGIN + 10, result.topPx);
        assertEquals(ORIGIN + 20, result.bottomPx);
    }

    public void testDirtyFlagsShorterThanRowCount() {
        boolean[] dirty = new boolean[5];
        dirty[3] = true;
        RowInvalidation.Decision result = RowInvalidation.decide(
                1L, 1L, false, dirty, 20, 200, 10, ORIGIN);
        assertEquals(RowInvalidation.Result.RANGE, result.kind);
        assertEquals(ORIGIN + 30, result.topPx);
        assertEquals(ORIGIN + 40, result.bottomPx);
    }
}
