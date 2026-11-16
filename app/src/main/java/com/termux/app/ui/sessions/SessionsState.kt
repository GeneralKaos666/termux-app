package com.termux.app.ui.sessions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.app.domain.sessions.SessionItem

/**
 * Compose-observable snapshot of the sessions shown in the sessions bar.
 *
 * Owned by the Java [TermuxActivity], which rebuilds and assigns [sessions] whenever
 * the service session list changes (see `TermuxActivity.refreshSessionsBar()`). The property
 * setter is intentionally public so Java can assign it directly; Compose recomposes on assignment.
 */
class SessionsState {
    var sessions: List<SessionItem> by mutableStateOf(emptyList())

    /**
     * Stable ids of sessions that produced output while they were not active. Kept beside
     * [sessions] so an output callback can update one badge without rebuilding every tab.
     */
    var unseen: Set<Int> by mutableStateOf(emptySet())

    /** Marks [id] as having unseen output; the caller skips the call when the mark exists. */
    fun markUnseen(id: Int) {
        if (id in unseen) return
        unseen = unseen + id
    }

    /** Clears the mark once the user selects the tab. */
    fun markSelected(id: Int) {
        if (id !in unseen) return
        unseen = unseen - id
    }
}
