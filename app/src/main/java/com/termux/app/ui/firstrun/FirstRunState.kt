package com.termux.app.ui.firstrun

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The stages the bootstrap install reports while it runs. The order is the order the installer
 * enters them; the overlay only names the current one.
 */
enum class BootstrapStage {
    DOWNLOADING,
    EXTRACTING,
    FINISHING,
}

/**
 * Compose-observable first-run onboarding state, mutated from the Java
 * [TermuxActivity] like the other shell states. The property setters are
 * intentionally public so Java can assign them directly.
 *
 * The overlay is shown by Compose when [visible] is true *and* the sessions bar is still empty, so
 * the overlay dismisses itself the moment the first session is added.
 */
class FirstRunState {
    var visible: Boolean by mutableStateOf(false)

    /** True while the bootstrap install is running; the overlay switches to a progress state. */
    var installing: Boolean by mutableStateOf(false)

    /** Current install stage; null while the installer has not reported one yet. */
    var stage: BootstrapStage? by mutableStateOf(null)

    /** Starts a fresh install: a stage left over from a previous attempt must not be shown. */
    fun beginInstall() {
        installing = true
        stage = null
    }

    /** The install failed or was dismissed; the overlay returns to its install button. */
    fun installFailed() {
        installing = false
        stage = null
    }
}
