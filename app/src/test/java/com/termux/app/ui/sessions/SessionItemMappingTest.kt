package com.termux.app.ui.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the pure session-model mapping used by the sessions bar. */
class SessionItemMappingTest {

    @Test
    fun `running session is running regardless of exit code`() {
        assertEquals(SessionState.RUNNING, sessionState(running = true, exitCode = 0))
        assertEquals(SessionState.RUNNING, sessionState(running = true, exitCode = 130))
        assertEquals(SessionState.RUNNING, sessionState(running = true, exitCode = -1))
    }

    @Test
    fun `finished session with zero exit code succeeded`() {
        assertEquals(SessionState.EXITED_SUCCESS, sessionState(running = false, exitCode = 0))
    }

    @Test
    fun `finished session with non-zero exit code failed`() {
        assertEquals(SessionState.EXITED_ERROR, sessionState(running = false, exitCode = 1))
        assertEquals(SessionState.EXITED_ERROR, sessionState(running = false, exitCode = 130))
        assertEquals(SessionState.EXITED_ERROR, sessionState(running = false, exitCode = 127))
    }

    @Test
    fun `title prefers the user-set name`() {
        assertEquals("my shell", sessionTitle(3, "my shell", "bash"))
    }

    @Test
    fun `title falls back to the terminal title`() {
        assertEquals("bash", sessionTitle(3, null, "bash"))
        assertEquals("bash", sessionTitle(3, "   ", "bash"))
    }

    @Test
    fun `title falls back to a numbered label`() {
        assertEquals("Session 2", sessionTitle(2, null, null))
        assertEquals("Session 2", sessionTitle(2, " ", ""))
    }

    @Test
    fun `title fallback uses stable id so reordering does not rename sessions`() {
        // The fallback number must come from the session's stable id, not its list position:
        // the same session at list position 1 vs 3 keeps the same fallback title.
        assertEquals("Session 5", sessionTitle(5, null, null))
        assertEquals("Session 5", sessionTitle(5, "   ", ""))
    }

    @Test
    fun `factory fallback title follows the stable id, not the list position`() {
        val item = sessionItem(
            id = 5,
            name = null,
            terminalTitle = null,
            running = true,
            exitCode = 0,
            active = false,
        )

        assertEquals("Session 5", item.title)
    }

    @Test
    fun `factory composes title and state fields`() {
        val item = sessionItem(
            id = 1,
            name = null,
            terminalTitle = "zsh",
            running = false,
            exitCode = 0,
            active = true,
        )

        assertEquals(1, item.id)
        assertEquals("zsh", item.title)
        assertFalse(item.running)
        assertEquals(0, item.exitCode)
        assertTrue(item.active)
    }
}
