package com.termux.app.ui.sessions

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.termux.R
import com.termux.app.ui.theme.TermuxShellTheme
import com.termux.app.ui.theme.shellAnimationsEnabled
import com.termux.app.ui.theme.shellSpringSpec
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collect

/**
 * Top strip of terminal sessions.
 *
 * Renders one tab per [SessionItem]: the title plus a status indicator (running / exited
 * successfully / exited with an error). Tapping a tab switches to that session; long-pressing one
 * and dragging reorders it. Every tab carries a menu button for rename / close / move, so those
 * actions reach background sessions too. A leading chevron collapses the whole bar (a floating
 * [SessionsRestoreHandle] brings it back), a trailing "+" starts a new session. With many
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
    onNewFloatSession: () -> Unit,
    onCollapse: () -> Unit,
    backgroundAlpha: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val listState = rememberLazyListState()
    val activeIndex = sessions.indexOfFirst { it.active }

    val startSessionLabel = stringResource(R.string.action_start_session)
    val newSessionLabel = stringResource(R.string.action_new_session)
    val newTabSessionLabel = stringResource(R.string.action_new_tab_session)
    val newFloatSessionLabel = stringResource(R.string.action_new_float_session)
    val collapseLabel = stringResource(R.string.action_hide_sessions_bar)
    var newSessionMenuExpanded by remember { mutableStateOf(false) }

    // Horizontal extent of each tab in the root coordinate space, keyed by session id, recorded by
    // the tab itself once it has been laid out. Never read during composition — only from gesture
    // callbacks and from the indicator's snapshotFlow — so updating it does not invalidate any
    // composable and cannot schedule another layout on every write.
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

    val animationsEnabled = shellAnimationsEnabled()

    // Which tab is being dragged, so its own graphicsLayer translation is the only thing that moves
    // it: an item placement animation on top of the drag would fight it.
    var draggingSessionId by remember { mutableStateOf<Int?>(null) }

    // The active tab's extent, kept in its own state instead of reading tabBounds during
    // composition: that map is written from layout, so reading it there would schedule another
    // layout on every write. snapshotFlow also keeps the last known bounds until the newly active
    // tab has been measured, so switching tabs glides instead of blinking out and back.
    var activeTabBounds by remember { mutableStateOf<TabBounds?>(null) }
    // Left edge of the tab row in root coordinates, so the indicator's root-relative bounds can be
    // turned into an offset inside the row. Guarded on write, so layout cannot invalidate in a loop.
    var rowLeftPx by remember { mutableIntStateOf(0) }
    val activeSessionId = sessions.firstOrNull { it.active }?.id
    LaunchedEffect(activeSessionId) {
        if (activeSessionId == null) {
            activeTabBounds = null
        } else {
            snapshotFlow { tabBounds[activeSessionId] }.collect { bounds ->
                if (bounds != null) activeTabBounds = bounds
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            // Only the bar's own background fades; tab labels, chips and indicators stay opaque.
            .background(MaterialTheme.colorScheme.surface.copy(alpha = backgroundAlpha))
            // The extra 2dp of vertical padding grows the bar itself, not the chips, which keep
            // their own 32dp minimum and stay vertically centred in the taller strip.
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The collapse chevron leads the row, before the tabs, the "+" and any empty-bar prompt, so
        // it never sits at the trailing edge where the scrolled tabs used to run under it.
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
            Box(
                modifier = Modifier
                    .weight(1f)
                    .onGloballyPositioned { coordinates ->
                        val left = coordinates.boundsInRoot().left.roundToInt()
                        if (left != rowLeftPx) rowLeftPx = left
                    },
            ) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
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
                            animationsEnabled = animationsEnabled,
                            onDragStateChanged = { dragging ->
                                draggingSessionId = if (dragging) {
                                    item.id
                                } else {
                                    // Only clear our own drag: another tab's end/cancel must not
                                    // release the exclusion of a tab that is still being dragged.
                                    draggingSessionId.takeIf { it != item.id }
                                }
                            },
                            modifier = if (animationsEnabled && item.id != draggingSessionId) {
                                // A tween, not the shell spring: a fading tab must not carry a
                                // clamped alpha overshoot (the spring goes above 1). Placement keeps
                                // the spring so a reorder still reads as one motion language.
                                Modifier.animateItem(
                                    fadeInSpec = tween(durationMillis = 150),
                                    placementSpec = shellSpringSpec(),
                                    fadeOutSpec = tween(durationMillis = 120),
                                )
                            } else {
                                Modifier
                            },
                        )
                    }
                }

                // A sibling of the row, not an item, so its slide never relayouts the tabs. Its
                // position comes from the active tab's measured extent, animated in root pixels.
                val bounds = activeTabBounds
                if (bounds != null) {
                    val density = LocalDensity.current
                    // Inset by the tab's corner radius so the pill stays under the flat part of the
                    // capsule instead of poking past its rounded ends.
                    val insetPx = with(density) { TabIndicatorInset.toPx() }.roundToInt()
                    val targetOffset = IntOffset(bounds.startPx - rowLeftPx + insetPx, 0)
                    val targetWidth = (bounds.endPx - bounds.startPx - 2 * insetPx).coerceAtLeast(0)
                    // While the row scrolls, the measured bounds move every frame: snap so the pill
                    // stays glued to its tab instead of trailing behind the fling. The spring is
                    // for the slide between tabs, which only happens once scrolling has settled.
                    val offsetSpec = if (animationsEnabled && !listState.isScrollInProgress) {
                        shellSpringSpec<IntOffset>()
                    } else {
                        snap<IntOffset>()
                    }
                    val offsetX by animateIntOffsetAsState(
                        targetValue = targetOffset,
                        animationSpec = offsetSpec,
                        label = "activeTabIndicatorOffset",
                    )
                    // A tween, not the spring: an overshooting width would briefly make the pill
                    // wider than its tab.
                    val indicatorWidth by animateDpAsState(
                        targetValue = with(density) { targetWidth.toDp() },
                        animationSpec = if (animationsEnabled) tween(durationMillis = 200) else snap(),
                        label = "activeTabIndicatorWidth",
                    )
                    // Clipped to the row so the pill never draws over the chevron or the "+" when
                    // the active tab is scrolled out of view. Only this layer is clipped, not the
                    // row: a dragged tab must still be drawn outside it while it is being moved.
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clipToBounds(),
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .offset { offsetX }
                                .width(indicatorWidth)
                                .height(2.dp)
                                .background(
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                    RoundedCornerShape(1.dp),
                                ),
                        )
                    }
                }
            }

            // Plain clickable icons rather than IconButtons: Material3's IconButton enforces a
            // 48dp minimum interactive size, which alone would keep the bar that tall. Tapping the
            // "+" opens a menu offering a new tab session or a new floating session.
            Box {
                Icon(
                    imageVector = SessionIcons.Plus,
                    contentDescription = newSessionLabel,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable { newSessionMenuExpanded = true }
                        .padding(2.dp)
                        .size(24.dp),
                )

                DropdownMenu(
                    expanded = newSessionMenuExpanded,
                    onDismissRequest = { newSessionMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(newTabSessionLabel) },
                        onClick = {
                            newSessionMenuExpanded = false
                            onNewSession()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(newFloatSessionLabel) },
                        onClick = {
                            newSessionMenuExpanded = false
                            onNewFloatSession()
                        },
                    )
                }
            }
        }
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
    animationsEnabled: Boolean,
    onDragStateChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
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

    // Colours fade between tabs instead of snapping. A tween, not the shell spring: a bouncy spec
    // would overshoot the blend past the target colour and flash brighter than either end state.
    val colorSpec = if (animationsEnabled) tween<Color>(durationMillis = 200) else snap<Color>()
    val containerColor by animateColorAsState(
        targetValue = if (item.active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        animationSpec = colorSpec,
        label = "sessionTabContainerColor",
    )
    val contentColor by animateColorAsState(
        targetValue = if (item.active) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = colorSpec,
        label = "sessionTabContentColor",
    )

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
    // Residual drag translation after release. The tab is placed in its new slot the moment the
    // order changes, so without this it would snap back to its pre-drag slot before gliding out;
    // instead the finger's offset decays to 0 over the same spring as the placement animation, so
    // the drop continues from where the tab was let go.
    var releaseOffsetX by remember(item.id) { mutableStateOf(0f) }
    var releaseTick by remember(item.id) { mutableIntStateOf(0) }
    // Tab extent captured when the drag starts. The drop maths measures the dragged centre against
    // the layout as it was before the drag, so the tab changing place mid-drag cannot skew it.
    var boundsAtDragStart by remember(item.id) { mutableStateOf(emptyList<TabBounds>()) }

    LaunchedEffect(releaseTick) {
        if (releaseOffsetX == 0f) return@LaunchedEffect
        if (!animationsEnabled) {
            releaseOffsetX = 0f
            return@LaunchedEffect
        }
        animate(releaseOffsetX, 0f, animationSpec = shellSpringSpec()) { value, _ ->
            releaseOffsetX = value
        }
    }

    Box(
        modifier = modifier
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                translationX = if (isDragging) dragOffsetX else releaseOffsetX
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
                // Compact, but still a comfortable tap target: 32dp rather than Material's 48dp
                // minimum. A plain clickable icon (see the menu button below) instead of an
                // IconButton is what keeps the bar from being forced back up to 48dp.
                .heightIn(min = 32.dp)
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
                            onDragStateChanged(true)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragOffsetX += dragAmount.x
                        },
                        onDragEnd = {
                            val draggedBy = dragOffsetX
                            isDragging = false
                            dragOffsetX = 0f
                            releaseOffsetX = draggedBy
                            releaseTick++
                            onDragStateChanged(false)

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
                            val draggedBy = dragOffsetX
                            isDragging = false
                            dragOffsetX = 0f
                            releaseOffsetX = draggedBy
                            releaseTick++
                            onDragStateChanged(false)
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

/** Horizontal inset of the active-tab pill, so it stays under the flat part of the tab capsule. */
private val TabIndicatorInset = 10.dp
