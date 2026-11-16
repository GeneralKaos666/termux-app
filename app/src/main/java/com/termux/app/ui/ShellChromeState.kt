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

    /**
     * Margin around the terminal area, in dp, from `terminal-margin-horizontal` and
     * `terminal-margin-vertical`.
     *
     * Applied to the terminal itself rather than to the whole shell. Insetting the shell would leave
     * a strip of the decor background — which is the *terminal* background colour — above the
     * sessions bar and below the extra-keys bar, so a 1dp margin showed up as a thin black line over
     * the chrome.
     */
    var terminalMarginHorizontalDp by mutableStateOf(0)
        private set

    var terminalMarginVerticalDp by mutableStateOf(0)
        private set

    /**
     * Whether the visual bell is tinting the sessions bar right now. [flashBell] turns it on; the
     * shell host turns it off after [BELL_FLASH_MILLIS].
     */
    var bellFlash by mutableStateOf(false)
        private set

    /**
     * Increments on every bell, so a bell that arrives while a flash is running restarts the
     * host's timer instead of being swallowed by the already-running effect.
     */
    var bellSequence by mutableStateOf(0)
        private set

    /**
     * Whether the cold-start intro is still on screen. Set by the activity only when there is no
     * saved instance state, so a rotation or restore does not replay it.
     */
    var splashVisible by mutableStateOf(false)

    /** The intro finished (or was skipped); the shell shows the terminal from now on. */
    fun dismissSplash() {
        splashVisible = false
    }

    /** Starts a visual-bell flash on the chrome. */
    fun flashBell() {
        bellFlash = true
        bellSequence++
    }

    /** Ends the flash; called by the host when [BELL_FLASH_MILLIS] have passed. */
    fun clearBellFlash() {
        bellFlash = false
    }

    fun updateTerminalMargins(horizontalDp: Int, verticalDp: Int) {
        terminalMarginHorizontalDp = horizontalDp.coerceAtLeast(0)
        terminalMarginVerticalDp = verticalDp.coerceAtLeast(0)
    }

    companion object {
        const val DEFAULT_TRANSPARENCY_PERCENT = 30

        /** How long the visual bell tints the chrome, in milliseconds. */
        const val BELL_FLASH_MILLIS = 250L
    }
}

/**
 * Maps a chrome transparency percentage (0-100) to the background alpha used by the bars:
 * 0% keeps the background fully opaque, 100% makes it fully transparent.
 */
fun chromeBackgroundAlpha(transparencyPercent: Int): Float =
    1f - transparencyPercent.coerceIn(0, 100) / 100f
