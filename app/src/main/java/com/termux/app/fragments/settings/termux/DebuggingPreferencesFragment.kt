package com.termux.app.fragments.settings.termux

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.Keep
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.termux.R
import com.termux.app.ui.backup.BackupActivity
import com.termux.shared.logger.Logger
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences

@Keep
class DebuggingPreferencesFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = this.context ?: return

        val preferenceManager: PreferenceManager = preferenceManager
        preferenceManager.setPreferenceDataStore(DebuggingPreferencesDataStore.getInstance(context))

        setPreferencesFromResource(R.xml.termux_debugging_preferences, rootKey)

        configureLoggingPreferences(context)
        configureBackupPreferences(context)
    }

    private fun configureBackupPreferences(context: Context) {
        val backup = findPreference<Preference>("backup_restore") ?: return

        backup.setOnPreferenceClickListener {
            startActivity(Intent(context, BackupActivity::class.java))
            true
        }
    }

    private fun configureLoggingPreferences(context: Context) {
        val loggingCategory = findPreference<PreferenceCategory>("logging") ?: return

        val logLevelListPreference = findPreference<ListPreference>("log_level")
        if (logLevelListPreference != null) {
            val preferences = TermuxAppSharedPreferences.build(context, true) ?: return

            setLogLevelListPreferenceData(logLevelListPreference, context, preferences.getLogLevel())
            loggingCategory.addPreference(logLevelListPreference)
        }
    }

    companion object {
        @JvmStatic
        fun setLogLevelListPreferenceData(
            logLevelListPreference: ListPreference?,
            context: Context,
            logLevel: Int,
        ): ListPreference {
            val preference = logLevelListPreference ?: ListPreference(context)

            val logLevels: Array<CharSequence> = Logger.getLogLevelsArray()
            val logLevelLabels: Array<CharSequence> = Logger.getLogLevelLabelsArray(context, logLevels, true)

            preference.setEntryValues(logLevels)
            preference.setEntries(logLevelLabels)

            preference.setValue(logLevel.toString())
            preference.setDefaultValue(Logger.DEFAULT_LOG_LEVEL)

            return preference
        }
    }

}

class DebuggingPreferencesDataStore private constructor(context: Context) : PreferenceDataStore() {

    private val mContext: Context = context
    private val mPreferences: TermuxAppSharedPreferences? = TermuxAppSharedPreferences.build(context, true)

    companion object {
        private var mInstance: DebuggingPreferencesDataStore? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): DebuggingPreferencesDataStore {
            if (mInstance == null) {
                mInstance = DebuggingPreferencesDataStore(context)
            }
            return mInstance!!
        }
    }

    override fun getString(key: String?, defValue: String?): String? {
        val preferences = mPreferences ?: return null
        if (key == null) return null

        return when (key) {
            "log_level" -> preferences.getLogLevel().toString()
            else -> null
        }
    }

    override fun putString(key: String?, value: String?) {
        val preferences = mPreferences ?: return
        if (key == null) return

        when (key) {
            "log_level" -> {
                if (value != null) {
                    preferences.setLogLevel(mContext, value.toInt())
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
                preferences.setTerminalViewKeyLoggingEnabled(value)
            "plugin_error_notifications_enabled" ->
                preferences.setPluginErrorNotificationsEnabled(value)
            "crash_report_notifications_enabled" ->
                preferences.setCrashReportNotificationsEnabled(value)
            else -> {}
        }
    }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        val preferences = mPreferences ?: return false

        return when (key) {
            "terminal_view_key_logging_enabled" ->
                preferences.isTerminalViewKeyLoggingEnabled()
            "plugin_error_notifications_enabled" ->
                preferences.arePluginErrorNotificationsEnabled(false)
            "crash_report_notifications_enabled" ->
                preferences.areCrashReportNotificationsEnabled(false)
            else -> false
        }
    }

}
