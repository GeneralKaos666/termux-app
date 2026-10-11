package com.termux.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Compose-observable snackbar requests posted by the Java activity and consumed by the shell host.
 * [sequence] increments on every request so identical consecutive messages are still observed.
 */
class SnackbarState {
    var message by mutableStateOf<String?>(null)
        private set
    var undoLabel by mutableStateOf<String?>(null)
        private set
    var sequence by mutableStateOf(0)
        private set

    /** Posts [message]; pass a non-null [undoLabel] to show an undo action with it. */
    @JvmOverloads
    fun post(message: String, undoLabel: String? = null) {
        this.message = message
        this.undoLabel = undoLabel
        sequence++
    }
}
