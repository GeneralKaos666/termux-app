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
public class KeyboardPreferencesFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        PreferenceManager preferenceManager = getPreferenceManager();
        preferenceManager.setPreferenceDataStore(KeyboardPreferencesDataStore.getInstance(context));

        setPreferencesFromResource(R.xml.termux_keyboard_preferences, rootKey);

        ListPreference extraKeysPreset = findPreference("extra_keys_preset");
        if (extraKeysPreset != null) {
            extraKeysPreset.setEntries(new CharSequence[] {
                getString(R.string.termux_terminal_view_extra_keys_preset_entry_default),
                getString(R.string.termux_terminal_view_extra_keys_preset_entry_compact),
                getString(R.string.termux_terminal_view_extra_keys_preset_entry_vim),
                getString(R.string.termux_terminal_view_extra_keys_preset_entry_emacs),
            });
            extraKeysPreset.setEntryValues(new CharSequence[] {
                TERMUX_APP.DEFAULT_IVALUE_EXTRA_KEYS_PRESET,
                "compact",
                "vim",
                "emacs",
            });
        }
    }

}

class KeyboardPreferencesDataStore extends PreferenceDataStore {

    private final Context mContext;
    private final TermuxAppSharedPreferences mPreferences;

    private static KeyboardPreferencesDataStore mInstance;

    private KeyboardPreferencesDataStore(Context context) {
        mContext = context;
        mPreferences = TermuxAppSharedPreferences.build(context, true);
    }

    public static synchronized KeyboardPreferencesDataStore getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new KeyboardPreferencesDataStore(context);
        }
        return mInstance;
    }



    @Override
    public void putBoolean(String key, boolean value) {
        if (mPreferences == null) return;
        if (key == null) return;

        switch (key) {
            case "soft_keyboard_enabled":
                    mPreferences.setSoftKeyboardEnabled(value);
                break;
            case "soft_keyboard_enabled_only_if_no_hardware":
                mPreferences.setSoftKeyboardEnabledOnlyIfNoHardware(value);
                break;
            default:
                break;
        }
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        if (mPreferences == null) return false;

        switch (key) {
            case "soft_keyboard_enabled":
                return mPreferences.isSoftKeyboardEnabled();
            case "soft_keyboard_enabled_only_if_no_hardware":
                return mPreferences.isSoftKeyboardEnabledOnlyIfNoHardware();
            default:
                return false;
        }
    }

    @Override
    public void putString(String key, String value) {
        if (mPreferences == null || key == null) return;

        switch (key) {
            case "extra_keys_preset":
                mPreferences.setExtraKeysPreset(value);
                break;
            default:
                break;
        }
    }

    @Override
    public String getString(String key, String defValue) {
        if (mPreferences == null) return defValue;

        switch (key) {
            case "extra_keys_preset":
                return mPreferences.getExtraKeysPreset();
            default:
                return defValue;
        }
    }

}
