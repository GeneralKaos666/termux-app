package com.termux.app.ui.splash

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.termux.app.ui.theme.shellAnimationsEnabled
import kotlinx.coroutines.delay

private const val WORD = "termux"
private const val TYPING_MS = 420

/**
 * The cold-start intro: a terminal prompt with the word `termux` typed out, then the whole overlay
 * fades away. Skipped entirely when animations are disabled, so it never delays a user who asked
 * for no motion.
 *
 * @param onDone Called once the intro has run; the caller clears its visible flag.
 */
@Composable
fun IntroSplash(visible: Boolean, onDone: () -> Unit) {
    val animationsEnabled = shellAnimationsEnabled()
    val duration = splashDurationMs(animationsEnabled)

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(if (animationsEnabled) 180 else 0)),
    ) {
        var typedCount by remember { mutableIntStateOf(if (duration == 0) WORD.length else 0) }

        LaunchedEffect(Unit) {
            if (duration == 0) {
                onDone()
                return@LaunchedEffect
            }
            animate(0f, WORD.length.toFloat(), animationSpec = tween(TYPING_MS)) { value, _ ->
                typedCount = value.toInt()
            }
            delay((duration - TYPING_MS).coerceAtLeast(0).toLong())
            onDone()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(24.dp),
            ) {
                Text(
                    text = "> ",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = WORD.take(typedCount),
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                )
                Cursor(animationsEnabled)
            }
        }
    }
}

/** A block cursor that blinks while the word is typed; solid when animations are off. */
@Composable
private fun Cursor(animationsEnabled: Boolean) {
    val alpha = if (animationsEnabled) {
        val transition = rememberInfiniteTransition(label = "splashCursor")
        val value by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable<Float>(tween(480), RepeatMode.Reverse),
            label = "splashCursorAlpha",
        )
        value
    } else {
        val value by animateFloatAsState(1f, label = "splashCursorStatic")
        value
    }
    Box(
        modifier = Modifier
            .padding(start = 2.dp)
            .width(10.dp)
            .height(20.dp)
            .background(Color.White.copy(alpha = alpha)),
    )
}
