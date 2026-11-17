package com.termux.app.api.file

/**
 * Lets one activity instance consume its launch intent exactly once. `onResume` re-enters after a
 * dialog or a permission prompt; without the gate the share is saved and the editor is launched a
 * second time.
 */
internal class ReceivedIntentGate {
    private var handled = false

    fun begin(): Boolean {
        if (handled) return false
        handled = true
        return true
    }
}
