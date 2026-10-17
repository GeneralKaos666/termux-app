package com.termux.view;

import com.termux.terminal.TerminalBuffer;

/**
 * Pure decision logic for renderer dirty-region invalidation.
 *
 * <p>The arithmetic here is Android-free and runs on a plain JVM. The one method that touches the
 * emulator ({@link #dirtyByIndex}) only reads flags through {@link com.termux.terminal.TerminalBuffer};
 * because that class' constructor touches {@code android.os.SystemClock}, the tests enable Gradle's
 * {@code testOptions { unitTests.returnDefaultValues = true }} (test scope only).
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
     * offset — {@link #dirtyByIndex} is the single place that owns the {@code screen row = topRow + index}
     * mapping — so the band it returns is already view-relative: {@code topPx = firstDirtyIndex * fontLineSpacing} and
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

    /**
     * Reads the buffer's dirty flags for the visible rows and returns them as viewport-relative flags.
     *
     * <p><b>This is the one place the {@code externalRow = topRow + index} mapping lives.</b> Index
     * {@code 0} is the external row {@code topRow} (the top visible row) and index {@code rows - 1} the
     * bottom one, so the result is exactly the input {@link #decide} expects and no other caller has to
     * know about the scroll offset. {@code topRow} may be negative: the transcript band above the screen
     * is addressable as {@code -1}, {@code -2}, ... down to {@code -terminalBuffer.getActiveTranscriptRows()}.
     *
     * <p>The returned flags are a fresh array, so the caller may keep them while the buffer keeps
     * changing.
     *
     * @param screen The buffer to read, or {@code null} (meaning "nothing dirty").
     * @param topRow The external row shown at the top of the viewport; negative reads the transcript. It
     *               must not be older than the oldest transcript row (the caller clamps {@code mTopRow} to
     *               {@code -getActiveTranscriptRows()} before building the flags), because
     *               {@link com.termux.terminal.TerminalBuffer#isRowDirty} throws past that bound.
     * @param rows   The number of visible rows. Anything {@code <= 0} means an empty viewport.
     * @return An array of {@code rows} viewport-relative flags, or an empty array when {@code screen} is
     *         {@code null} or {@code rows <= 0} ({@link #decide} tolerates a short, even empty, array).
     * @throws IllegalArgumentException if {@code topRow} is older than the oldest transcript row.
     */
    static boolean[] dirtyByIndex(TerminalBuffer screen, int topRow, int rows) {
        if (screen == null || rows <= 0) return new boolean[0];
        boolean[] dirty = new boolean[rows];
        for (int i = 0; i < rows; i++) dirty[i] = screen.isRowDirty(topRow + i);
        return dirty;
    }
}
