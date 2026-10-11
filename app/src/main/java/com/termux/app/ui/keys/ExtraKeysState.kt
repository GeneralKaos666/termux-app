package com.termux.app.ui.keys

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Compose-observable state of the bottom extra-keys bar.
 *
 * Owned by the Java [com.termux.app.TermuxActivity], which mutates it as preferences and the
 * active key set change; Compose recomposes on assignment. The property setters are intentionally
 * public so Java can assign them directly.
 */
class ExtraKeysState {

    /** Whether the whole bar is shown. Mirrors the old "terminal toolbar" preference. */
    var visible: Boolean by mutableStateOf(false)

    /** Whether the inline "type a line and send" field is revealed above the keys. */
    var textInputVisible: Boolean by mutableStateOf(false)

    /** Total height of the key grid in pixels, or 0 when there are no keys to show. */
    var gridHeightPx: Int by mutableStateOf(0)
}
