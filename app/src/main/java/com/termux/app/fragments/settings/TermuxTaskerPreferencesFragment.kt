package com.termux.app.fragments.settings

import android.content.Context
import android.os.Bundle
import androidx.annotation.Keep
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.termux.R
import com.termux.shared.termux.settings.preferences.TermuxTaskerAppSharedPreferences

@Keep
class TermuxTaskerPreferencesFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = this.context ?: return

        val preferenceManager: PreferenceManager = preferenceManager
        preferenceManager.setPreferenceDataStore(TermuxTaskerPreferencesDataStore.getInstance(context))

        setPreferencesFromResource(R.xml.termux_tasker_preferences, rootKey)
    }

}

class TermuxTaskerPreferencesDataStore private constructor(context: Context) : PreferenceDataStore() {

    private val mContext: Context = context
    private val mPreferences: TermuxTaskerAppSharedPreferences? = TermuxTaskerAppSharedPreferences.build(context, true)

    companion object {
        private var mInstance: TermuxTaskerPreferencesDataStore? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): TermuxTaskerPreferencesDataStore {
            if (mInstance == null) {
                mInstance = TermuxTaskerPreferencesDataStore(context.applicationContext)
            }
            return mInstance!!
        }
    }

}
