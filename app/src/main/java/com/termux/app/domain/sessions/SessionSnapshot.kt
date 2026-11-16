package com.termux.app.domain.sessions

/** Primitive view of a terminal session, read by the activity and projected into a [SessionItem]. */
data class SessionSnapshot(
    val name: String?,
    val terminalTitle: String?,
    val running: Boolean,
    val exitStatus: Int,
)
