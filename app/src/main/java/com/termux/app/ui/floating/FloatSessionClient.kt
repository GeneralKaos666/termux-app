package com.termux.app.ui.floating

import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase
import com.termux.terminal.TerminalSession

/**
 * Routes terminal-session client callbacks to an injectable [Listener] so the in-app floating
 * terminal window can react to text, title, colour and exit events without holding a reference to
 * the session itself. The session argument is unused by the forwarding logic.
 */
class FloatSessionClient(
    private val listener: Listener,
) : TermuxTerminalSessionClientBase() {

    /** Callbacks forwarded by [FloatSessionClient]. */
    interface Listener {
        fun onTextChanged()
        fun onTitleChanged()
        fun onColorsChanged()
        fun onSessionFinished()
    }

    override fun onTextChanged(changedSession: TerminalSession) = listener.onTextChanged()

    override fun onTitleChanged(updatedSession: TerminalSession) = listener.onTitleChanged()

    override fun onColorsChanged(changedSession: TerminalSession) = listener.onColorsChanged()

    override fun onSessionFinished(finishedSession: TerminalSession) = listener.onSessionFinished()
}
