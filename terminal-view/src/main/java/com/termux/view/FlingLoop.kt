package com.termux.view

/**
 * Controls how long the fling frame loop runs.
 *
 * Kept free of `android.*` types so it is unit-testable on the JVM (see `FlingLoopTest`), in the
 * same spirit as `CursorFx` and `RenderBand`.
 */
object FlingLoop {

    /**
     * Whether the fling loop must post another frame, given the value
     * `Scroller.computeScrollOffset()` just returned and `Scroller.isFinished` read afterwards.
     *
     * Both inputs are required because `computeScrollOffset()` returns `true` on the very tick that
     * also sets `isFinished`. Posting a frame on that "true" runs the loop once more, where the
     * early return recycles the loop's `MotionEvent` copy a second time.
     */
    @JvmStatic
    fun continues(computeReportedMore: Boolean, scrollerFinished: Boolean): Boolean =
        computeReportedMore && !scrollerFinished
}
