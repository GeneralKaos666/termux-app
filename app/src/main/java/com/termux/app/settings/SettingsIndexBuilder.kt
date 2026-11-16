package com.termux.app.settings

import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import com.termux.app.domain.settings.SettingsEntry

/**
 * Flattens a preference screen into the rows settings search can offer.
 *
 * A category is a heading, not a setting: it is walked for children but never returned, even when
 * it carries a key (this app's categories do). A row is anything else with a key, which is what
 * navigation and persistence need.
 */
fun settingsIndex(screen: PreferenceScreen): List<SettingsEntry> = buildList {
    collect(screen, this)
}

private fun collect(group: PreferenceGroup, into: MutableList<SettingsEntry>) {
    for (index in 0 until group.preferenceCount) {
        val preference = group.getPreference(index) ?: continue
        when (preference) {
            is PreferenceGroup -> collect(preference, into)
            else -> {
                val key = preference.key ?: continue
                into += SettingsEntry(
                    key = key,
                    title = preference.title?.toString().orEmpty(),
                    summary = preference.summary?.toString().orEmpty(),
                    fragment = preference.fragment,
                )
            }
        }
    }
}
