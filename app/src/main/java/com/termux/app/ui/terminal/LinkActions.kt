package com.termux.app.ui.terminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.termux.R
import com.termux.app.domain.terminal.LinkAction

/**
 * The bottom sheet shown when a link is tapped and the user asked to confirm first: the URL, then
 * one row per action that applies to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkActionSheet(
    state: LinkActionState,
    onAction: (LinkAction, String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!state.visible) return

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Text(
                text = state.url,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            state.actions.forEach { action ->
                Text(
                    text = stringResource(action.labelRes()),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAction(action, state.url) }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                )
            }
        }
    }
}

private fun LinkAction.labelRes(): Int = when (this) {
    LinkAction.OPEN -> R.string.link_action_open
    LinkAction.COPY -> R.string.link_action_copy
    LinkAction.SHARE -> R.string.link_action_share
}
