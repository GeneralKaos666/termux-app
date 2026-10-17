package com.termux.view;

/**
 * Pure decision logic for renderer dirty-region invalidation.
 *
 * <p>No Android imports: this helper only does arithmetic on viewport state the caller already knows
 * (dirty flags of the visible rows, the change counter, the row height and the view height), so it
 * can be unit tested on the JVM without Android.
 *
 * <p>Package-private on purpose: this is an implementation detail of {@link TerminalView} and
 * {@link TerminalRenderer}.
 */
final class RowInvalidation {

    /** Classifies a {@link Decision}. */
    enum Result {
        /** Nothing visible is dirty: no repaint is needed. */
        NONE,
        /** The view must be repainted in full (counter jump, scroll, or an unusable band). */
        FULL,
        /** Only the pixel band carried by the decision must be repainted. */
        RANGE
    }

    /** Outcome of {@link #decide}: a {@link Result#kind} plus a band that is only meaningful for {@link Result#RANGE}. */
    static final class Decision {
        /** Which of {@link Result#NONE}, {@link Result#FULL} or {@link Result#RANGE} this decision is. */
        final Result kind;
        /** Top edge of the band, in pixels relative to the top of the view. Meaningful only for {@link Result#RANGE}. */
        final int topPx;
        /** Bottom edge (exclusive) of the band, in pixels relative to the top of the view. Meaningful only for {@link Result#RANGE}. */
        final int bottomPx;

        /** The only {@link Result#NONE} decision; compare with {@code ==} or {@link #kind}. */
        static final Decision NONE = new Decision(Result.NONE, 0, 0);
        /** The only {@link Result#FULL} decision; compare with {@code ==} or {@link #kind}. */
        static final Decision FULL = new Decision(Result.FULL, 0, 0);

        private Decision(Result kind, int topPx, int bottomPx) {
            this.kind = kind;
            this.topPx = topPx;
            this.bottomPx = bottomPx;
        }

        /** Creates a {@link Result#RANGE} decision for an already clamped, non-empty band. */
        static Decision range(int topPx, int bottomPx) {
            return new Decision(Result.RANGE, topPx, bottomPx);
        }
    }

    private RowInvalidation() {
    }

    /**
     * Decides how much of the terminal must be repainted for the visible rows.
     *
     * <p><b>Coordinates:</b> {@code dirtyByIndex} is <b>viewport-relative</b>: index {@code 0} is the
     * top visible row and index {@code rows - 1} the bottom one. This helper never sees the scroll
     * offset (the caller owns the {@code screen row = topRow + index} mapping), so the band it returns
     * is already view-relative: {@code topPx = firstDirtyIndex * fontLineSpacing} and
     * {@code bottomPx = (lastDirtyIndex + 1) * fontLineSpacing}. The low end is therefore
     * non-negative by construction (a viewport-relative index times a positive row height). The high
     * end is clamped to {@code viewHeight} when {@code viewHeight > 0}; if the band ends up empty or
     * entirely outside the view (its bottom edge is at or above its top edge, e.g. the only dirty rows
     * are below the visible area), the decision is {@link Result#FULL} rather than a useless band.
     *
     * @param lastCounter    The change counter observed at the previous draw, or {@code -1} when
     *                       nothing has been drawn yet (session/emulator rebind).
     * @param currentCounter The change counter of the buffer that is about to be drawn.
     * @param topRowChanged  Whether the viewport scrolled since the previous draw; the caller tracks it.
     * @param dirtyByIndex   Per-visible-row dirty flags, viewport-relative (index {@code 0} = top
     *                       visible row). {@code null} is treated as "nothing dirty".
     * @param rows           Number of visible rows; flags at index {@code >= rows} are ignored.
     * @param viewHeight     The view height in pixels; {@code <= 0} means "unknown, do not clamp".
     * @param fontLineSpacing The height of a single text row in pixels; {@code <= 0} yields {@link Result#FULL}.
     * @return {@link Decision#NONE}, {@link Decision#FULL}, or a {@link Result#RANGE} decision.
     */
    static Decision decide(long lastCounter, long currentCounter, boolean topRowChanged,
                           boolean[] dirtyByIndex, int rows, int viewHeight, int fontLineSpacing) {
        // A counter change (including a decrease, which is defensive: a jump backwards is still a
        // jump) means our picture of the screen is stale; a scroll moves every visible row.
        if (topRowChanged || currentCounter != lastCounter) return Decision.FULL;
        // Without a row height no band can be computed, whatever the dirty flags say.
        if (fontLineSpacing <= 0) return Decision.FULL;

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
        if (firstDirty < 0) return Decision.NONE;

        int topPx = firstDirty * fontLineSpacing;
        int bottomPx = (lastDirty + 1) * fontLineSpacing;
        if (viewHeight > 0 && bottomPx > viewHeight) bottomPx = viewHeight;
        // The band is empty or entirely outside the view: repaint everything rather than nothing.
        if (bottomPx <= topPx) return Decision.FULL;
        return Decision.range(topPx, bottomPx);
    }
}
