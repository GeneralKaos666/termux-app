package com.termux.app.fragments.settings

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import androidx.annotation.Keep
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.termux.R
import com.termux.shared.termux.settings.preferences.TermuxAPIAppSharedPreferences

@Keep
class TermuxAPIPreferencesFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = this.context ?: return

        val preferenceManager: PreferenceManager = preferenceManager
        preferenceManager.setPreferenceDataStore(TermuxAPIPreferencesDataStore.getInstance(context))

        setPreferencesFromResource(R.xml.termux_api_preferences, rootKey)
    }

}

@SuppressLint("StaticFieldLeak")
class TermuxAPIPreferencesDataStore private constructor(context: Context) : PreferenceDataStore() {

    // Holds the application context: the store is a process-wide singleton, so an Activity here
    // would leak. Lint only sees the field type, which is why the class suppresses its check.
    private val mContext: Context = context
    private val mPreferences: TermuxAPIAppSharedPreferences? = TermuxAPIAppSharedPreferences.build(context, true)

    companion object {
        private var mInstance: TermuxAPIPreferencesDataStore? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): TermuxAPIPreferencesDataStore {
            if (mInstance == null) {
                mInstance = TermuxAPIPreferencesDataStore(context.applicationContext)
            }
            return mInstance!!
        }
    }

}
