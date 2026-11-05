package com.termux.view;

/**
 * The range of absolute screen rows a draw must cover.
 *
 * <p>Computed from the canvas clip so a partial invalidate repaints only the rows it touches, with one
 * row of slack on either side. Kept free of {@code android.*} types so it can be unit tested without a
 * device or Robolectric.
 */
final class RenderBand {

    /** Absolute screen row to start at, never less than the view's top row. */
    final int firstRow;

    /** Absolute screen row to stop before, never past the latest visible row. */
    final int lastRowExclusive;

    private RenderBand(int firstRow, int lastRowExclusive) {
        this.firstRow = firstRow;
        this.lastRowExclusive = lastRowExclusive;
    }

    /**
     * @param clipKnown whether the canvas returned a usable clip; a false return means the caller's clip
     *                  rectangle holds stale data and must not be trusted.
     */
    static RenderBand forClip(int topRow, int screenRows, int rowHeight,
                              boolean clipKnown, int clipTop, int clipBottom) {
        int visibleEnd = topRow + screenRows;
        int firstScreenRow = topRow, lastScreenRowExclusive = visibleEnd;
        if (clipKnown && clipTop != clipBottom && rowHeight > 0) {
            firstScreenRow = Math.max(topRow, topRow + Math.floorDiv(clipTop, rowHeight) - 1);
            lastScreenRowExclusive = Math.min(visibleEnd, topRow + ceilDivInt(clipBottom, rowHeight) + 1);
        }
        if (lastScreenRowExclusive <= firstScreenRow) {
            // Never hand the renderer an empty band: fall back to the whole visible range.
            firstScreenRow = topRow;
            lastScreenRowExclusive = visibleEnd;
        }
        return new RenderBand(firstScreenRow, lastScreenRowExclusive);
    }

    /** Integer ceiling division for non-negative values; Java 8 has no {@code Math.ceilDiv}. */
    private static int ceilDivInt(int a, int b) {
        return (a + b - 1) / b;
    }
}
