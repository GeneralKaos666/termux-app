package com.termux.app.ui

import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.app.ui.keys.ExtraKeysBar
import com.termux.app.ui.keys.ExtraKeysState
import com.termux.app.ui.keys.QuickAction
import com.termux.app.ui.sessions.SessionsBar
import com.termux.app.ui.sessions.SessionsState
import com.termux.app.ui.theme.TermuxShellTheme
import com.termux.app.ui.theme.shellAnimationsEnabled
import com.termux.shared.termux.extrakeys.ExtraKeysView

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

    /** Wire the given [view] to its client and load the active key set. Called once per creation. */
    fun onExtraKeysViewCreated(view: ExtraKeysView)

    /** Drop the activity's reference to the extra-keys view it is no longer hosting. */
    fun onExtraKeysViewReleased()

    /** Run the extra-keys bar quick action. */
    fun onExtraKeysAction(action: QuickAction)

    /** Write [text] (or a newline when empty) to the current session. */
    fun onExtraKeysTextSend(text: String)

    /** The user pressed Undo on the last snackbar with an undo action. */
    fun onSnackbarUndo()
}

/**
 * Installs the Compose shell host into [composeView], rendering the sessions bar above the existing
 * [terminalView] (a [com.termux.view.TerminalView]) which is embedded through [AndroidView], and the
 * extra-keys bar below it.
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
    extraKeysState: ExtraKeysState,
    snackbarState: SnackbarState,
    savedTextInput: String?,
    callbacks: ShellHostCallbacks,
) {
    composeView.setContent {
        TerminalShellHost(terminalView, sessionsState, extraKeysState, snackbarState, savedTextInput, callbacks)
    }
}

@Composable
private fun TerminalShellHost(
    terminalView: View,
    sessionsState: SessionsState,
    extraKeysState: ExtraKeysState,
    snackbarState: SnackbarState,
    savedTextInput: String?,
    callbacks: ShellHostCallbacks,
) {
    val hostView = LocalView.current
    val snackbarHostState = remember { SnackbarHostState() }
    val animationsEnabled = shellAnimationsEnabled()

    // Dark-first chrome: the terminal and the extra-keys grid are always dark, so the shell follows
    // them rather than the system light/dark setting. Material You (dark variant) on API 31+,
    // Termux palette fallback elsewhere; see the theme module.
    TermuxShellTheme {
        Box(modifier = Modifier.fillMaxSize()) {
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

                AnimatedVisibility(
                    visible = extraKeysState.visible,
                    enter = if (animationsEnabled) expandVertically() + fadeIn() else EnterTransition.None,
                    exit = if (animationsEnabled) shrinkVertically() + fadeOut() else ExitTransition.None,
                ) {
                    ExtraKeysBar(
                        state = extraKeysState,
                        initialText = savedTextInput,
                        animationsEnabled = animationsEnabled,
                        callbacks = callbacks,
                    )
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    LaunchedEffect(snackbarState.sequence) {
        if (snackbarState.sequence <= 0) return@LaunchedEffect

        val message = snackbarState.message ?: return@LaunchedEffect
        val undoLabel = snackbarState.undoLabel
        val result = if (undoLabel != null) {
            snackbarHostState.showSnackbar(message, actionLabel = undoLabel, duration = SnackbarDuration.Short)
        } else {
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
        if (result == SnackbarResult.ActionPerformed) callbacks.onSnackbarUndo()
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
