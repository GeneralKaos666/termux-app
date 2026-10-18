package com.termux.view;

import junit.framework.TestCase;

/**
 * Unit tests for {@link ShapedRunPolicy}: given the shaped advance array the platform reported for a run, which
 * cell boundary (if any) the run does not land on. Advances are in pixels and a cell is {@code cellWidth} wide.
 */
public class ShapedRunPolicyTest extends TestCase {

    public void testAlignedRunHasNoMisalignedCell() {
        assertEquals(-1, ShapedRunPolicy.firstMisalignedCell(new float[]{10f, 10f, 10f}, 3, 3, 10f));
    }

    public void testLigatureAdvanceFailsAtTheCellItsClusterSkips() {
        // A 10px glyph then a 37px cluster in a 3-cell run: glyph boundaries at 10 and 47, cell boundaries at
        // 10, 20 and 30. The 20px boundary is the first one no glyph boundary coincides with.
        assertEquals(2, ShapedRunPolicy.firstMisalignedCell(new float[]{10f, 37f}, 2, 3, 10f));
    }

    public void testFirstBoundaryMissIsReported() {
        assertEquals(1, ShapedRunPolicy.firstMisalignedCell(new float[]{15f}, 1, 3, 10f));
    }

    public void testRunWhoseOnlyMissIsItsTotalReportsTheLastBoundary() {
        // Boundaries 10 and 20: the glyph boundary at 10 hits, the one at 20 does not, and 20 is also the run's
        // total width, so the caller draws one span and condenses it.
        assertEquals(2, ShapedRunPolicy.firstMisalignedCell(new float[]{10f, 10.5f}, 2, 2, 10f));
    }

    public void testBoundaryWithinToleranceIsAligned() {
        // cellWidth 8 -> tolerance 0.08 (1% of a cell, the same spirit as the renderer's per-codepoint check).
        assertEquals(-1, ShapedRunPolicy.firstMisalignedCell(new float[]{8f, 8f + 0.08f}, 2, 2, 8f));
    }

    public void testBoundaryJustOutsideToleranceIsMisaligned() {
        assertEquals(2, ShapedRunPolicy.firstMisalignedCell(new float[]{8f, 8f + 0.5f}, 2, 2, 8f));
    }

    public void testSeveralGlyphsPerCellStillHitEveryBoundary() {
        // Two 5px glyphs per 10px cell: the cell boundaries are hit by the glyph boundaries inside the cells.
        assertEquals(-1, ShapedRunPolicy.firstMisalignedCell(new float[]{5f, 5f, 5f, 5f}, 4, 2, 10f));
    }

    public void testPerGlyphAndPerClusterReportingAgree() {
        // The same geometry, reported once per glyph and once per cluster, must get the same verdict.
        assertEquals(-1, ShapedRunPolicy.firstMisalignedCell(new float[]{5f, 5f, 10f}, 3, 2, 10f));
        assertEquals(-1, ShapedRunPolicy.firstMisalignedCell(new float[]{10f, 10f}, 2, 2, 10f));
        assertEquals(2, ShapedRunPolicy.firstMisalignedCell(new float[]{5f, 5f, 30f}, 3, 2, 10f));
        assertEquals(2, ShapedRunPolicy.firstMisalignedCell(new float[]{10f, 30f}, 2, 2, 10f));
    }

    public void testFloatNoiseOfALongRunStaysAligned() {
        // 200 cells of two glyphs summing to 13.999999... per cell: the boundary at 14 is off by float noise only.
        float[] advances = new float[400];
        for (int i = 0; i < advances.length; i += 2) {
            advances[i] = 7.02f;
            advances[i + 1] = 6.98f;
        }
        assertEquals(-1, ShapedRunPolicy.firstMisalignedCell(advances, advances.length, 200, 7f));
    }

    public void testNoEntriesWrittenIsMisaligned() {
        assertEquals(1, ShapedRunPolicy.firstMisalignedCell(new float[0], 0, 3, 10f));
    }

    public void testStaleTailBeyondTheEntryCountIsIgnored() {
        // Only [0, advanceCount) is read, so a poisoned entry past the count cannot change the verdict.
        assertEquals(-1, ShapedRunPolicy.firstMisalignedCell(new float[]{10f, 10f, 999f}, 2, 2, 10f));
    }

    public void testNonFiniteAdvancesAreMisaligned() {
        assertEquals(2, ShapedRunPolicy.firstMisalignedCell(new float[]{10f, Float.NaN}, 2, 2, 10f));
        assertEquals(2, ShapedRunPolicy.firstMisalignedCell(new float[]{10f, Float.POSITIVE_INFINITY}, 2, 2, 10f));
    }

    public void testZeroAndNegativeEntriesNeverThrow() {
        // A leading zero-width entry (a combining mark reported on its own) leaves every boundary hit.
        assertEquals(-1, ShapedRunPolicy.firstMisalignedCell(new float[]{0f, 10f, 10f, 10f, 10f}, 5, 4, 10f));
        // A negative advance cannot coincide with a boundary; the verdict is a misaligned cell, not an exception.
        assertEquals(2, ShapedRunPolicy.firstMisalignedCell(new float[]{-10f, 10f, 10f}, 3, 3, 10f));
    }

    public void testDegenerateGeometryIsMisalignedOrSkipped() {
        assertEquals(1, ShapedRunPolicy.firstMisalignedCell(new float[]{10f, 10f}, 2, 2, 0f));
        assertEquals(1, ShapedRunPolicy.firstMisalignedCell(new float[]{10f, 10f}, 2, 2, Float.NaN));
        assertEquals(-1, ShapedRunPolicy.firstMisalignedCell(new float[]{10f, 10f}, 2, 1, 10f));
    }
}
