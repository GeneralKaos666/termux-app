package com.termux.app.fragments.settings

import android.content.Context
import android.os.Bundle
import androidx.annotation.Keep
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.termux.R
import com.termux.shared.termux.settings.preferences.TermuxWidgetAppSharedPreferences

@Keep
class TermuxWidgetPreferencesFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = this.context ?: return

        val preferenceManager: PreferenceManager = preferenceManager
        preferenceManager.setPreferenceDataStore(TermuxWidgetPreferencesDataStore.getInstance(context))

        setPreferencesFromResource(R.xml.termux_widget_preferences, rootKey)
    }

}

class TermuxWidgetPreferencesDataStore private constructor(context: Context) : PreferenceDataStore() {

    private val mContext: Context = context
    private val mPreferences: TermuxWidgetAppSharedPreferences? = TermuxWidgetAppSharedPreferences.build(context, true)

    companion object {
        private var mInstance: TermuxWidgetPreferencesDataStore? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): TermuxWidgetPreferencesDataStore {
            if (mInstance == null) {
                mInstance = TermuxWidgetPreferencesDataStore(context)
            }
            return mInstance!!
        }
    }

}
