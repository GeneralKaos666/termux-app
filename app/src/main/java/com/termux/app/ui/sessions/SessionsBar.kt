package com.termux.app.ui.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.R

/**
 * Always-visible, adaptive top strip of terminal sessions.
 *
 * Renders one tab per [SessionItem]: the title plus a status dot (running / exited successfully /
 * exited with an error). Tapping a tab switches to that session; long-pressing one opens a menu to
 * rename or close it. A trailing "+" starts a new session. With many sessions the row scrolls, and
 * the active tab is always scrolled into view.
 *
 * This replaces the old navigation drawer session list.
 */
@Composable
fun SessionsBar(
    sessions: List<SessionItem>,
    onSelect: (Int) -> Unit,
    onRename: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val activeIndex = sessions.indexOfFirst { it.active }

    val startSessionLabel = stringResource(R.string.action_start_session)
    val newSessionLabel = stringResource(R.string.action_new_session)

    // Keep the active session visible when it changes. Deliberately not keyed on the whole list:
    // session titles update constantly while commands run, and re-running this on every refresh
    // would keep yanking the row back and stop the user browsing the other tabs.
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) listState.animateScrollToItem(activeIndex)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (sessions.isEmpty()) {
            TextButton(
                onClick = onNewSession,
                modifier = Modifier.semantics { contentDescription = startSessionLabel },
            ) {
                Text(stringResource(R.string.action_start_session))
            }
        } else {
            LazyRow(
                modifier = Modifier.weight(1f),
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(sessions, key = { it.id }) { item ->
                    SessionTab(
                        item = item,
                        onSelect = onSelect,
                        onRename = onRename,
                        onClose = onClose,
                    )
                }
            }

            IconButton(
                onClick = onNewSession,
                modifier = Modifier.semantics { contentDescription = newSessionLabel },
            ) {
                Text(
                    text = "+",
                    fontSize = 22.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionTab(
    item: SessionItem,
    onSelect: (Int) -> Unit,
    onRename: (Int) -> Unit,
    onClose: (Int) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    val state = sessionState(item.running, item.exitCode)
    val stateLabel = stringResource(
        when (state) {
            SessionState.RUNNING -> R.string.session_state_running
            SessionState.EXITED_SUCCESS -> R.string.session_state_exited
            SessionState.EXITED_ERROR -> R.string.session_state_exited_error
        }
    )

    val containerColor = if (item.active) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        Color.Transparent
    }
    val contentColor = if (item.active) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(containerColor)
                .combinedClickable(
                    onClick = { menuExpanded = true },
                )
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .semantics {
                    contentDescription = item.title
                    selected = item.active
                    stateDescription = stateLabel
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(sessionStateColor(state))
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = item.title,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
            )
        }

        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_select_session)) },
                onClick = {
                    menuExpanded = false
                    onSelect(item.id)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_rename_session)) },
                onClick = {
                    menuExpanded = false
                    onRename(item.id)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_close_session)) },
                onClick = {
                    menuExpanded = false
                    onClose(item.id)
                },
            )
        }
    }
}

/** Status-dot color for a session state. */
private fun sessionStateColor(state: SessionState): Color = when (state) {
    SessionState.RUNNING -> Color(0xFF43A047)
    SessionState.EXITED_SUCCESS -> Color(0xFF9E9E9E)
    SessionState.EXITED_ERROR -> Color(0xFFE53935)
}
