package com.termux.app.ui.floating

/**
 * Pure geometry helpers for the in-app floating terminal window. No Android dependencies: the
 * window type constants mirror the `WindowManager.LayoutParams` values so the sdk-gated selection
 * can be unit tested without a running framework.
 */
object FloatWindowGeometry {

    /** `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`, valid on API 26+. */
    const val TYPE_APPLICATION_OVERLAY = 2038

    /** `WindowManager.LayoutParams.TYPE_PHONE`, the pre-API-26 overlay type. */
    const val TYPE_PHONE = 2002

    /** Returns the overlay window type to use for a given SDK level. */
    fun windowType(sdkInt: Int): Int =
        if (sdkInt >= 26) TYPE_APPLICATION_OVERLAY else TYPE_PHONE

    /** Clamps a window x coordinate to `[0, maxX - width]`, flooring at 0. */
    fun clampX(x: Int, width: Int, maxX: Int): Int =
        x.coerceIn(0, (maxX - width).coerceAtLeast(0))

    /** Clamps a window y coordinate to `[0, maxY - height]`, flooring at 0. */
    fun clampY(y: Int, height: Int, maxY: Int): Int =
        y.coerceIn(0, (maxY - height).coerceAtLeast(0))

    /** Clamps a window width to `[minWidth, maxWidth]`. */
    fun clampWidth(width: Int, minWidth: Int, maxWidth: Int): Int =
        width.coerceIn(minWidth, maxWidth)

    /** Clamps a window height to `[minHeight, maxHeight]`. */
    fun clampHeight(height: Int, minHeight: Int, maxHeight: Int): Int =
        height.coerceIn(minHeight, maxHeight)
}
