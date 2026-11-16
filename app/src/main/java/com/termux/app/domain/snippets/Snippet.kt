package com.termux.app.domain.snippets

/** A saved command the user can insert into a session. Persisted by `SnippetStore`. */
data class Snippet(
    val id: String,
    val label: String,
    val command: String,
)
