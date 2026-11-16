package com.termux.app.domain.palette

import com.termux.app.domain.sessions.SessionItem
import com.termux.app.domain.snippets.Snippet

/**
 * Filters and ranks palette rows for [query].
 *
 * Ranking: label prefix, then a word prefix, then a label substring, then a hint substring, then a
 * loose subsequence (the terminal-style "fuzzy" match). Ties keep input order.
 */
fun filterPalette(entries: List<PaletteEntry>, query: String): List<PaletteEntry> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return entries
    return entries
        .mapNotNull { entry -> rank(entry, needle)?.let { rank -> entry to rank } }
        .sortedBy { (_, rank) -> rank }
        .map { (entry, _) -> entry }
}

/**
 * Builds the palette rows: the fixed app actions first, then one row per session, then one row per
 * snippet. Session and snippet payloads are the values the host needs to act on the choice.
 */
fun paletteEntries(
    sessions: List<SessionItem>,
    snippets: List<Snippet>,
    labels: PaletteLabels,
): List<PaletteEntry> {
    val actions = listOf(
        PaletteEntry("new-session", labels.newSession, null, PaletteAction.NEW_SESSION),
        PaletteEntry("new-float", labels.newFloatSession, null, PaletteAction.NEW_FLOAT_SESSION),
        PaletteEntry("appearance", labels.openAppearance, null, PaletteAction.OPEN_APPEARANCE),
        PaletteEntry("settings", labels.openSettings, null, PaletteAction.OPEN_SETTINGS),
        PaletteEntry("toggle-keys", labels.toggleKeys, null, PaletteAction.TOGGLE_KEYS),
        PaletteEntry("paste", labels.paste, null, PaletteAction.PASTE),
    )
    val sessionRows = sessions.map { session ->
        PaletteEntry(
            id = "session-${session.id}",
            label = session.title,
            hint = session.subtitle,
            action = PaletteAction.JUMP_SESSION,
            payload = session.id.toString(),
        )
    }
    val snippetRows = snippets.map { snippet ->
        PaletteEntry(
            id = snippet.id,
            label = snippet.label,
            hint = snippet.command,
            action = PaletteAction.RUN_SNIPPET,
            payload = snippet.command,
        )
    }
    return actions + sessionRows + snippetRows
}

private fun rank(entry: PaletteEntry, needle: String): Int? {
    val label = entry.label.lowercase()
    val hint = entry.hint?.lowercase()
    return when {
        label.startsWith(needle) -> 0
        label.split(' ', '-', '_', '/').any { it.isNotEmpty() && it.startsWith(needle) } -> 1
        label.contains(needle) -> 2
        hint != null && hint.contains(needle) -> 3
        isSubsequence(needle, label) -> 4
        hint != null && isSubsequence(needle, hint) -> 5
        else -> null
    }
}

private fun isSubsequence(needle: String, haystack: String): Boolean {
    var at = 0
    for (char in haystack) {
        if (at < needle.length && char == needle[at]) at++
        if (at == needle.length) return true
    }
    return at == needle.length
}
