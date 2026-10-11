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

    fun update(bitmap: Bitmap?, dimPercent: Int) {
        this.bitmap = bitmap
        this.dimPercent = dimPercent.coerceIn(0, 90)
    }
}
