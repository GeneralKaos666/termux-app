package com.termux.app.ui.sessions

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.termux.app.domain.sessions.SessionItem
import com.termux.app.ui.theme.TermuxShellTheme

@Preview(name = "Sessions bar with sessions", showBackground = true, widthDp = 360)
@Composable
private fun SessionsBarPreview() {
    TermuxShellTheme {
        SessionsBar(
            sessions = listOf(
                SessionItem(1, "shell", true, 0, true),
                SessionItem(4, "htop", true, 0, false),
                SessionItem(7, "npm run build", false, 0, false),
                SessionItem(9, "vim", false, 1, false),
            ),
            onSelect = {},
            onRename = {},
            onClose = {},
            onMove = { _, _ -> },
            onNewSession = {},
            onNewFloatSession = {},
            onCollapse = {},
        )
    }
}

@Preview(name = "Sessions bar empty", showBackground = true, widthDp = 360)
@Composable
private fun SessionsBarEmptyPreview() {
    TermuxShellTheme {
        SessionsBar(
            sessions = emptyList(),
            onSelect = {},
            onRename = {},
            onClose = {},
            onMove = { _, _ -> },
            onNewSession = {},
            onNewFloatSession = {},
            onCollapse = {},
        )
    }
}

/** A background (non-active) tab's menu, at the head of the row so move-left is disabled. */
@Preview(name = "Menu on a background tab", showBackground = true, widthDp = 360)
@Composable
private fun SessionActionsMenuPreview() {
    TermuxShellTheme {
        SessionActionsMenu(
            expanded = true,
            onDismiss = {},
            sessionId = 4,
            fromIndex = 0,
            sessionCount = 4,
            onRename = {},
            onClose = {},
            onMove = { _, _ -> },
        )
    }
}
