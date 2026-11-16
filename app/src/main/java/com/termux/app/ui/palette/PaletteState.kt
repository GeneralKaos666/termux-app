package com.termux.app.ui.palette

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.app.domain.palette.PaletteEntry
import com.termux.app.domain.palette.filterPalette

/**
 * Compose-observable state for the command palette.
 *
 * Owned by the activity, like the other shell states, so a hardware shortcut can open it without
 * going through Compose. Entries are rebuilt by the shell whenever the
 * session list or snippets change; [open] keeps the current query reset and the selection at the
 * first row.
 */
class PaletteState {

    var visible: Boolean by mutableStateOf(false)

    var query: String by mutableStateOf("")

    var entries: List<PaletteEntry> by mutableStateOf(emptyList())

    var filtered: List<PaletteEntry> by mutableStateOf(emptyList())

    var selectedIndex: Int by mutableStateOf(0)

    /** Replaces the source rows and re-applies the current query. */
    fun updateEntries(newEntries: List<PaletteEntry>) {
        entries = newEntries
        filtered = filterPalette(newEntries, query)
        if (selectedIndex !in filtered.indices) selectedIndex = 0
    }

    fun open() {
        visible = true
        query = ""
        selectedIndex = 0
        filtered = entries
    }

    fun close() {
        visible = false
        query = ""
        selectedIndex = 0
    }

    fun updateQuery(newQuery: String) {
        query = newQuery
        filtered = filterPalette(entries, newQuery)
        if (selectedIndex !in filtered.indices) selectedIndex = 0
    }

    fun moveSelection(delta: Int) {
        if (filtered.isEmpty()) return
        selectedIndex = (selectedIndex + delta).coerceIn(0, filtered.lastIndex)
    }

    fun selected(): PaletteEntry? = filtered.getOrNull(selectedIndex)
}
