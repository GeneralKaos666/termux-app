package com.termux.app.fragments.settings.termux_float

import android.content.Context
import android.os.Bundle
import androidx.annotation.Keep
import androidx.preference.ListPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.termux.R
import com.termux.shared.termux.settings.preferences.TermuxFloatAppSharedPreferences

@Keep
class DebuggingPreferencesFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = this.context ?: return

        val preferenceManager: PreferenceManager = preferenceManager
        preferenceManager.setPreferenceDataStore(DebuggingPreferencesDataStore.getInstance(context))

        setPreferencesFromResource(R.xml.termux_float_debugging_preferences, rootKey)

        configureLoggingPreferences(context)
    }

    private fun configureLoggingPreferences(context: Context) {
        val loggingCategory = findPreference<PreferenceCategory>("logging") ?: return

        val logLevelListPreference = findPreference<ListPreference>("log_level")
        if (logLevelListPreference != null) {
            val preferences = TermuxFloatAppSharedPreferences.build(context, false) ?: return

            com.termux.app.fragments.settings.termux.DebuggingPreferencesFragment
                .setLogLevelListPreferenceData(logLevelListPreference, context, preferences.getLogLevel(true))
            loggingCategory.addPreference(logLevelListPreference)
        }
    }
}

class DebuggingPreferencesDataStore private constructor(context: Context) : PreferenceDataStore() {

    private val mContext: Context = context
    private val mPreferences: TermuxFloatAppSharedPreferences? = TermuxFloatAppSharedPreferences.build(context, false)

    companion object {
        private var mInstance: DebuggingPreferencesDataStore? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): DebuggingPreferencesDataStore {
            if (mInstance == null) {
                mInstance = DebuggingPreferencesDataStore(context.applicationContext)
            }
            return mInstance!!
        }
    }

    override fun getString(key: String?, defValue: String?): String? {
        val preferences = mPreferences ?: return null
        if (key == null) return null

        return when (key) {
            "log_level" -> preferences.getLogLevel(true).toString()
            else -> null
        }
    }

    override fun putString(key: String?, value: String?) {
        val preferences = mPreferences ?: return
        if (key == null) return

        when (key) {
            "log_level" -> {
                if (value != null) {
                    preferences.setLogLevel(mContext, value.toInt(), true)
                }
            }
            else -> {}
        }
    }

    override fun putBoolean(key: String?, value: Boolean) {
        val preferences = mPreferences ?: return
        if (key == null) return

        when (key) {
            "terminal_view_key_logging_enabled" ->
                preferences.setTerminalViewKeyLoggingEnabled(value, true)
            else -> {}
        }
    }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        val preferences = mPreferences ?: return false

        return when (key) {
            "terminal_view_key_logging_enabled" ->
                preferences.isTerminalViewKeyLoggingEnabled(true)
            else -> false
        }
    }

}
