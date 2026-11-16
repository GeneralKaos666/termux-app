package com.termux.app.domain.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionOutputTrackerTest {

    @Test
    fun `output on an inactive session marks it unseen`() {
        val tracker = SessionOutputTracker()
        tracker.recordOutput(3, active = false)
        assertTrue(tracker.hasUnseen(3))
    }

    @Test
    fun `output on the active session does not mark it`() {
        val tracker = SessionOutputTracker()
        tracker.recordOutput(3, active = true)
        assertFalse(tracker.hasUnseen(3))
    }

    @Test
    fun `selecting a session clears it`() {
        val tracker = SessionOutputTracker()
        tracker.recordOutput(3, active = false)
        tracker.clear(3)
        assertFalse(tracker.hasUnseen(3))
    }

    @Test
    fun `removing an id forgets it`() {
        val tracker = SessionOutputTracker()
        tracker.recordOutput(3, active = false)
        tracker.remove(3)
        assertFalse(tracker.hasUnseen(3))
        assertEquals(emptySet<Int>(), tracker.unseen())
    }

    @Test
    fun `unseen lists every marked id`() {
        val tracker = SessionOutputTracker()
        tracker.recordOutput(3, active = false)
        tracker.recordOutput(9, active = false)
        assertEquals(setOf(3, 9), tracker.unseen())
    }
}
