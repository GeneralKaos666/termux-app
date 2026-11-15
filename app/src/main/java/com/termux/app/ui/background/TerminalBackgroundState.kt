package com.termux.app.ui.background

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Compose-observable terminal background picture, mutated from the Java activity like the other
 * shell states. `bitmap == null` means no picture (solid terminal background colour).
 */
class TerminalBackgroundState {
    var bitmap by mutableStateOf<Bitmap?>(null)
        private set

    /** Dim percentage applied over the picture for text readability, 0-90. */
    var dimPercent by mutableStateOf(60)
        private set

    /** Blur radius applied to the picture in pixels, 0 = sharp. */
    var blurRadius by mutableStateOf(0f)
        private set

    /** Opacity of the picture, 0.1-1.0. Independent of [dimPercent]. */
    var pictureOpacity by mutableStateOf(1f)
        private set

    /** Keeps callers that predate blur/opacity working with today's sharp, opaque picture. */
    fun update(bitmap: Bitmap?, dimPercent: Int) = update(bitmap, dimPercent, 0f, 1f)

    fun update(bitmap: Bitmap?, dimPercent: Int, blurRadius: Float, pictureOpacity: Float) {
        this.bitmap = bitmap
        this.dimPercent = dimPercent.coerceIn(0, 90)
        this.blurRadius = blurRadius.coerceAtLeast(0f)
        this.pictureOpacity = pictureOpacity.coerceIn(0.1f, 1f)
    }
}
