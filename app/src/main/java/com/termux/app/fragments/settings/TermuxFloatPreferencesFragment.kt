package com.termux.app.fragments.settings

import android.content.Context
import android.os.Bundle
import androidx.annotation.Keep
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.termux.R
import com.termux.shared.termux.settings.preferences.TermuxFloatAppSharedPreferences

@Keep
class TermuxFloatPreferencesFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = this.context ?: return

        val preferenceManager: PreferenceManager = preferenceManager
        preferenceManager.setPreferenceDataStore(TermuxFloatPreferencesDataStore.getInstance(context))

        setPreferencesFromResource(R.xml.termux_float_preferences, rootKey)
    }

}

class TermuxFloatPreferencesDataStore private constructor(context: Context) : PreferenceDataStore() {

    private val mContext: Context = context
    private val mPreferences: TermuxFloatAppSharedPreferences?

    init {
        // Do not exit the app when com.termux.window is absent: the float is now in-app, so fall
        // back to a null backing rather than terminating.
        mPreferences = TermuxFloatAppSharedPreferences.build(context, false)
    }

    companion object {
        private var mInstance: TermuxFloatPreferencesDataStore? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): TermuxFloatPreferencesDataStore {
            if (mInstance == null) {
                mInstance = TermuxFloatPreferencesDataStore(context.applicationContext)
            }
            return mInstance!!
        }
    }

}
