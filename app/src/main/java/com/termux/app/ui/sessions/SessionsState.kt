package com.termux.app.ui.sessions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Compose-observable snapshot of the sessions shown in the sessions bar.
 *
 * Owned by the Java [com.termux.app.TermuxActivity], which rebuilds and assigns [sessions] whenever
 * the service session list changes (see `TermuxActivity.refreshSessionsBar()`). The property
 * setter is intentionally public so Java can assign it directly; Compose recomposes on assignment.
 */
class SessionsState {
    var sessions: List<SessionItem> by mutableStateOf(emptyList())
}
