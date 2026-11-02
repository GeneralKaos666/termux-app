package com.termux.app.ui.floating

import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.app.ui.materialIcon
import com.termux.app.ui.theme.TermuxShellTheme
import kotlin.math.roundToInt

/**
 * The expanded floating terminal window: a header (drag handle + collapse/keyboard/close icons)
 * over the embedded [terminalView] (a `com.termux.view.TerminalView`), with a resize corner at the
 * bottom-end.
 *
 * Gestures are detected and reported only — the controller applies them to `WindowManager.LayoutParams`:
 * a header drag reports the per-event pixel delta through [onDrag] (added to `x/y`), and the resize
 * corner reports the per-event size delta through [onResize] (added to `width/height`, then clamped
 * via [FloatWindowGeometry]). [onCollapse], [onClose] and [onToggleKeyboard] fire on their header icons.
 *
 * The embedded terminal is the single leaf Android view, so the Compose host is made non-focusable
 * (the same [SideEffect] as [com.termux.app.ui.ShellHost]) to keep the IME input connection bound to
 * the terminal rather than the host.
 */
@Composable
fun FloatingWindow(
    terminalView: View,
    onDrag: (IntOffset) -> Unit,
    onResize: (IntSize) -> Unit,
    onCollapse: () -> Unit,
    onClose: () -> Unit,
    onToggleKeyboard: () -> Unit,
) {
    val hostView = LocalView.current

    TermuxShellTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(WindowShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, WindowShape),
        ) {
            FloatingWindowHeader(
                onDrag = onDrag,
                onCollapse = onCollapse,
                onToggleKeyboard = onToggleKeyboard,
                onClose = onClose,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                AndroidView(factory = { terminalView }, modifier = Modifier.fillMaxSize())

                ResizeHandle(
                    onResize = onResize,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
        }
    }

    SideEffect {
        // Hand focus to descendants (see ShellHost): the AndroidComposeView host would otherwise
        // keep focus and steal the terminal's IME input connection, so the soft keyboard would
        // never bind to the embedded terminal.
        hostView.isFocusable = false
        hostView.isFocusableInTouchMode = false
        (hostView as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }
}

/** The window header: a drag handle and the collapse/keyboard/close actions. */
@Composable
private fun FloatingWindowHeader(
    onDrag: (IntOffset) -> Unit,
    onCollapse: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(HeaderHeight)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(IntOffset(dragAmount.x.roundToInt(), dragAmount.y.roundToInt()))
                }
            }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = WindowIcons.DragHandle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.weight(1f))
        HeaderButton(WindowIcons.Minimize, contentDescription = "Collapse", onClick = onCollapse)
        HeaderButton(WindowIcons.Keyboard, contentDescription = "Keyboard", onClick = onToggleKeyboard)
        HeaderButton(WindowIcons.Close, contentDescription = "Close", onClick = onClose)
    }
}

/** A compact header action button: a plain clickable icon rather than a Material `IconButton`. */
@Composable
private fun HeaderButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    val view = LocalView.current
    Icon(
        imageVector = icon,
        // TODO(localize): swap for a string resource in Task 7.
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clickable {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
            .padding(6.dp)
            .size(20.dp),
    )
}

/**
 * The bottom-end resize corner: a diagonal grip whose drag reports the per-event size delta through
 * [onResize].
 */
@Composable
private fun ResizeHandle(
    onResize: (IntSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gripColor = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .size(ResizeHandleSize)
            // TODO(localize): swap for a string resource in Task 7.
            .semantics { contentDescription = "Resize" }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onResize(IntSize(dragAmount.x.roundToInt(), dragAmount.y.roundToInt()))
                }
            }
            .drawBehind {
                val stroke = ResizeStrokeWidth.toPx()
                for (i in 0 until ResizeLineCount) {
                    val inset = (ResizeLineInset + ResizeLineStep * i).toPx()
                    drawLine(
                        color = gripColor,
                        start = Offset(size.width - inset, size.height),
                        end = Offset(size.width, size.height - inset),
                        strokeWidth = stroke,
                    )
                }
            },
    )
}

/** Vector icons for the window header, drawn from 24x24 Material icon path data (Apache 2.0). */
private object WindowIcons {
    val DragHandle: ImageVector = materialIcon(
        "DragHandle",
        "M20 9H4v2h16V9zM4 15h16v-2H4v2z",
    )

    val Minimize: ImageVector = materialIcon(
        "Minimize",
        "M6 19h12v2H6z",
    )

    val Keyboard: ImageVector = materialIcon(
        "Keyboard",
        "M20 5H4c-1.1 0-1.99.9-1.99 2L2 17c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm-9 3h2v2h-2V8zm0 3h2v2h-2v-2zM8 8h2v2H8V8zm0 3h2v2H8v-2zm-1 2H5v-2h2v2zm0-3H5V8h2v2zm9 7H8v-2h8v2zm0-4h-2v-2h2v2zm0-3h-2V8h2v2zm3 3h-2v-2h2v2zm0-3h-2V8h2v2z",
    )

    val Close: ImageVector = materialIcon(
        "Close",
        "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z",
    )
}

private val WindowShape = RoundedCornerShape(12.dp)
private val HeaderHeight = 36.dp
private val ResizeHandleSize = 28.dp
private val ResizeStrokeWidth = 1.5.dp
private val ResizeLineInset = 5.dp
private val ResizeLineStep = 6.dp
private const val ResizeLineCount = 3
