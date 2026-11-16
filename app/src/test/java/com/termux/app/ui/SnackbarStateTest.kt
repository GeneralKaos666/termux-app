package com.termux.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests for the snackbar request queue, in particular the visual bell's throttle. */
class SnackbarStateTest {

    @Test
    fun `repeated bells within the throttle show once`() {
        var now = 0L
        val state = SnackbarState(clock = { now })

        state.showBell("Bell")
        now = 100
        state.showBell("Bell")

        assertEquals(1, state.sequence)
        assertEquals("Bell", state.message)
    }

    @Test
    fun `a bell after the throttle shows again`() {
        var now = 0L
        val state = SnackbarState(clock = { now })

        state.showBell("Bell")
        now = BELL_THROTTLE_MILLIS + 1
        state.showBell("Bell")

        assertEquals(2, state.sequence)
    }

    @Test
    fun `a bell keeps the message of the last bell that was shown`() {
        var now = 0L
        val state = SnackbarState(clock = { now })

        state.showBell("Bell")
        now = 100
        state.showBell("Another bell")

        assertEquals("Bell", state.message)
    }

    @Test
    fun `a bell clears a pending undo label`() {
        var now = 0L
        val state = SnackbarState(clock = { now })

        state.post("Renamed", "Undo")
        state.showBell("Bell")

        assertEquals("Bell", state.message)
        assertEquals(null, state.undoLabel)
    }
}
