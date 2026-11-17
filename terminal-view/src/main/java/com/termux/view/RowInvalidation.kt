package com.termux.view

import com.termux.terminal.TerminalBuffer

/**
 * Pure decision logic for renderer dirty-region invalidation.
 *
 * The arithmetic here is Android-free and runs on a plain JVM. The one method that touches the
 * emulator ([dirtyByIndex]) only reads flags through [com.termux.terminal.TerminalBuffer];
 * because that class' constructor touches `android.os.SystemClock`, the tests enable Gradle's
 * `testOptions { unitTests.returnDefaultValues = true }` (test scope only).
 *
 * Public because Java callers in [TerminalView] and [TerminalRenderer] reach it directly - Kotlin has
 * no package-private visibility, and `internal` would mangle the names those Java files call.
 */
object RowInvalidation {

    /** Classifies a [Decision]. */
    enum class Result {
        /** Nothing visible is dirty: no repaint is needed. */
        NONE,

        /** The view must be repainted in full (counter jump, scroll, or an unusable band). */
        FULL,

        /** Only the pixel band carried by the decision must be repainted. */
        RANGE
    }

    /** Outcome of [decide]: a [Result] kind plus a band that is only meaningful for [Result.RANGE]. */
    class Decision private constructor(
        /** Which of [Result.NONE], [Result.FULL] or [Result.RANGE] this decision is. */
        @JvmField val kind: Result,
        /** Top edge of the band, in pixels relative to the top of the view. Meaningful only for [Result.RANGE]. */
        @JvmField val topPx: Int,
        /** Bottom edge (exclusive) of the band, in pixels relative to the top of the view. Meaningful only for [Result.RANGE]. */
        @JvmField val bottomPx: Int,
    ) {

        companion object {
            /** The only [Result.NONE] decision; compare with `==` or [kind]. */
            @JvmField
            val NONE = Decision(Result.NONE, 0, 0)

            /** The only [Result.FULL] decision; compare with `==` or [kind]. */
            @JvmField
            val FULL = Decision(Result.FULL, 0, 0)

            /** Creates a [Result.RANGE] decision for an already clamped, non-empty band. */
            @JvmStatic
            fun range(topPx: Int, bottomPx: Int): Decision {
                return Decision(Result.RANGE, topPx, bottomPx)
            }
        }
    }

    /**
     * Decides how much of the terminal must be repainted for the visible rows.
     *
     * **Coordinates:** `dirtyByIndex` is **viewport-relative**: index `0` is the
     * top visible row and index `rows - 1` the bottom one. This helper never sees the scroll
     * offset — [dirtyByIndex] is the single place that owns the `screen row = topRow + index`
     * mapping — so the band it returns is already view-relative: `topPx = firstDirtyIndex * fontLineSpacing` and
     * `bottomPx = (lastDirtyIndex + 1) * fontLineSpacing`. The low end is therefore
     * non-negative by construction (a viewport-relative index times a positive row height). The high
     * end is clamped to `viewHeight` when `viewHeight > 0`; if the band ends up empty or
     * entirely outside the view (its bottom edge is at or above its top edge, e.g. the only dirty rows
     * are below the visible area), the decision is [Result.FULL] rather than a useless band.
     *
     * @param lastCounter    The change counter observed at the previous draw, or `-1` when
     *                       nothing has been drawn yet (session/emulator rebind).
     * @param currentCounter The change counter of the buffer that is about to be drawn.
     * @param forceFull      Whether the caller has already decided the whole view must be repainted (the
     *                       viewport scrolled, or a view overlay such as the cursor cell or reverse video
     *                       changed).
     * @param dirtyByIndex   Per-visible-row dirty flags, viewport-relative (index `0` = top
     *                       visible row). `null` is treated as "nothing dirty".
     * @param rows           Number of visible rows; flags at index `>= rows` are ignored.
     * @param viewHeight     The view height in pixels; `<= 0` means "unknown, do not clamp".
     * @param fontLineSpacing The height of a single text row in pixels; `<= 0` yields [Result.FULL].
     * @return [Decision.NONE], [Decision.FULL], or a [Result.RANGE] decision.
     */
    @JvmStatic
    fun decide(
        lastCounter: Long, currentCounter: Long, forceFull: Boolean,
        dirtyByIndex: BooleanArray?, rows: Int, viewHeight: Int, fontLineSpacing: Int,
    ): Decision {
        // A counter change (including a decrease, which is defensive: a jump backwards is still a
        // jump) means our picture of the screen is stale; a scroll or an overlay change moves every
        // visible row.
        if (forceFull || currentCounter != lastCounter) return Decision.FULL
        // Without a row height no band can be computed, whatever the dirty flags say.
        if (fontLineSpacing <= 0) return Decision.FULL

        var firstDirty = -1
        var lastDirty = -1
        if (dirtyByIndex != null) {
            val rowsToScan = Math.min(dirtyByIndex.size, rows)
            for (i in 0 until rowsToScan) {
                if (dirtyByIndex[i]) {
                    if (firstDirty < 0) firstDirty = i
                    lastDirty = i
                }
            }
        }
        if (firstDirty < 0) return Decision.NONE

        val topPx = firstDirty * fontLineSpacing
        var bottomPx = (lastDirty + 1) * fontLineSpacing
        if (viewHeight > 0 && bottomPx > viewHeight) bottomPx = viewHeight
        // The band is empty or entirely outside the view: repaint everything rather than nothing.
        if (bottomPx <= topPx) return Decision.FULL
        return Decision.range(topPx, bottomPx)
    }

    /**
     * Reads the buffer's dirty flags for the visible rows and returns them as viewport-relative flags.
     *
     * **This is the one place the `externalRow = topRow + index` mapping lives.** Index
     * `0` is the external row `topRow` (the top visible row) and index `rows - 1` the
     * bottom one, so the result is exactly the input [decide] expects and no other caller has to
     * know about the scroll offset. `topRow` may be negative: the transcript band above the screen
     * is addressable as `-1`, `-2`, ... down to `-terminalBuffer.getActiveTranscriptRows()`.
     *
     * The returned flags are a fresh array, so the caller may keep them while the buffer keeps
     * changing.
     *
     * @param screen The buffer to read, or `null` (meaning "nothing dirty").
     * @param topRow The external row shown at the top of the viewport; negative reads the transcript. It
     *               must not be older than the oldest transcript row (the caller clamps `mTopRow` to
     *               `-getActiveTranscriptRows()` before building the flags), because
     *               [com.termux.terminal.TerminalBuffer.isRowDirty] throws past that bound.
     * @param rows   The number of visible rows. Anything `<= 0` means an empty viewport.
     * @return An array of `rows` viewport-relative flags, or an empty array when `screen` is
     *         `null` or `rows <= 0` ([decide] tolerates a short, even empty, array).
     * @throws IllegalArgumentException if `topRow` is older than the oldest transcript row.
     */
    @JvmStatic
    fun dirtyByIndex(screen: TerminalBuffer?, topRow: Int, rows: Int): BooleanArray {
        if (screen == null || rows <= 0) return BooleanArray(0)
        val dirty = BooleanArray(rows)
        for (i in 0 until rows) dirty[i] = screen.isRowDirty(topRow + i)
        return dirty
    }
}
