package com.termux.app.data.ui

import android.content.Context

/**
 * [UiPreferences] backed by the app-private `termux_ui` preferences file.
 *
 * A null value removes the key, so a cleared string returns the caller's default rather than an
 * empty string.
 */
class AndroidUiPreferences(context: Context) : UiPreferences {

    private val preferences =
        context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)

    override fun getBoolean(key: String, def: Boolean): Boolean = preferences.getBoolean(key, def)

    override fun setBoolean(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
    }

    override fun getString(key: String, def: String?): String? = preferences.getString(key, def)

    override fun setString(key: String, value: String?) {
        val editor = preferences.edit()
        if (value == null) editor.remove(key) else editor.putString(key, value)
        editor.apply()
    }

    private companion object {
        const val PREFERENCES_FILE = "termux_ui"
    }
}
