package com.termux.app.ui.sessions

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.termux.R
import com.termux.app.ui.theme.TermuxShellTheme
import kotlin.math.roundToInt

/**
 * Top strip of terminal sessions.
 *
 * Renders one tab per [SessionItem]: the title plus a status indicator (running / exited
 * successfully / exited with an error). Tapping a tab switches to that session; long-pressing one
 * and dragging reorders it. Every tab carries a menu button for rename / close / move, so those
 * actions reach background sessions too. A trailing "+" starts a new session, and a trailing
 * chevron collapses the whole bar (a floating [SessionsRestoreHandle] brings it back). With many
 * sessions the row scrolls, and the active tab is always scrolled into view.
 *
 * This replaces the old navigation drawer session list.
 */
@Composable
fun SessionsBar(
    sessions: List<SessionItem>,
    onSelect: (Int) -> Unit,
    onRename: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onNewSession: () -> Unit,
    onCollapse: () -> Unit,
    backgroundAlpha: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val listState = rememberLazyListState()
    val activeIndex = sessions.indexOfFirst { it.active }

    val startSessionLabel = stringResource(R.string.action_start_session)
    val newSessionLabel = stringResource(R.string.action_new_session)
    val collapseLabel = stringResource(R.string.action_hide_sessions_bar)

    // Horizontal extent of each tab in the root coordinate space, keyed by session id, recorded by
    // the tab itself once it has been laid out. Only read from gesture callbacks, never during
    // composition, so updating it does not invalidate any composable.
    val tabBounds = remember { mutableStateMapOf<Int, TabBounds>() }

    // Bounds in session order, for the drop-index maths. Tabs that have not been laid out yet are
    // skipped rather than guessed at, and the index is taken from the session order here because
    // that is the order a move is expressed in.
    val snapshotTabBounds: () -> List<TabBounds> = {
        sessions.mapIndexedNotNull { index, session ->
            tabBounds[session.id]?.copy(index = index)
        }
    }

    // Keep the active session visible when it changes. Deliberately not keyed on the whole list:
    // session titles update constantly while commands run, and re-running this on every refresh
    // would keep yanking the row back and stop the user browsing the other tabs.
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) listState.animateScrollToItem(activeIndex)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            // Only the bar's own background fades; tab labels, chips and indicators stay opaque.
            .background(MaterialTheme.colorScheme.surface.copy(alpha = backgroundAlpha))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (sessions.isEmpty()) {
            // A clickable Text rather than a TextButton: the button's 48dp minimum interactive
            // size would keep the empty bar taller than the populated one.
            Text(
                text = stringResource(R.string.action_start_session),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onNewSession)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .semantics { contentDescription = startSessionLabel },
            )
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
                        allSessions = sessions,
                        onSelect = onSelect,
                        onRename = onRename,
                        onClose = onClose,
                        onMove = onMove,
                        onTabBoundsChanged = { id, bounds -> tabBounds[id] = bounds },
                        snapshotTabBounds = snapshotTabBounds,
                    )
                }
            }

            // Plain clickable icons rather than IconButtons: Material3's IconButton enforces a
            // 48dp minimum interactive size, which alone would keep the bar that tall.
            Icon(
                imageVector = SessionIcons.Plus,
                contentDescription = newSessionLabel,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(onClick = onNewSession)
                    .padding(2.dp)
                    .size(24.dp),
            )
        }

        Icon(
            imageVector = SessionIcons.ChevronUp,
            contentDescription = collapseLabel,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clickable {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onCollapse()
                }
                .padding(2.dp)
                .size(24.dp),
        )
    }
}

/**
 * Floating handle shown while the sessions bar is collapsed: a circular button tucked into the
 * top corner of the shell that expands the bar again. Its surface background (faded with the
 * same transparency as the chrome bars) keeps it readable over the terminal.
 */
