package com.termux.app.ui.snippets

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.app.data.snippets.SnippetStore
import com.termux.app.domain.snippets.Snippet

/**
 * Editable snippet list for [SnippetsScreen].
 *
 * Edits stay in memory until [save], so a mis-tap does not rewrite the store; the screen's Apply
 * action calls [save] and finishes. Rows added here have no id yet; [save] assigns them.
 */
class SnippetsState(private val store: SnippetStore) {

    var items: List<Snippet> by mutableStateOf(store.load())
        private set

    /** Appends a draft row; a blank label or command is ignored rather than saved as an empty chip. */
    fun add(label: String, command: String) {
        val trimmedLabel = label.trim()
        val trimmedCommand = command.trim()
        if (trimmedLabel.isEmpty() || trimmedCommand.isEmpty()) return
        items = items + Snippet(id = "", label = trimmedLabel, command = trimmedCommand)
    }

    /** Appends an empty row for the add button; [save] drops it again if it is left blank. */
    fun addBlank() {
        items = items + Snippet(id = "", label = "", command = "")
    }

    fun update(id: String, label: String, command: String) {
        items = items.map { snippet ->
            if (snippet.id == id) snippet.copy(label = label, command = command) else snippet
        }
    }

    fun remove(id: String) {
        items = items.filterNot { it.id == id }
    }

    /** Moves the row at [from] to [to]; an out-of-range index leaves the list untouched. */
    fun move(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices || from == to) return
        val reordered = items.toMutableList()
        val moved = reordered.removeAt(from)
        reordered.add(to, moved)
        items = reordered
    }

    /** Normalizes and persists the list, returning what was written. */
    fun save(): List<Snippet> {
        val saved = store.save(items)
        items = saved
        return saved
    }
}
