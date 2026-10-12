package com.termux.app.ui.firstrun

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Compose-observable first-run onboarding state, mutated from the Java
 * [com.termux.app.TermuxActivity] like the other shell states. The property setters are
 * intentionally public so Java can assign them directly.
 *
 * The overlay is shown by Compose when [visible] is true *and* the sessions bar is still empty, so
 * the overlay dismisses itself the moment the first session is added.
 */
class FirstRunState {
    var visible: Boolean by mutableStateOf(false)

    /** True while the bootstrap install is running; the overlay switches to a progress state. */
    var installing: Boolean by mutableStateOf(false)
}
