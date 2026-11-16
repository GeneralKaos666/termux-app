package com.termux.app.ui.snippets

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.app.domain.snippets.Snippet

/**
 * Compose-observable snapshot of the user's snippets for the shell's quick-chips row.
 *
 * Owned by the activity, which reloads it whenever the snippet editor closes and applies the
 * visibility preference. The editor's own draft lives in [SnippetsState].
 */
class SnippetChipsState {

    var items: List<Snippet> by mutableStateOf(emptyList())

    /** Whether the chips row is shown above the extra-keys grid (`SNIPPETS_ROW`). */
    var rowVisible: Boolean by mutableStateOf(true)
}
