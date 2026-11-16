package com.termux.app.ui.splash

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/**
 * Types [text] out one character at a time over [durationMs], or shows all of it at once when
 * animations are disabled. Shared by the intro splash and the first-run boot panel.
 */
@Composable
fun TypewriterText(
    text: String,
    animationsEnabled: Boolean,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    durationMs: Int = 420,
) {
    var typed by remember(text) { mutableIntStateOf(if (animationsEnabled) 0 else text.length) }

    LaunchedEffect(text, animationsEnabled) {
        if (!animationsEnabled) {
            typed = text.length
            return@LaunchedEffect
        }
        animate(0f, text.length.toFloat(), animationSpec = tween(durationMs)) { value, _ ->
            typed = value.toInt()
        }
    }

    BasicText(
        text = text.take(typed),
        modifier = modifier,
        style = style.copy(color = color),
    )
}
