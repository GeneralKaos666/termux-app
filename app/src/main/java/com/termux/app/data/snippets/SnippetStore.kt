package com.termux.app.data.snippets

import com.termux.app.data.ui.UiPreferenceKeys
import com.termux.app.data.ui.UiPreferences
import com.termux.app.domain.snippets.MAX_SNIPPETS
import com.termux.app.domain.snippets.Snippet
import com.termux.app.domain.snippets.parseSnippets
import com.termux.app.domain.snippets.serializeSnippets

/**
 * Persists the user's snippets through [UiPreferences].
 *
 * [save] normalizes its input (trimmed, no blank rows, capped) and returns what it wrote, so the
 * caller can use the returned list as the source of truth instead of re-reading.
 */
class SnippetStore(private val prefs: UiPreferences) {

    fun load(): List<Snippet> = parseSnippets(prefs.getString(UiPreferenceKeys.SNIPPETS, null))

    fun save(items: List<Snippet>): List<Snippet> {
        val normalized = normalize(items)
        prefs.setString(UiPreferenceKeys.SNIPPETS, serializeSnippets(normalized))
        return normalized
    }

    private fun normalize(items: List<Snippet>): List<Snippet> {
        val usedIds = HashSet<String>()
        return items.asSequence()
            .map { it.copy(label = it.label.trim(), command = it.command.trim()) }
            .filter { it.label.isNotEmpty() && it.command.isNotEmpty() }
            .take(MAX_SNIPPETS)
            .map { snippet ->
                val id = if (snippet.id.isNotEmpty() && usedIds.add(snippet.id)) snippet.id
                else nextId(usedIds)
                // nextId() already added the generated id to usedIds.
                snippet.copy(id = id)
            }
            .toList()
    }

    private fun nextId(usedIds: MutableSet<String>): String {
        var index = 1
        while (!usedIds.add("snippet-$index")) index++
        return "snippet-$index"
    }
}
