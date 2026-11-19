package com.termux.app.fragments.settings.termux_tasker

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import androidx.annotation.Keep
import androidx.preference.ListPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.termux.R
import com.termux.shared.termux.settings.preferences.TermuxTaskerAppSharedPreferences

@Keep
class DebuggingPreferencesFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = this.context ?: return

        val preferenceManager: PreferenceManager = preferenceManager
        preferenceManager.setPreferenceDataStore(DebuggingPreferencesDataStore.getInstance(context))

        setPreferencesFromResource(R.xml.termux_tasker_debugging_preferences, rootKey)

        configureLoggingPreferences(context)
    }

    private fun configureLoggingPreferences(context: Context) {
        val loggingCategory = findPreference<PreferenceCategory>("logging") ?: return

        val logLevelListPreference = findPreference<ListPreference>("log_level")
        if (logLevelListPreference != null) {
            val preferences = TermuxTaskerAppSharedPreferences.build(context, true) ?: return

            com.termux.app.fragments.settings.termux.DebuggingPreferencesFragment
                .setLogLevelListPreferenceData(logLevelListPreference, context, preferences.getLogLevel(true))
            loggingCategory.addPreference(logLevelListPreference)
        }
    }
}

@SuppressLint("StaticFieldLeak")
class DebuggingPreferencesDataStore private constructor(context: Context) : PreferenceDataStore() {

    // Holds the application context: the store is a process-wide singleton, so an Activity here
    // would leak. Lint only sees the field type, which is why the class suppresses its check.
    private val mContext: Context = context
    private val mPreferences: TermuxTaskerAppSharedPreferences? = TermuxTaskerAppSharedPreferences.build(context, true)

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

}
