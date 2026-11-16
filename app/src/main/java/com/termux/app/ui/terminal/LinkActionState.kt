package com.termux.app.ui.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.app.domain.terminal.LinkAction
import com.termux.app.domain.terminal.availableLinkActions

/**
 * Compose-observable state for the link action sheet.
 *
 * Owned by the activity so the terminal view client can open it from a tap or a link click without
 * going through Compose.
 */
class LinkActionState {

    var visible: Boolean by mutableStateOf(false)
        private set

    /** The URL the sheet acts on. */
    var url: String by mutableStateOf("")
        private set

    var actions: List<LinkAction> by mutableStateOf(emptyList())
        private set

    /** Shows the sheet for [url]; a URL with no actions leaves it closed. */
    fun show(url: String) {
        val available = availableLinkActions(url)
        if (available.isEmpty()) return
        this.url = url
        actions = available
        visible = true
    }

    fun dismiss() {
        visible = false
    }
}
