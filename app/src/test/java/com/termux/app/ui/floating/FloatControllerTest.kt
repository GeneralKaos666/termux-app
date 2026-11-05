package com.termux.app.ui.floating

import android.content.Context
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import com.termux.shared.shell.command.ExecutionCommand
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowWindowManagerImpl

/**
 * Unit tests for the float controller's decision logic: the overlay-permission gate and the
 * session-finished collapse. Window creation is exercised through a Robolectric [Context] whose
 * window manager never actually attaches the overlay view, so no Compose composition runs here.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class FloatControllerTest {

    /** A fake session source that records session creation and termination. */
    private class FakeSource(
        private val result: TermuxSession? = null,
    ) : FloatSessionSource {
        var calls = 0
            private set
        val finished = mutableListOf<TermuxSession>()

        override fun createFloatSession(client: TerminalSessionClient): TermuxSession? {
            calls++
            return result
        }

        override fun finishFloatSession(session: TermuxSession) {
            finished += session
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
    fun `stop terminates the float session's shell`() {
        val source = FakeSource(newTermuxSession())
        val controller = FloatController(context(), source, canDrawOverlays = { true })
        controller.showNewSession()
        val session = requireNotNull(controller.floatSession)

        controller.stop()

        assertEquals(listOf(session), source.finished)
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

    @Test
    fun `drag clamps position so the window cannot be moved off screen`() {
        // 400x400 screen, 200x200 window: the on-screen position is bounded to [0, 200].
        assertEquals(
            IntOffset(200, 0),
            clampDragPosition(100, 100, 200, 200, IntOffset(1000, -500), 400, 400),
        )
    }

    @Test
    fun `drag applies an in-bounds delta unchanged`() {
        assertEquals(
            IntOffset(150, 175),
            clampDragPosition(100, 100, 200, 200, IntOffset(50, 75), 400, 400),
        )
    }

    @Test
    fun `showNewSession installs the owners a ComposeView requires before adding it`() {
        val context = context()
        val controller = FloatController(context, FakeSource(newTermuxSession()), canDrawOverlays = { true })

        controller.showNewSession()

        val overlay = addedViews(context).single()
        assertNotNull(
            "ComposeView throws ViewTreeLifecycleOwner not found without this",
            overlay.findViewTreeLifecycleOwner(),
        )
        assertNotNull(
            "rememberSaveable needs a restored SavedStateRegistryOwner",
            overlay.findViewTreeSavedStateRegistryOwner(),
        )
    }

    @Test
    fun `the float terminal view has a renderer before Compose lays it out`() {
        val controller = FloatController(context(), FakeSource(newTermuxSession()), canDrawOverlays = { true })

        controller.showNewSession()

        // The first layout calls updateSize(), which dereferences the renderer.
        assertNotNull(
            "TerminalView.updateSize throws a NullPointerException without a renderer",
            controller.terminalView.mRenderer,
        )
    }

    @Test
    fun `hiding the window destroys its lifecycle so the recomposer is released`() {
        val context = context()
        val controller = FloatController(context, FakeSource(newTermuxSession()), canDrawOverlays = { true })
        controller.showNewSession()
        val overlay = addedViews(context).single()
        val owner = requireNotNull(overlay.findViewTreeLifecycleOwner())

        controller.hide()

        assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
        assertNull(overlay.findViewTreeLifecycleOwner())
        assertNull(overlay.findViewTreeSavedStateRegistryOwner())
    }

    /** The overlay views the controller handed to the window manager, in add order. */
    private fun addedViews(context: Context): List<View> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return (shadowOf(windowManager) as ShadowWindowManagerImpl).views
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
