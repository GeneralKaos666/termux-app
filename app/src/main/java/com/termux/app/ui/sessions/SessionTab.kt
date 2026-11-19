package com.termux.app.ui.sessions

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.termux.R
import com.termux.app.domain.sessions.SessionItem
import com.termux.app.domain.sessions.SessionState
import com.termux.app.domain.sessions.TabBounds
import com.termux.app.domain.sessions.dropIndex
import com.termux.app.domain.sessions.sessionState
import com.termux.app.ui.theme.ShellEaseOut
import com.termux.app.ui.theme.shellSettleSpec
import com.termux.app.ui.theme.shellSpringSpec
import kotlin.math.roundToInt

@Composable
internal fun SessionTab(
    item: SessionItem,
    unseen: Boolean,
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
    val view = LocalView.current

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
    val colorSpec = if (animationsEnabled) tween<Color>(durationMillis = 200, easing = ShellEaseOut) else snap<Color>()
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
    val liftSpec = if (animationsEnabled) shellSpringSpec() else snap<Float>()
    val lift by animateFloatAsState(
        targetValue = if (isDragging) 1.04f else 1f,
        animationSpec = liftSpec,
        label = "sessionTabLift",
    )
    val liftShadowPx = with(LocalDensity.current) { 8.dp.toPx() }
    // Residual drag translation after release. The tab is placed in its new slot the moment the
    // order changes, so without this it would snap back to its pre-drag slot before gliding out;
    // instead the finger's offset decays to 0 over the settle spring, so the drop continues from
    // where the tab was let go without overshooting the slot.
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
        animate(releaseOffsetX, 0f, animationSpec = shellSettleSpec()) { value, _ ->
            releaseOffsetX = value
        }
    }

    Box(
        modifier = modifier
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                translationX = if (isDragging) dragOffsetX else releaseOffsetX
                scaleX = lift
                scaleY = lift
                shadowElevation = if (isDragging) liftShadowPx else 0f
                shape = RoundedCornerShape(16.dp)
                clip = false
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
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
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
            SessionStateDot(state, unseen)
            Spacer(Modifier.width(6.dp))
            Column {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.subtitle != null) {
                    Text(
                        text = item.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
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
