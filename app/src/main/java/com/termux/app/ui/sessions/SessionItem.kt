package com.termux.app.ui.sessions

/**
 * Immutable projection of a single terminal session for the top sessions bar.
 *
 * This is the Compose-facing model; it deliberately carries no Android or session-service
 * references so it can be built and diffed purely from primitive session fields.
 *
 * @property id Stable identity of the session for as long as it exists, assigned by the activity
 *   and never reused while it lives. This is deliberately not the service list index: selection
 *   callbacks carry it and the activity maps it back to the session, and list mutations cannot
 *   shift one session's identity onto another.
 * @property title Single-line label shown on the tab.
 * @property running Whether the session process is still running.
 * @property exitCode Exit code of the session process; only meaningful when [running] is false.
 * @property active Whether this session is currently attached to the terminal view.
 */
data class SessionItem(
    val id: Int,
    val title: String,
    val running: Boolean,
    val exitCode: Int,
    val active: Boolean,
)

/** Coarse state of a session, used to pick the tab's status dot color. */
enum class SessionState { RUNNING, EXITED_SUCCESS, EXITED_ERROR }

/**
 * Maps a session's running/exit-code pair to its [SessionState]. This is the tab-state logic that
 * used to live in `TermuxSessionsListViewController` (running sessions are shown normally, finished
 * sessions with a non-zero exit are a failure).
 */
fun sessionState(running: Boolean, exitCode: Int): SessionState =
    when {
        running -> SessionState.RUNNING
        exitCode == 0 -> SessionState.EXITED_SUCCESS
        else -> SessionState.EXITED_ERROR
    }

/**
 * Builds the single-line tab title for a session: the user-set name if present, otherwise the
 * terminal title, otherwise a numbered fallback. Flattened from the two-line title composition in
 * `TermuxSessionsListViewController` so it fits on one tab.
 */
fun sessionTitle(number: Int, name: String?, terminalTitle: String?): String {
    name?.takeIf { it.isNotBlank() }?.let { return it }
    terminalTitle?.takeIf { it.isNotBlank() }?.let { return it }
    return "Session $number"
}

/** Convenience factory for a [SessionItem] from raw session fields. */
fun sessionItem(
    id: Int,
    number: Int,
    name: String?,
    terminalTitle: String?,
    running: Boolean,
    exitCode: Int,
    active: Boolean,
): SessionItem = SessionItem(
    id = id,
    title = sessionTitle(number, name, terminalTitle),
    running = running,
    exitCode = exitCode,
    active = active,
)
