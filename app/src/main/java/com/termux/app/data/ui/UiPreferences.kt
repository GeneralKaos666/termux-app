package com.termux.app.data.ui

/**
 * App-private key/value store for the shell UI features.
 *
 * This is deliberately separate from `TermuxAppSharedPreferences`, which owns the properties the
 * terminal engine and the published `:termux-shared` surface depend on. Nothing here changes how
 * the terminal renders; it only turns optional UI affordances on and off.
 */
interface UiPreferences {
    fun getBoolean(key: String, def: Boolean): Boolean

    fun setBoolean(key: String, value: Boolean)

    fun getString(key: String, def: String?): String?

    fun setString(key: String, value: String?)
}

/** Keys used with [UiPreferences]. Stored as strings so a key rename is a deliberate edit. */
object UiPreferenceKeys {
    const val TAB_SUBTITLES = "tab_subtitles"
    const val COMMAND_PALETTE = "command_palette"
    const val SNIPPETS = "snippets"
    const val SNIPPETS_ROW = "snippets_row"
    const val LINK_ACTION_SHEET = "link_action_sheet"
}
