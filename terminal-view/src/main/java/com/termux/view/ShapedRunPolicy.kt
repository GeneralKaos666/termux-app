package com.termux.view

/**
 * Decides, from the shaped advances the platform reported for one run, whether the run lands on the terminal cell
 * grid. A run that does (and any run when the platform cannot report advances) is drawn as one shaped span, so
 * ligatures and complex-script shaping survive; a run that does not is cut at the first cell boundary its glyph
 * boundaries miss, and the pieces are drawn on their own so no glyph can drift into the next cell.
 *
 * The decision is boundary-based rather than cluster-identity-based, so it does not care whether the platform
 * reports one entry per glyph, per cluster or per character - only the cumulative advances are read. It reads no
 * Android types and holds no state.
 */
object ShapedRunPolicy {

    /** Tolerance as a fraction of one cell; the same 1% spirit as the renderer's per-codepoint width check. */
    const val TOLERANCE_FRACTION = 0.01f

    /**
     * @param advances     the run's shaped advances in pixels; only `[0, advanceCount)` is read
     * @param advanceCount how many entries `advances` holds
     * @param runColumns   how many cells the run spans
     * @param cellWidth    the width of one cell in pixels
     * @return `-1` when every cell boundary (including the run's total width) coincides with a glyph
     * boundary within tolerance, `-1` for a run of fewer than two cells (nothing to verify), otherwise the
     * first 1-based cell boundary that no glyph boundary coincides with. Degenerate input (no entries, a
     * non-positive or non-finite cell width) is reported as cell 1, that is, misaligned.
     */
    @JvmStatic
    fun firstMisalignedCell(advances: FloatArray, advanceCount: Int, runColumns: Int, cellWidth: Float): Int {
        if (runColumns < 2) return -1
        if (advanceCount <= 0) return 1
        if (!(cellWidth > 0f)) return 1

        val tolerance = TOLERANCE_FRACTION * cellWidth
        var position = 0f
        var entry = 0
        for (cell in 1..runColumns) {
            val boundary = cell * cellWidth
            var boundaryHit = false
            while (true) {
                if (Math.abs(position - boundary) <= tolerance) {
                    boundaryHit = true
                    break
                }
                // Walk glyph boundaries up to the first one at or past this cell boundary; entries are consumed
                // monotonically, so the whole run costs one pass.
                if (position > boundary || entry >= advanceCount) break
                position += advances[entry]
                entry++
            }
            if (!boundaryHit) return cell
        }
        return -1
    }
}
