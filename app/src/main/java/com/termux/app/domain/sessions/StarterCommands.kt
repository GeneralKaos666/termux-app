package com.termux.app.domain.sessions

/** A command offered on the empty sessions bar; tapping it starts a session and inserts it. */
data class StarterCommand(val command: String)

/**
 * The starter chips shown while the bar is empty. Nothing is offered before the bootstrap is
 * installed, because no command exists yet, and nothing is offered once a session already exists,
 * because the prompt is only for the empty bar.
 */
fun starterChips(isFirstSession: Boolean, hasBootstrap: Boolean): List<StarterCommand> {
    if (!isFirstSession || !hasBootstrap) return emptyList()
    return listOf(
        StarterCommand("pkg upgrade"),
        StarterCommand("pkg install neofetch"),
        StarterCommand("ls"),
    )
}
