package com.termux.app.ui.firstrun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the first-run onboarding state defaults and assignment. */
class FirstRunStateTest {

    @Test
    fun `defaults are hidden and not installing`() {
        val state = FirstRunState()
        assertFalse(state.visible)
        assertFalse(state.installing)
        assertNull(state.stage)
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

    @Test
    fun `installing without a stage shows preparing`() {
        val state = FirstRunState()
        state.beginInstall()
        assertTrue(state.installing)
        // No stage yet means the download has not been announced: the overlay shows "preparing".
        assertNull(state.stage)
    }

    @Test
    fun `stage updates are observable`() {
        val state = FirstRunState()
        state.stage = BootstrapStage.EXTRACTING
        assertEquals(BootstrapStage.EXTRACTING, state.stage)
        state.stage = BootstrapStage.FINISHING
        assertEquals(BootstrapStage.FINISHING, state.stage)
    }

    @Test
    fun `beginning an install clears a stale stage`() {
        val state = FirstRunState()
        state.stage = BootstrapStage.EXTRACTING
        state.beginInstall()
        assertNull(state.stage)
    }

    @Test
    fun `failure clears stage and installing`() {
        val state = FirstRunState()
        state.beginInstall()
        state.stage = BootstrapStage.DOWNLOADING
        state.installFailed()
        assertFalse(state.installing)
        assertNull(state.stage)
    }
}
