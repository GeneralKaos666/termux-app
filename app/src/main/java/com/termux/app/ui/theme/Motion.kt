package com.termux.app.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/**
 * The shell's shared spring, used by the chrome transitions and the float enter animation so they
 * read as one motion language. Generic over the animated type because Compose sizes
 * `expandVertically` with [androidx.compose.ui.unit.IntSize] and fades with [Float].
 */
fun <T> shellSpringSpec(): SpringSpec<T> = spring(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMedium,
)
