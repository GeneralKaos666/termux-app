package com.termux.app.ui.sessions

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.termux.R

/**
 * The per-tab menu: rename, close, and move one position left or right.
 *
 * The move items are enabled rather than hidden at the ends of the row, so the menu looks the same
 * on every tab and the disabled direction states why it cannot go further.
 */
@Composable
internal fun SessionActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    sessionId: Int,
    fromIndex: Int,
    sessionCount: Int,
    onRename: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_rename_session)) },
            onClick = {
                onDismiss()
                onRename(sessionId)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_close_session)) },
            onClick = {
                onDismiss()
                onClose(sessionId)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_move_session_left)) },
            enabled = fromIndex > 0,
            onClick = {
                onDismiss()
                onMove(sessionId, fromIndex - 1)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_move_session_right)) },
            enabled = fromIndex in 0 until sessionCount - 1,
            onClick = {
                onDismiss()
                onMove(sessionId, fromIndex + 1)
            },
        )
    }
}
