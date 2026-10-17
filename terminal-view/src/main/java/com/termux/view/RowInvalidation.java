package com.termux.view;

/**
 * Pure decision logic for renderer dirty-region invalidation.
 *
 * <p>Deliberately free of Android imports: it only does arithmetic on viewport state the caller
 * already knows (change counters, per-visible-row dirty flags, scroll offset, view height and font
 * line spacing), so it can be exercised by plain JUnit tests on the JVM.
 *
 * <p>Package-private: this is an implementation detail of {@link TerminalView} and
 * {@link TerminalRenderer}.
 */
final class RowInvalidation {

    /**
     * Classifies the outcome of {@link #decide}: {@link #NONE} and {@link #FULL} are returned as-is,
     * while the {@link #RANGE} outcome is carried by a {@link Range} payload.
     */
    enum Result {
        NONE,
        FULL,
        RANGE
    }

    /** Payload of the {@link Result#RANGE} outcome: the pixel band to repaint, relative to the view. */
    static final class Range {
        /** Top edge, in pixels, clamped to {@code [0, viewHeight]}. */
        final int topPx;
        /** Bottom edge (exclusive), in pixels, clamped to {@code [0, viewHeight]}. */
        final int bottomPx;

        Range(int topPx, int bottomPx) {
            this.topPx = topPx;
            this.bottomPx = bottomPx;
        }

        /** The outcome this payload belongs to; always {@link Result#RANGE}. */
        Result kind() {
            return Result.RANGE;
        }
    }

    private RowInvalidation() {
    }

    /**
     * Decides how much of the terminal must be repainted for the visible rows.
     *
     * @param lastCounter    The change counter observed at the previous draw, or {@code -1} when
     *                       nothing has been drawn yet (session/emulator rebind).
     * @param currentCounter The change counter of the buffer that is about to be drawn.
     * @param topRowChanged  Whether the viewport scrolled since the previous draw.
     * @param dirtyByIndex   Per-visible-row dirty flags, index {@code 0} being the top visible row.
     *                       May be {@code null}.
     * @param topRow         The top visible screen row; negative when scrolled into history.
     * @param rows           Number of visible rows; entries past it are ignored.
     * @param viewHeight     The view height in pixels; {@code <= 0} means "unknown, do not clamp".
     * @param fontLineSpacing The height of a single text row, in pixels.
     * @return {@link Result#NONE}, {@link Result#FULL}, or a {@link Range} payload for the
     *         {@link Result#RANGE} outcome.
     */
    static Object decide(long lastCounter, long currentCounter, boolean topRowChanged,
                         boolean[] dirtyByIndex, int topRow, int rows, int viewHeight,
                         int fontLineSpacing) {
        // A counter change (including a decrease, which is defensive: a jump backwards is still a
        // jump) means our picture of the screen is stale; a scroll moves every row on screen.
        if (topRowChanged || currentCounter != lastCounter) return Result.FULL;

        int firstDirty = -1;
        int lastDirty = -1;
        if (dirtyByIndex != null) {
            int rowsToScan = Math.min(dirtyByIndex.length, rows);
            for (int i = 0; i < rowsToScan; i++) {
                if (dirtyByIndex[i]) {
                    if (firstDirty < 0) firstDirty = i;
                    lastDirty = i;
                }
            }
        }
        if (firstDirty < 0) return Result.NONE;

        int topPx = (firstDirty - topRow) * fontLineSpacing;
        int bottomPx = (lastDirty - topRow + 1) * fontLineSpacing;
        if (topPx < 0) topPx = 0;
        if (viewHeight > 0 && bottomPx > viewHeight) bottomPx = viewHeight;
        // The band is entirely outside the viewport: repaint everything rather than nothing.
        if (bottomPx <= topPx) return Result.FULL;
        return new Range(topPx, bottomPx);
    }
}
