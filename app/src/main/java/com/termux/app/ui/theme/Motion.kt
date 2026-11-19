package com.termux.app.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

/**
 * The shell's spring for things that pop: the float overlay's enter and the session tab's lift and
 * placement. Low bounce (about 3 % overshoot) — a terminal is a tool, not a toy, and a 16 %
 * overshoot on MediumBouncy read as wobble.
 */
fun <T> shellSpringSpec(): SpringSpec<T> = spring(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessMedium,
)

/**
 * The shell's spring for anything that has to land exactly on a target: the active-tab indicator and
 * the residual drag offset after a drop. Critically damped, so it settles without sliding past the
 * slot or the tab edge. Same stiffness as [shellSpringSpec], so both settle on the same beat.
 */
fun <T> shellSettleSpec(): SpringSpec<T> = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium,
)

/**
 * The shell's ease for anything that appears or settles: a strong ease-out, so the change starts
 * fast and the user sees it at once. Compose's default `tween` easing is `FastOutSlowIn`, which
 * starts slow and delays exactly that first moment; `cubic-bezier(0.23, 1, 0.32, 1)` is the
 * standard strong UI ease-out.
 */
val ShellEaseOut: Easing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

/**
 * Drives the float overlay's enter animation: `0f` on first composition, animated to `1f` with the
 * shell spring, and `1f` immediately when shell animations are disabled. Scale and alpha both read
 * it, so the overlay grows and fades as one instead of popping into place.
 *
 * The value is remembered, so recomposition does not replay it; a new overlay gets a new
 * `ComposeView` and therefore a new animation. The effect is keyed on the setting as well as the
 * value, so turning "remove animations" on mid-flight cancels the spring and snaps to the end
 * instead of letting that one pop finish.
 */
@Composable
fun floatEnterProgress(): Float {
    val animationsEnabled = shellAnimationsEnabled()
    val progress = remember { Animatable(if (animationsEnabled) 0f else 1f) }
    LaunchedEffect(animationsEnabled) {
        if (animationsEnabled) progress.animateTo(1f, shellSpringSpec()) else progress.snapTo(1f)
    }
    return progress.value
}

/** The smallest scale the float overlay reaches on its first frame — it grows from 92 %, not a point. */
private const val FloatEnterMinScale = 0.92f

/**
 * [floatEnterProgress] as a scale and an alpha for a `graphicsLayer`. The scale starts at
 * [FloatEnterMinScale] (92 %) and grows to 100 %, so the overlay grows into place instead of
 * appearing from a point; the spring overshoot still carries it just past 100 % (that is the pop).
 * The alpha is clamped, since a bouncy spring goes above 1 and only the platform's clamping would
 * hide it.
 */
@Composable
fun floatEnterScaleAndAlpha(): Pair<Float, Float> {
    val progress = floatEnterProgress()
    val scale = FloatEnterMinScale + (1f - FloatEnterMinScale) * progress
    return scale to progress.coerceIn(0f, 1f)
}
