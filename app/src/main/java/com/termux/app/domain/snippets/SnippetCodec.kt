package com.termux.app.domain.snippets

/** Separator between the label and the command in the persisted form. */
private const val FIELD_SEPARATOR = '\t'

/** Most snippets kept; beyond this the list stops growing. */
const val MAX_SNIPPETS = 20

/**
 * Parses the persisted snippet value: one `label<TAB>command` per line.
 *
 * Only the first tab separates the fields, so a command may itself contain tabs. Rows with a blank
 * label or command are dropped, and unknown junk yields an empty list rather than an error, because
 * the value is app-private and a bad write must not take the snippet UI down.
 */
fun parseSnippets(raw: String?): List<Snippet> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.lineSequence()
        .mapNotNull { line -> parseSnippetLine(line) }
        .take(MAX_SNIPPETS)
        .mapIndexed { index, snippet -> snippet.copy(id = "snippet-${index + 1}") }
        .toList()
}

private fun parseSnippetLine(line: String): Snippet? {
    val tab = line.indexOf(FIELD_SEPARATOR)
    if (tab < 0) return null
    val label = line.substring(0, tab).trim()
    val command = line.substring(tab + 1).trim()
    if (label.isEmpty() || command.isEmpty()) return null
    return Snippet(id = "", label = label, command = command)
}

/** Serializes [items] back to the persisted form; ids are not stored, they are assigned on read. */
fun serializeSnippets(items: List<Snippet>): String =
    items.joinToString("\n") { "${it.label}$FIELD_SEPARATOR${it.command}" }
