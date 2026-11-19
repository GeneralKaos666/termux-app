package com.termux.app.fragments.settings.termux

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.Keep
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.termux.R
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences
import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants.TERMUX_APP

@Keep
class KeyboardPreferencesFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = this.context ?: return

        val preferenceManager: PreferenceManager = preferenceManager
        preferenceManager.setPreferenceDataStore(KeyboardPreferencesDataStore.getInstance(context))

        setPreferencesFromResource(R.xml.termux_keyboard_preferences, rootKey)

        val extraKeysPreset = findPreference<ListPreference>("extra_keys_preset")
        if (extraKeysPreset != null) {
            extraKeysPreset.setEntries(
                arrayOf<CharSequence>(
                    getString(R.string.termux_terminal_view_extra_keys_preset_entry_default),
                    getString(R.string.termux_terminal_view_extra_keys_preset_entry_compact),
                    getString(R.string.termux_terminal_view_extra_keys_preset_entry_vim),
                    getString(R.string.termux_terminal_view_extra_keys_preset_entry_emacs),
                ),
            )
            extraKeysPreset.setEntryValues(
                arrayOf<CharSequence>(
                    TERMUX_APP.DEFAULT_IVALUE_EXTRA_KEYS_PRESET,
                    "compact",
                    "vim",
                    "emacs",
                ),
            )
        }

        val keyLayouts = findPreference<Preference>("key_layouts")
        if (keyLayouts != null) {
            keyLayouts.setOnPreferenceClickListener {
                startActivity(Intent(context, com.termux.app.ui.keys.KeyLayoutGalleryActivity::class.java))
                true
            }
        }

        val extraKeysPage2Preset = findPreference<ListPreference>("extra_keys_page2_preset")
        if (extraKeysPage2Preset != null) {
            extraKeysPage2Preset.setEntries(
                arrayOf<CharSequence>(
                    getString(R.string.termux_terminal_view_extra_keys_page2_preset_entry_default),
                    getString(R.string.termux_terminal_view_extra_keys_page2_preset_entry_function),
                ),
            )
            extraKeysPage2Preset.setEntryValues(
                arrayOf<CharSequence>(
                    TERMUX_APP.DEFAULT_IVALUE_EXTRA_KEYS_PAGE2_PRESET,
                    "function",
                ),
            )
        }
    }

}

@SuppressLint("StaticFieldLeak")
class KeyboardPreferencesDataStore private constructor(context: Context) : PreferenceDataStore() {

    // Holds the application context: the store is a process-wide singleton, so an Activity here
    // would leak. Lint only sees the field type, which is why the class suppresses its check.
    private val mContext: Context = context
    private val mPreferences: TermuxAppSharedPreferences? = TermuxAppSharedPreferences.build(context, true)

    companion object {
        private var mInstance: KeyboardPreferencesDataStore? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): KeyboardPreferencesDataStore {
            if (mInstance == null) {
                mInstance = KeyboardPreferencesDataStore(context.applicationContext)
            }
            return mInstance!!
        }
    }

    override fun putBoolean(key: String?, value: Boolean) {
        val preferences = mPreferences ?: return
        if (key == null) return

        when (key) {
            "soft_keyboard_enabled" ->
                preferences.setSoftKeyboardEnabled(value)
            "soft_keyboard_enabled_only_if_no_hardware" ->
                preferences.setSoftKeyboardEnabledOnlyIfNoHardware(value)
            else -> {}
        }
    }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        val preferences = mPreferences ?: return false

        return when (key) {
            "soft_keyboard_enabled" ->
                preferences.isSoftKeyboardEnabled()
            "soft_keyboard_enabled_only_if_no_hardware" ->
                preferences.isSoftKeyboardEnabledOnlyIfNoHardware()
            else -> false
        }
    }

    override fun putString(key: String?, value: String?) {
        val preferences = mPreferences ?: return
        if (key == null) return

        when (key) {
            "extra_keys_preset" ->
                preferences.setExtraKeysPreset(value)
            "extra_keys_page2_preset" ->
                preferences.setExtraKeysPage2Preset(value)
            else -> {}
        }
    }

    override fun getString(key: String?, defValue: String?): String? {
        val preferences = mPreferences ?: return defValue

        return when (key) {
            "extra_keys_preset" -> preferences.getExtraKeysPreset()
            "extra_keys_page2_preset" -> preferences.getExtraKeysPage2Preset()
            else -> defValue
        }
    }

}
