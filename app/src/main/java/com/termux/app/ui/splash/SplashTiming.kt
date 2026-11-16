package com.termux.app.ui.splash

/** How long the intro splash holds before it fades out, in milliseconds. */
private const val SPLASH_DURATION_MS = 560

/**
 * Duration of the intro splash: [SPLASH_DURATION_MS] when the system animator scale allows motion,
 * and 0 when the user disabled animations, so the splash is skipped rather than frozen.
 */
fun splashDurationMs(animationsEnabled: Boolean): Int =
    if (animationsEnabled) SPLASH_DURATION_MS else 0
