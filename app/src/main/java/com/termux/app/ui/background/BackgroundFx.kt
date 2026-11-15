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
     * valid positive factor; the caller's own size floor keeps the result at least one pixel.
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

    /** The source rectangle of a centre-crop, in source pixels. */
    data class Crop(val left: Int, val top: Int, val width: Int, val height: Int)

    /**
     * The source rectangle that centre-crops a `srcW x srcH` picture into a `dstW x dstH` area. The
     * crop keeps the source aspect ratio, is centred, and is clamped to at least one pixel and at
     * most the source, so a degenerate picture (the 1x1 live-wallpaper fallback) is still drawn,
     * stretched to fill, instead of collapsing to a zero-size source rect that draws nothing.
     * Returns an empty crop for a non-positive size on either side.
     */
    @JvmStatic
    fun centerCrop(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Crop {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return Crop(0, 0, 0, 0)
        val scale = maxOf(dstW.toFloat() / srcW, dstH.toFloat() / srcH)
        val cropW = Math.round(dstW / scale).coerceIn(1, srcW)
        val cropH = Math.round(dstH / scale).coerceIn(1, srcH)
        return Crop((srcW - cropW) / 2, (srcH - cropH) / 2, cropW, cropH)
    }
}
