package com.termux.view

/**
 * Pure math for the cursor's visual effects: the fade-blink envelope and the eased movement step.
 *
 * Kept free of `android.*` types so it is unit-testable on the JVM (see `CursorFxTest`),
 * in the same spirit as `RenderBand`.
 */
object CursorFx {

    /**
     * The cursor opacity for the triangular blink envelope at `elapsedMs` into a blink cycle of
     * `periodMs`: full at the start and end of the cycle, fully faded at the half cycle, and linear
     * in between. A non-positive period means blinking is disabled, so the cursor stays fully opaque.
     */
    @JvmStatic
    fun blinkAlpha(elapsedMs: Long, periodMs: Long): Float {
        if (periodMs <= 0) return 1f
        var phase = elapsedMs % periodMs
        if (phase < 0) phase += periodMs
        val position = phase.toFloat() / periodMs.toFloat()
        return Math.abs(2f * position - 1f)
    }

    /**
     * One time-based exponential-smoothing step from `current` towards `target`: the
     * remaining distance halves every `halfLifeMs`, so the glide speed depends on elapsed time,
     * not on callback cadence. A non-positive `dtMs` means no time has passed, so the value
     * holds; a non-positive `halfLifeMs` is degenerate and snaps to `target`.
     */
    @JvmStatic
    fun easeTowards(current: Float, target: Float, dtMs: Float, halfLifeMs: Float): Float {
        if (dtMs <= 0f) return current
        if (halfLifeMs <= 0f) return target
        val factor = 1f - Math.pow(0.5, (dtMs / halfLifeMs).toDouble()).toFloat()
        return current + (target - current) * factor
    }
}
