package com.termux.app.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** How long a visual bell is kept from repeating, in milliseconds. */
internal const val BELL_THROTTLE_MILLIS = 400L

/**
 * Compose-observable snackbar requests posted by the Java activity and consumed by the shell host.
 * [sequence] increments on every request so identical consecutive messages are still observed.
 *
 * @param clock reads the monotonic time in milliseconds; injectable so the bell throttle is
 *   testable without waiting.
 */
class SnackbarState @JvmOverloads constructor(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    var message by mutableStateOf<String?>(null)
        private set
    var undoLabel by mutableStateOf<String?>(null)
        private set
    var sequence by mutableStateOf(0)
        private set

    /** When the last bell was shown, or `null` when none has been shown yet. */
    private var lastBellAt: Long? = null

    /** Posts [message]; pass a non-null [undoLabel] to show an undo action with it. */
    @JvmOverloads
    fun post(message: String, undoLabel: String? = null) {
        this.message = message
        this.undoLabel = undoLabel
        sequence++
    }

    /**
     * Posts the visual bell [message], unless one was shown less than [BELL_THROTTLE_MILLIS] ago.
     *
     * A program that rings on every prompt would otherwise stack snackbars over the terminal, so
     * bells arriving inside the window are dropped rather than queued.
     */
    fun showBell(message: String) {
        val now = clock()
        val previous = lastBellAt
        if (previous != null && now - previous < BELL_THROTTLE_MILLIS) return
        lastBellAt = now
        post(message)
    }
}
