package com.termux.app.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

/**
 * The shell's shared spring, used by the chrome transitions and the float enter animation so they
 * read as one motion language. Generic over the animated type because Compose sizes
 * `expandVertically` with [androidx.compose.ui.unit.IntSize] and fades with [Float].
 */
fun <T> shellSpringSpec(): SpringSpec<T> = spring(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMedium,
)

/**
 * Drives the float overlay's enter animation: `0f` on first composition, animated to `1f` with the
 * shell spring, and `1f` immediately when shell animations are disabled. Scale and alpha both read
 * it, so the overlay grows and fades as one instead of popping into place.
 *
 * The value is remembered, so recomposition does not replay it; a new overlay gets a new
 * `ComposeView` and therefore a new animation.
 */
@Composable
fun floatEnterProgress(): Float {
    val animationsEnabled = shellAnimationsEnabled()
    val progress = remember { Animatable(if (animationsEnabled) 0f else 1f) }
    LaunchedEffect(progress) {
        if (animationsEnabled) progress.animateTo(1f, shellSpringSpec())
    }
    return progress.value
}

/**
 * [floatEnterProgress] as a scale and an alpha for a `graphicsLayer`. The scale keeps the spring's
 * overshoot (that is the pop); the alpha is clamped, since a bouncy spring goes above 1 and only the
 * platform's clamping would hide it.
 */
@Composable
fun floatEnterScaleAndAlpha(): Pair<Float, Float> {
    val progress = floatEnterProgress()
    return progress to progress.coerceIn(0f, 1f)
}
