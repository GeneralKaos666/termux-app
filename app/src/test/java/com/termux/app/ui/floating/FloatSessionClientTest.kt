package com.termux.app.ui.floating

import com.termux.terminal.TerminalSession
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for the float session client callback routing. */
class FloatSessionClientTest {

    private class RecordingListener : FloatSessionClient.Listener {
        var textChangedCount = 0
        var titleChangedCount = 0
        var colorsChangedCount = 0
        var sessionFinishedCount = 0

        override fun onTextChanged() {
            textChangedCount++
        }

        override fun onTitleChanged() {
            titleChangedCount++
        }

        override fun onColorsChanged() {
            colorsChangedCount++
        }

        override fun onSessionFinished() {
            sessionFinishedCount++
        }
    }

    /** A minimal session whose identity is unused by the forwarding client. */
    private fun session(): TerminalSession =
        TerminalSession("/system/bin/sh", "/", null, null, null, null)

    @Test
    fun `onTextChanged forwards to the listener exactly once`() {
        val listener = RecordingListener()
        FloatSessionClient(listener).onTextChanged(session())
        assertEquals(1, listener.textChangedCount)
    }

    @Test
    fun `onTitleChanged forwards to the listener exactly once`() {
        val listener = RecordingListener()
        FloatSessionClient(listener).onTitleChanged(session())
        assertEquals(1, listener.titleChangedCount)
    }

    @Test
    fun `onColorsChanged forwards to the listener exactly once`() {
        val listener = RecordingListener()
        FloatSessionClient(listener).onColorsChanged(session())
        assertEquals(1, listener.colorsChangedCount)
    }

    @Test
    fun `onSessionFinished forwards to the listener exactly once`() {
        val listener = RecordingListener()
        FloatSessionClient(listener).onSessionFinished(session())
        assertEquals(1, listener.sessionFinishedCount)
    }
}
