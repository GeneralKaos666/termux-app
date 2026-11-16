package com.termux.app.data.keys

import android.content.Context
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences

/**
 * Reads and writes the selected extra-keys preset.
 *
 * Wraps [TermuxAppSharedPreferences] so the Compose gallery activity does not reach into shared
 * preferences itself: `ui` may only touch them from the appearance activity's composition root.
 */
class ExtraKeysPresetStore(context: Context) {

    private val preferences = TermuxAppSharedPreferences.build(context, true)

    /** The saved preset id, or the default preset when none is saved. */
    fun current(): String? = preferences?.extraKeysPreset

    /** Saves [id] as the chosen preset; the terminal applies it on its next resume. */
    fun save(id: String) {
        preferences?.extraKeysPreset = id
    }
}
