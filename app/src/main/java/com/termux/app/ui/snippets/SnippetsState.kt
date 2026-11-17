package com.termux.app.ui.snippets

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.app.data.snippets.SnippetStore
import com.termux.app.domain.snippets.MAX_SNIPPETS
import com.termux.app.domain.snippets.Snippet

/**
 * Editable snippet list for [SnippetsScreen].
 *
 * Edits stay in memory until [save], so a mis-tap does not rewrite the store; the screen's Apply
 * action calls [save] and finishes. Rows added here get a local [DRAFT_ID_PREFIX] id, because the
 * screen edits and deletes by id: two new rows sharing an id would edit or delete each other.
 * [save] strips the draft ids and lets the store number the rows it keeps.
 */
class SnippetsState(private val store: SnippetStore) {

    /** Next draft number; never reused, so a removed row's id cannot come back. */
    private var nextDraftId = 1

    var items: List<Snippet> by mutableStateOf(store.load())
        private set

    /** True when no more rows can be added; the store keeps at most [MAX_SNIPPETS]. */
    val atLimit: Boolean
        get() = items.size >= MAX_SNIPPETS

    /**
     * Appends a draft row; a blank label or command is ignored rather than saved as an empty chip.
     * @return whether a row was appended.
     */
    fun add(label: String, command: String): Boolean {
        if (atLimit) return false
        val trimmedLabel = label.trim()
        val trimmedCommand = command.trim()
        if (trimmedLabel.isEmpty() || trimmedCommand.isEmpty()) return false
        items = items + Snippet(id = newDraftId(), label = trimmedLabel, command = trimmedCommand)
        return true
    }

    /**
     * Appends an empty row for the add button; [save] drops it again if it is left blank.
     * @return whether a row was appended.
     */
    fun addBlank(): Boolean {
        if (atLimit) return false
        items = items + Snippet(id = newDraftId(), label = "", command = "")
        return true
    }

    private fun newDraftId(): String = DRAFT_ID_PREFIX + nextDraftId++

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
        // A draft id is local to this editor; the store owns the ids of saved rows.
        val saved = store.save(items.map { snippet ->
            if (snippet.id.startsWith(DRAFT_ID_PREFIX)) snippet.copy(id = "") else snippet
        })
        items = saved
        return saved
    }
}

/** Marks the id of a row that exists only in the editor. */
private const val DRAFT_ID_PREFIX = "draft-"
