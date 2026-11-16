package com.termux.app.domain.sessions

/**
 * Short labels for the second line of a session tab.
 *
 * The label is a hint, not information the user must read: it is elided to roughly one tab's width
 * so a long path cannot push the tab title off the bar.
 */
private const val LABEL_BUDGET = 22

/**
 * Shortens [cwd] for a tab: `~` for home, `~/...` under home, otherwise the basename prefixed with
 * `…/`, cut on a `-` boundary when it does not fit [LABEL_BUDGET].
 *
 * Returns null when there is no usable path, so callers can drop the subtitle entirely.
 */
fun cwdLabel(cwd: String?, home: String): String? {
    val path = cwd?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val normalized = path.trimEnd('/').ifEmpty { "/" }
    val homePath = home.trim().trimEnd('/').ifEmpty { "/" }

    if (normalized == homePath) return "~"
    if (homePath != "/" && normalized.startsWith("$homePath/")) {
        return "~/" + normalized.removePrefix("$homePath/")
    }

    val basename = normalized.substringAfterLast('/').ifEmpty { normalized }
    if (basename.length <= LABEL_BUDGET) return "…/$basename"

    var kept = ""
    for (word in basename.split('-')) {
        val candidate = if (kept.isEmpty()) word else "$kept-$word"
        if (candidate.length > LABEL_BUDGET) break
        kept = candidate
    }
    if (kept.isEmpty()) kept = basename.take(LABEL_BUDGET)
    return "…/$kept"
}

/**
 * Builds the tab subtitle from the working directory and the foreground command, joined with a
 * middle dot. Either half may be absent; both absent means no subtitle.
 */
fun tabSubtitle(cwd: String?, home: String, command: String?): String? {
    val labels = listOfNotNull(
        cwdLabel(cwd, home),
        command?.trim()?.takeIf { it.isNotEmpty() },
    )
    return labels.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