@Composable
internal fun SessionsRestoreHandle(
    onExpand: () -> Unit,
    backgroundAlpha: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val expandLabel = stringResource(R.string.action_show_sessions_bar)
    IconButton(
        onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onExpand()
        },
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = backgroundAlpha),
                CircleShape,
            )
            .semantics { contentDescription = expandLabel },
    ) {
        Icon(
            imageVector = SessionIcons.ChevronDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SessionTab(
    item: SessionItem,
    allSessions: List<SessionItem>,
    onSelect: (Int) -> Unit,
    onRename: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onTabBoundsChanged: (Int, TabBounds) -> Unit,
    snapshotTabBounds: () -> List<TabBounds>,
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

    val menuLabel = stringResource(R.string.action_session_menu)

    val fromIndex = remember(allSessions, item.id) {
        allSessions.indexOfFirst { it.id == item.id }
    }

    // The gesture handler is created once per session and outlives a composition, so read the
    // values it needs through rememberUpdatedState rather than capturing a stale snapshot of them.
    val currentFromIndex by rememberUpdatedState(fromIndex)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnTabBoundsChanged by rememberUpdatedState(onTabBoundsChanged)
    val currentSnapshotTabBounds by rememberUpdatedState(snapshotTabBounds)

    var dragOffsetX by remember(item.id) { mutableStateOf(0f) }
    var isDragging by remember(item.id) { mutableStateOf(false) }
    // Tab extent captured when the drag starts. The drop maths measures the dragged centre against
    // the layout as it was before the drag, so the tab changing place mid-drag cannot skew it.
    var boundsAtDragStart by remember(item.id) { mutableStateOf(emptyList<TabBounds>()) }

    Box(
        modifier = Modifier
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                translationX = if (isDragging) dragOffsetX else 0f
            }
            .clickable { onSelect(item.id) }
            .semantics {
                contentDescription = item.title
                selected = item.active
                stateDescription = stateLabel
            },
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(containerColor)
                // Slimmer than the 48dp Material default: a plain clickable icon (see the menu
                // button below) rather than an IconButton keeps the whole bar near half height.
                .heightIn(min = 24.dp)
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInRoot()
                    // The index is fixed up by SessionsBar from the session order; only the extent
                    // is meaningful here.
                    currentOnTabBoundsChanged(
                        item.id,
                        TabBounds(
                            index = currentFromIndex,
                            startPx = bounds.left.roundToInt(),
                            endPx = bounds.right.roundToInt(),
                        ),
                    )
                }
                .pointerInput(item.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            boundsAtDragStart = currentSnapshotTabBounds()
                            isDragging = true
                            dragOffsetX = 0f
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragOffsetX += dragAmount.x
                        },
                        onDragEnd = {
                            val draggedBy = dragOffsetX
                            isDragging = false
                            dragOffsetX = 0f

                            val from = currentFromIndex
                            val own = boundsAtDragStart.firstOrNull { it.index == from }
                            val target = if (own == null) {
                                from
                            } else {
                                dropIndex(boundsAtDragStart, (own.startPx + own.endPx) / 2f + draggedBy)
                            }
                            if (target != from) currentOnMove(item.id, target)
                        },
                        onDragCancel = {
                            isDragging = false
                            dragOffsetX = 0f
                        },
                    )
                }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SessionStateDot(state)
            Spacer(Modifier.width(6.dp))
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(4.dp))
            // A plain clickable icon rather than an IconButton: Material3's IconButton enforces
            // a 48dp minimum interactive size, which alone would keep the bar that tall.
            Icon(
                imageVector = SessionIcons.MoreVert,
                contentDescription = menuLabel,
                tint = contentColor,
                modifier = Modifier
                    .clickable { menuExpanded = true }
                    .padding(2.dp)
                    .size(20.dp),
            )
        }

        SessionActionsMenu(
            expanded = menuExpanded,
            onDismiss = { menuExpanded = false },
            sessionId = item.id,
            fromIndex = fromIndex,
            sessionCount = allSessions.size,
            onRename = onRename,
            onClose = onClose,
            onMove = onMove,
        )
    }
}

/**
 * The per-tab menu: rename, close, and move one position left or right.
 *
 * The move items are enabled rather than hidden at the ends of the row, so the menu looks the same
 * on every tab and the disabled direction states why it cannot go further.
 */
@Composable
private fun SessionActionsMenu(
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

/**
 * Status indicator for a session state. Each state is a distinct shape as well as a distinct
 * colour, so state is not conveyed by colour alone: running is a filled circle, exited-success is
 * a hollow ring, exited-with-error is a square (WCAG 1.4.1).
 */
@Composable
private fun SessionStateDot(state: SessionState) {
    val color = sessionStateColor(state)
    when (state) {
        SessionState.RUNNING -> Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        SessionState.EXITED_SUCCESS -> Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .border(width = 2.dp, color = color, shape = CircleShape)
        )
        SessionState.EXITED_ERROR -> Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
    }
}

/** Status-dot color for a session state. */
private fun sessionStateColor(state: SessionState): Color = when (state) {
    SessionState.RUNNING -> Color(0xFF43A047)
    SessionState.EXITED_SUCCESS -> Color(0xFF9E9E9E)
    SessionState.EXITED_ERROR -> Color(0xFFE53935)
}

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
