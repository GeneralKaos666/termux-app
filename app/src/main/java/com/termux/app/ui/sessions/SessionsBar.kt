package com.termux.app.ui.sessions

import com.termux.app.domain.sessions.SessionItem
import com.termux.app.domain.sessions.StarterCommand
import com.termux.app.domain.sessions.TabBounds

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.termux.R
import com.termux.app.ui.theme.ShellEaseOut
import com.termux.app.ui.theme.shellAnimationsEnabled
import com.termux.app.ui.theme.shellSettleSpec
import com.termux.app.ui.theme.shellSpringSpec
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first

/** Upper bound on the one-viewport steps taken to reach a tab whose offset is not measured yet. */
private const val MAX_ACTIVE_TAB_SCROLL_STEPS = 8

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
    modifier: Modifier = Modifier,
    onStarterCommand: (String) -> Unit = {},
    onOpenPalette: () -> Unit = {},
    backgroundAlpha: Float = 1f,
    unseen: Set<Int> = emptySet(),
    starterCommands: List<StarterCommand> = emptyList(),
    bellFlash: Boolean = false,
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
    //
    // Only the overlap is scrolled, so a tab that is already on screen stays where it is:
    // aligning it to the leading edge made every session switch slide the whole strip.
    LaunchedEffect(activeIndex) {
        if (activeIndex < 0) return@LaunchedEffect
        // Nothing can be measured before the row has its items, and the layout info changes on every
        // scroll, so the wait for the first layout happens here rather than in a composition key.
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        repeat(MAX_ACTIVE_TAB_SCROLL_STEPS) {
            val layout = listState.layoutInfo
            val viewportSize = layout.viewportEndOffset - layout.viewportStartOffset
            if (viewportSize <= 0) return@LaunchedEffect
            val tab = layout.visibleItemsInfo.firstOrNull { it.index == activeIndex }
            if (tab == null) {
                // The tab is outside the measured range, so its offset is unknown: move one
                // viewport towards it and measure again instead of aligning it to the edge.
                val lastVisibleIndex = layout.visibleItemsInfo.lastOrNull()?.index
                    ?: return@LaunchedEffect
                val step = if (activeIndex > lastVisibleIndex) viewportSize else -viewportSize
                listState.animateScrollBy(step.toFloat())
                return@repeat
            }
            val delta = minimalScrollDelta(
                tab.offset, tab.size, layout.viewportStartOffset, layout.viewportEndOffset,
            )
            if (delta != 0) listState.animateScrollBy(delta.toFloat())
            return@LaunchedEffect
        }
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
            // A visual bell briefly tints that background instead, so the flash reads as the
            // chrome lighting up rather than as another tab.
            .background(
                if (bellFlash) {
                    MaterialTheme.colorScheme.primary.copy(alpha = BELL_FLASH_ALPHA * backgroundAlpha)
                } else {
                    MaterialTheme.colorScheme.surface.copy(alpha = backgroundAlpha)
                },
            )
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
            SessionsBarEmptyState(
                starterCommands = starterCommands,
                onNewSession = onNewSession,
                onStarterCommand = onStarterCommand,
                startSessionLabel = startSessionLabel,
            )
        } else {
            val fadeColor = MaterialTheme.colorScheme.surface.copy(alpha = backgroundAlpha)
            val fadeWidth = 16.dp
            Box(
                modifier = Modifier
                    .weight(1f)
                    .onGloballyPositioned { coordinates ->
                        val left = coordinates.boundsInRoot().left.roundToInt()
                        if (left != rowLeftPx) rowLeftPx = left
                    },
            ) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawWithContent {
                            drawContent()
                            // Read the scroll state here, in the draw phase: a scroll then redraws
                            // without recomposing the bar.
                            val atStart = listState.firstVisibleItemIndex == 0 &&
                                listState.firstVisibleItemScrollOffset == 0
                            val atEnd = !listState.canScrollForward
                            val scrollFraction = if (atStart) 0f else 1f
                            val edge = fadeWidth.toPx()
                            val leadingAlpha = edgeFadeAlpha(scrollFraction, atStart, atEnd, true)
                            val trailingAlpha = edgeFadeAlpha(scrollFraction, atStart, atEnd, false)
                            if (leadingAlpha > 0f) {
                                drawRect(
                                    brush = Brush.horizontalGradient(
                                        listOf(fadeColor.copy(alpha = fadeColor.alpha * leadingAlpha), Color.Transparent),
                                        startX = 0f,
                                        endX = edge,
                                    ),
                                    size = Size(edge, size.height),
                                )
                            }
                            if (trailingAlpha > 0f) {
                                drawRect(
                                    brush = Brush.horizontalGradient(
                                        listOf(Color.Transparent, fadeColor.copy(alpha = fadeColor.alpha * trailingAlpha)),
                                        startX = size.width - edge,
                                        endX = size.width,
                                    ),
                                    topLeft = Offset(size.width - edge, 0f),
                                    size = Size(edge, size.height),
                                )
                            }
                        },
                    state = listState,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(sessions, key = { it.id }) { item ->
                        SessionTab(
                            item = item,
                            unseen = item.id in unseen,
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
                                    fadeInSpec = tween(durationMillis = 150, easing = ShellEaseOut),
                                    placementSpec = shellSpringSpec(),
                                    fadeOutSpec = tween(durationMillis = 120, easing = ShellEaseOut),
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
                    // stays glued to its tab instead of trailing behind the fling. The settle spring
                    // is for the slide between tabs, which only happens once scrolling has settled;
                    // critically damped, because a pill that overshoots its tab edge reads as a miss.
                    val offsetSpec = if (animationsEnabled && !listState.isScrollInProgress) {
                        shellSettleSpec<IntOffset>()
                    } else {
                        snap<IntOffset>()
                    }
                    val offsetX by animateIntOffsetAsState(
                        targetValue = targetOffset,
                        animationSpec = offsetSpec,
                        label = "activeTabIndicatorOffset",
                    )
                    // Same spring as the offset, so the pill's left edge and its width move on one
                    // beat and it never briefly straddles two tabs. Critically damped, so it cannot
                    // grow wider than the tab.
                    val indicatorWidth by animateDpAsState(
                        targetValue = with(density) { targetWidth.toDp() },
                        animationSpec = if (animationsEnabled) shellSettleSpec() else snap(),
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
                        .combinedClickable(
                            onClick = { newSessionMenuExpanded = true },
                            onLongClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                onOpenPalette()
                            },
                        )
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
    modifier: Modifier = Modifier,
    backgroundAlpha: Float = 1f,
    bellFlash: Boolean = false,
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
                if (bellFlash) {
                    MaterialTheme.colorScheme.primary.copy(alpha = BELL_FLASH_ALPHA * backgroundAlpha)
                } else {
                    MaterialTheme.colorScheme.surface.copy(alpha = backgroundAlpha)
                },
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

/** How strongly the visual bell tints the chrome. */
private const val BELL_FLASH_ALPHA = 0.45f

/** Horizontal inset of the active-tab pill, so it stays under the flat part of the tab capsule. */
private val TabIndicatorInset = 10.dp
