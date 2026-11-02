package com.termux.app.ui.floating

import android.content.Context
import com.termux.shared.shell.command.ExecutionCommand
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

/**
 * Unit tests for the float controller's decision logic: the overlay-permission gate and the
 * session-finished collapse. Window creation is exercised through a Robolectric [Context] whose
 * window manager never actually attaches the overlay view, so no Compose composition runs here.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class FloatControllerTest {

    /** A fake session source that records whether [createFloatSession] was invoked. */
    private class FakeSource(
        private val result: TermuxSession? = null,
    ) : FloatSessionSource {
        var calls = 0
            private set

        override fun createFloatSession(client: TerminalSessionClient): TermuxSession? {
            calls++
            return result
        }
    }

    private fun context(): Context = RuntimeEnvironment.getApplication()

    @Test
    fun `showNewSession does not create a session without the overlay permission`() {
        val source = FakeSource()
        val controller = FloatController(context(), source, canDrawOverlays = { false })

        controller.showNewSession()

        assertEquals(0, source.calls)
        assertFalse(controller.isShown())
    }

    @Test
    fun `showNewSession creates a session and shows the window when permitted`() {
        val source = FakeSource(newTermuxSession())
        val controller = FloatController(context(), source, canDrawOverlays = { true })

        controller.showNewSession()

        assertEquals(1, source.calls)
        assertTrue(controller.isShown())
    }

    @Test
    fun `onSessionFinished clears the session and hides the window`() {
        val source = FakeSource(newTermuxSession())
        val controller = FloatController(context(), source, canDrawOverlays = { true })
        controller.showNewSession()

        controller.onSessionFinished()

        assertNull(controller.floatSession)
        assertFalse(controller.isShown())
    }

    @Test
    fun `a second showNewSession reuses the existing session instead of creating another`() {
        val source = FakeSource(newTermuxSession())
        val controller = FloatController(context(), source, canDrawOverlays = { true })
        controller.showNewSession()

        controller.showNewSession()

        assertEquals(1, source.calls)
        assertTrue(controller.isShown())
    }

    /**
     * Builds a [TermuxSession] around a real [TerminalSession] without spawning a shell process.
     * [TermuxSession] has no public constructor (it is only produced by `TermuxSession.execute`),
     * so the private constructor is invoked reflectively.
     */
    private fun newTermuxSession(): TermuxSession {
        val terminalSession = TerminalSession("/system/bin/sh", "/", null, null, null, null)
        val constructor = TermuxSession::class.java.getDeclaredConstructor(
            TerminalSession::class.java,
            ExecutionCommand::class.java,
            TermuxSession.TermuxSessionClient::class.java,
            Boolean::class.javaPrimitiveType,
        )
        constructor.isAccessible = true
        return constructor.newInstance(terminalSession, ExecutionCommand(), null, false)
    }
}
