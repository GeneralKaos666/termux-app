package com.termux.app.ui.sessions

import com.termux.app.domain.sessions.SessionItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionsStateTest {

    @Test
    fun `every session is listed as built`() {
        val state = SessionsState()
        state.sessions = listOf(SessionItem(1, "a", true, 0, true))
        assertEquals(listOf(1), state.sessions.map { it.id })
    }

    @Test
    fun `selecting a session clears its unseen flag`() {
        val state = SessionsState()
        state.unseen = setOf(3)
        state.markSelected(3)
        assertTrue(3 !in state.unseen)
    }

    @Test
    fun `marking unseen adds the id once`() {
        val state = SessionsState()
        state.markUnseen(7)
        state.markUnseen(7)
        assertEquals(setOf(7), state.unseen)
    }

    @Test
    fun `selecting an unrelated id keeps the mark`() {
        val state = SessionsState()
        state.unseen = setOf(3)
        state.markSelected(4)
        assertEquals(setOf(3), state.unseen)
    }
}
