package com.termux.app.ui

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Installs the Compose shell host into [composeView], rendering the existing [terminalView]
 * (a [com.termux.view.TerminalView]) through [AndroidView].
 *
 * This is called from the Java [com.termux.app.TermuxActivity]; composables and
 * [ComposeView.setContent] cannot be invoked from Java directly, hence this non-composable entry
 * point.
 *
 * The terminal is the single leaf Android view inside the Compose tree. Its focus and IME
 * behaviour must be validated on device (see the shell workstream plan, task A2).
 */
fun installTerminalShellHost(composeView: ComposeView, terminalView: View) {
    composeView.setContent {
        TerminalShellHost(terminalView)
    }
}

@Composable
private fun TerminalShellHost(terminalView: View) {
    val hostView = LocalView.current

    AndroidView(
        factory = { terminalView },
        modifier = Modifier.fillMaxSize()
    )

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
