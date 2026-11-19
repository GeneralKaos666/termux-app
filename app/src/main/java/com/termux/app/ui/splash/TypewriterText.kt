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
    durationMs: Int = typingDurationMs(text.length),
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

/** Per-character typing cadence: fast enough not to delay the panel, slow enough to read. */
private const val TYPE_CHAR_MS = 32

/** Floor so a very short string still reads as typed rather than popping in. */
private const val TYPE_MIN_MS = 240

/** Ceiling so a long localized welcome never holds the panel for longer than this. */
private const val TYPE_MAX_MS = 900

/**
 * Type-out duration for [length] characters: a fixed cadence per character instead of one fixed
 * duration for the whole string. A fixed duration makes a long localized welcome blur past and a
 * short one crawl; this keeps the speed the same and caps the total.
 */
internal fun typingDurationMs(length: Int): Int =
    (length * TYPE_CHAR_MS).coerceIn(TYPE_MIN_MS, TYPE_MAX_MS)
