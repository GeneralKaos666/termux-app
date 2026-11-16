package com.termux.app.domain.settings

/**
 * One searchable settings row.
 *
 * @property key the preference key, which identifies the row.
 * @property title the row's title, as shown in the settings list.
 * @property summary the row's summary, or an empty string when it has none.
 * @property fragment the settings fragment the row navigates to, or `null` for a leaf row.
 */
data class SettingsEntry(
    val key: String,
    val title: String,
    val summary: String,
    val fragment: String?,
)

/**
 * The entries matching [query], in the input order.
 *
 * Matching is case-insensitive and looks at the title and the summary: the title is what the user
 * remembers, the summary is what names the setting when the title is vague. A blank query returns
 * every entry, which is what the search screen shows before the user types.
 */
fun filterSettings(entries: List<SettingsEntry>, query: String): List<SettingsEntry> {
    val needle = query.trim()
    if (needle.isEmpty()) return entries
    return entries.filter { entry ->
        entry.title.contains(needle, ignoreCase = true) ||
            entry.summary.contains(needle, ignoreCase = true)
    }
}
