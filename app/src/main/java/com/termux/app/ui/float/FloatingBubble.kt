package com.termux.app.ui.float

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.termux.app.ui.materialIcon
import com.termux.app.ui.theme.TermuxShellTheme
import kotlin.math.roundToInt

/**
 * The collapsed floating-terminal bubble: a circular drag target rendered in its own `ComposeView`
 * overlay.
 *
 * Drag reports the per-event pixel delta through [onDrag] as an [IntOffset], which the controller
 * adds to the bubble's `WindowManager.LayoutParams.x/y`. Tap (a down/up with no drag) fires [onTap]
 * to expand the bubble into the terminal window. The composable only detects gestures and reports
 * them; it never touches `WindowManager` itself.
 */
@Composable
fun FloatingBubble(
    onTap: () -> Unit,
    onDrag: (IntOffset) -> Unit,
) {
    TermuxShellTheme {
        Box(
            modifier = Modifier
                .size(BubbleDiameter)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onTap() })
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDrag(IntOffset(dragAmount.x.roundToInt(), dragAmount.y.roundToInt()))
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = BubbleIcons.Terminal,
                // TODO(localize): swap for a string resource in Task 7.
                contentDescription = "Open terminal",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** Vector icons for the bubble, drawn from 24x24 Material icon path data (Apache 2.0). */
private object BubbleIcons {
    val Terminal: ImageVector = materialIcon(
        "Terminal",
        "M20 4H4c-1.11 0-2 .9-2 2v12c0 1.1.89 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.89-2-2-2zm0 14H4V8h16v10zm-2-1h-6v-2h6v2zM7.5 17l-1.41-1.41L8.67 13l-2.59-2.59L7.5 9l4 4-4 4z",
    )
}

private val BubbleDiameter = 56.dp
