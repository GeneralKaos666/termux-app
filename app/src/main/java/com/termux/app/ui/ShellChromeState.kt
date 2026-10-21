package com.termux.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Compose-observable state for the shell chrome (the sessions tab bar and the extra-keys bar, whose
 * second page is a second key grid), mutated from the Java activity like the other shell states.
 */
class ShellChromeState {

    /**
     * Transparency percentage of the chrome bars, 0-100: 0 is fully opaque (the historical look),
     * 100 fully transparent. The bars fade over the full-screen terminal background.
     */
    var barTransparencyPercent by mutableStateOf(DEFAULT_TRANSPARENCY_PERCENT)
        private set

    fun update(barTransparencyPercent: Int) {
        this.barTransparencyPercent = barTransparencyPercent.coerceIn(0, 100)
    }

    companion object {
        const val DEFAULT_TRANSPARENCY_PERCENT = 30
    }
}

/**
 * Maps a chrome transparency percentage (0-100) to the background alpha used by the bars:
 * 0% keeps the background fully opaque, 100% makes it fully transparent.
 */
fun chromeBackgroundAlpha(transparencyPercent: Int): Float =
    1f - transparencyPercent.coerceIn(0, 100) / 100f
