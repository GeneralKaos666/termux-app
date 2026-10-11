package com.termux.app.ui

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.app.ui.sessions.SessionsBar
import com.termux.app.ui.sessions.SessionsState

/**
 * Callbacks from the Compose shell back to the hosting Java activity, so the Compose tree never has
 * to hold Android session-service references.
 */
interface ShellHostCallbacks {
    /** Switch the terminal to the session with the given [id]. */
    fun onSessionSelected(id: Int)

    /** Open the rename prompt for the session with the given [id]. */
    fun onSessionRename(id: Int)

    /** Close the session with the given [id], confirming first if it is still running. */
    fun onSessionClose(id: Int)

    /** Start a new session. */
    fun onNewSession()
}

/**
 * Installs the Compose shell host into [composeView], rendering the sessions bar above the existing
 * [terminalView] (a [com.termux.view.TerminalView]) which is embedded through [AndroidView].
 *
 * This is called from the Java [com.termux.app.TermuxActivity]; composables and
 * [ComposeView.setContent] cannot be invoked from Java directly, hence this non-composable entry
 * point.
 *
 * The terminal is the single leaf Android view inside the Compose tree. Its focus and IME
 * behaviour must be validated on device (see the shell workstream plan, tasks A2/A3).
 */
fun installTerminalShellHost(
    composeView: ComposeView,
    terminalView: View,
    sessionsState: SessionsState,
    callbacks: ShellHostCallbacks,
) {
    composeView.setContent {
        TerminalShellHost(terminalView, sessionsState, callbacks)
    }
}

@Composable
private fun TerminalShellHost(
    terminalView: View,
    sessionsState: SessionsState,
    callbacks: ShellHostCallbacks,
) {
    val hostView = LocalView.current
    val darkTheme = isSystemInDarkTheme()

    // A minimal Material 3 theme so the shell has coherent colors before A5 replaces this with the
    // Termux palette / dynamic color.
    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
        Column(modifier = Modifier.fillMaxSize()) {
            SessionsBar(
                sessions = sessionsState.sessions,
                onSelect = callbacks::onSessionSelected,
                onRename = callbacks::onSessionRename,
                onClose = callbacks::onSessionClose,
                onNewSession = callbacks::onNewSession,
            )

            AndroidView(
                factory = { terminalView },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }

    SideEffect {
        // The Compose host (AndroidComposeView) is itself focusable(-in-touch-mode), so it keeps
        // focus for the Compose tree. An embedded Android view that calls requestFocus() (the
        // terminal does so on tap) would then never become the focused Android view, and the IME
        // would bind its input connection to the host instead of the terminal, so the soft
        // keyboard would never show. Hand focus to descendants instead.
        hostView.isFocusable = false
        hostView.isFocusableInTouchMode = false
        (hostView as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }
}
