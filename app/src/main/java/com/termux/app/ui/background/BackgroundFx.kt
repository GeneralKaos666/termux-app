package com.termux.app.ui.background

/**
 * Pure math behind the acrylic terminal background: no `android.*` types, so it is unit-testable on
 * any host. The bitmap work itself (blur, downscale, wallpaper sampling) lives in `TermuxActivity`
 * and `ShellHost` and is verified on device.
 */
object BackgroundFx {

    /**
     * The load-time downscale divisor for the API < 31 blur fallback: the bitmap is scaled down by
     * this factor and drawn back up with filtering, which reads as a soft blur without a
     * `RenderEffect`. Grows with the radius and is clamped to `[1, 8]`, so the divisor is always a
     * valid positive factor and the bitmap never collapses to a single pixel.
     */
    @JvmStatic
    fun blurDownscale(radiusPx: Float): Float = (1f + radiusPx / 8f).coerceIn(1f, 8f)

    /**
     * The live-wallpaper fallback colour: the wallpaper's primary colour when the platform could
     * sample one (API 27+), otherwise the terminal background colour. Replaces the black that a
     * null `WallpaperManager.getDrawable()` used to leave behind.
     */
    @JvmStatic
    fun wallpaperFallbackColor(
        colorsAvailable: Boolean,
        primaryColor: Int,
        terminalBackgroundColor: Int,
    ): Int = if (colorsAvailable) primaryColor else terminalBackgroundColor

    /**
     * The picture opacity as a fraction: the preference percentage clamped to `[10, 100]` and
     * divided by 100, so the picture can be lightened but never fully hidden.
     */
    @JvmStatic
    fun clampOpacity(percent: Int): Float = percent.coerceIn(10, 100) / 100f
}
