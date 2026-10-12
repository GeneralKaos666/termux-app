package com.termux.app.ui.firstrun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Unit tests for the first-run onboarding state defaults and assignment. */
class FirstRunStateTest {

    @Test
    fun `defaults are hidden and not installing`() {
        val state = FirstRunState()
        assertFalse(state.visible)
        assertFalse(state.installing)
    }

    @Test
    fun `visible and installing flags propagate on assignment`() {
        val state = FirstRunState()
        state.visible = true
        state.installing = true
        assertEquals(true, state.visible)
        assertEquals(true, state.installing)
        state.visible = false
        assertFalse(state.visible)
    }
}
