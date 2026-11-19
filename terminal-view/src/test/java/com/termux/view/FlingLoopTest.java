package com.termux.view;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FlingLoopTest {

    @Test
    public void continuesWhileScrollerIsRunning() {
        assertTrue(FlingLoop.continues(true, false));
    }

    @Test
    public void stopsOnTheTickThatFinishesTheScroller() {
        // Scroller.computeScrollOffset() returns true on the same call that sets isFinished. Posting
        // another frame because of that "true" ran the loop once more, recycled the frame's
        // MotionEvent copy a second time and killed the app with "recycled twice!".
        assertFalse(FlingLoop.continues(true, true));
    }

    @Test
    public void stopsWhenComputeReportsTheEnd() {
        assertFalse(FlingLoop.continues(false, true));
        assertFalse(FlingLoop.continues(false, false));
    }
}
