package com.termux.app.fragments.settings.termux;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import com.termux.R;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants.TERMUX_APP;

@Keep
public class TerminalViewPreferencesFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        PreferenceManager preferenceManager = getPreferenceManager();
        preferenceManager.setPreferenceDataStore(TerminalViewPreferencesDataStore.getInstance(context));

        setPreferencesFromResource(R.xml.termux_terminal_view_preferences, rootKey);

        ListPreference terminalBackground = findPreference("terminal_background");
        if (terminalBackground != null) {
            terminalBackground.setEntries(new CharSequence[] {
                getString(R.string.termux_terminal_view_terminal_background_entry_solid),
                getString(R.string.termux_terminal_view_terminal_background_entry_wallpaper),
            });
            terminalBackground.setEntryValues(new CharSequence[] {
                TERMUX_APP.IVALUE_TERMINAL_BACKGROUND_SOLID,
                TERMUX_APP.IVALUE_TERMINAL_BACKGROUND_WALLPAPER,
            });
        }
    }

}

class TerminalViewPreferencesDataStore extends PreferenceDataStore {

    private final Context mContext;
    private final TermuxAppSharedPreferences mPreferences;

    private static TerminalViewPreferencesDataStore mInstance;

    private TerminalViewPreferencesDataStore(Context context) {
        mContext = context;
        mPreferences = TermuxAppSharedPreferences.build(context, true);
    }

    public static synchronized TerminalViewPreferencesDataStore getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new TerminalViewPreferencesDataStore(context);
        }
        return mInstance;
    }



    @Override
    public void putBoolean(String key, boolean value) {
        if (mPreferences == null) return;
        if (key == null) return;

        switch (key) {
            case "terminal_margin_adjustment":
                    mPreferences.setTerminalMarginAdjustment(value);
                break;
            default:
                break;
        }
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        if (mPreferences == null) return false;

        switch (key) {
            case "terminal_margin_adjustment":
                return mPreferences.isTerminalMarginAdjustmentEnabled();
            default:
                return false;
        }
    }

    @Override
    public void putString(String key, String value) {
        if (mPreferences == null || key == null) return;

        if ("terminal_background".equals(key))
            mPreferences.setTerminalBackground(value);
    }

    @Override
    public String getString(String key, String defValue) {
        if (mPreferences == null) return defValue;

        if ("terminal_background".equals(key))
            return mPreferences.getTerminalBackground();
        return defValue;
    }

    @Override
    public void putInt(String key, int value) {
        if (mPreferences == null || key == null) return;

        if ("terminal_background_dim".equals(key))
            mPreferences.setTerminalBackgroundDim(value);
    }

    @Override
    public int getInt(String key, int defValue) {
        if (mPreferences == null) return defValue;

        if ("terminal_background_dim".equals(key))
            return mPreferences.getTerminalBackgroundDim();
        return defValue;
    }

}
