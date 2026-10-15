package com.termux.app.fragments.settings.termux;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import com.termux.R;
import com.termux.app.ui.appearance.AppearanceActivity;
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

        Preference appearance = findPreference("appearance");
        if (appearance != null) {
            appearance.setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(getContext(), AppearanceActivity.class));
                return true;
            });
        }

        ListPreference terminalBackground = findPreference("terminal_background");
        if (terminalBackground != null) {
            terminalBackground.setEntries(new CharSequence[] {
                getString(R.string.termux_terminal_view_terminal_background_entry_solid),
                getString(R.string.termux_terminal_view_terminal_background_entry_wallpaper),
                getString(R.string.termux_terminal_view_terminal_background_entry_image),
            });
            terminalBackground.setEntryValues(new CharSequence[] {
                TERMUX_APP.IVALUE_TERMINAL_BACKGROUND_SOLID,
                TERMUX_APP.IVALUE_TERMINAL_BACKGROUND_WALLPAPER,
                TERMUX_APP.IVALUE_TERMINAL_BACKGROUND_IMAGE,
            });
            terminalBackground.setOnPreferenceChangeListener((preference, newValue) -> {
                updateChooseImageVisibility(newValue.toString());
                return true;
            });
            updateChooseImageVisibility(terminalBackground.getValue());
        }

        androidx.preference.Preference chooseImage = findPreference("terminal_background_choose_image");
        if (chooseImage != null) {
            chooseImage.setOnPreferenceClickListener(preference -> {
                launchImagePicker();
                return true;
            });
        }

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

    private static final int REQUEST_CODE_PICK_IMAGE = 1001;

    private void updateChooseImageVisibility(String mode) {
        Preference chooseImage = findPreference("terminal_background_choose_image");
        if (chooseImage != null)
            chooseImage.setVisible(TERMUX_APP.IVALUE_TERMINAL_BACKGROUND_IMAGE.equals(mode));
    }

    private void launchImagePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_CODE_PICK_IMAGE);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CODE_PICK_IMAGE || resultCode != android.app.Activity.RESULT_OK || data == null)
            return;

        Uri uri = data.getData();
        Context context = getContext();
        if (uri == null || context == null) return;

        // Keep read access across reboots while the picker granted it.
        try {
            int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (flags != 0)
                context.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }

        String localPath = copyImageToAppStorage(context, uri);
        if (localPath == null) {
            com.termux.shared.logger.Logger.showToast(context, "Failed to read the selected image", true);
            return;
        }

        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(context, true);
        preferences.setTerminalBackgroundImagePath(localPath);
        preferences.setTerminalBackground(TERMUX_APP.IVALUE_TERMINAL_BACKGROUND_IMAGE);

        ListPreference terminalBackground = findPreference("terminal_background");
        if (terminalBackground != null) {
            terminalBackground.setValue(TERMUX_APP.IVALUE_TERMINAL_BACKGROUND_IMAGE);
            updateChooseImageVisibility(TERMUX_APP.IVALUE_TERMINAL_BACKGROUND_IMAGE);
        }

        // Push the new picture into the shell behind us.
        android.app.Activity activity = getActivity();
        if (activity instanceof com.termux.app.TermuxActivity)
            ((com.termux.app.TermuxActivity) activity).syncTerminalBackground(true);
    }

    @androidx.annotation.Nullable
    private String copyImageToAppStorage(@androidx.annotation.NonNull Context context, @androidx.annotation.NonNull Uri uri) {
        try {
            java.io.File dir = new java.io.File(context.getFilesDir(), "backgrounds");
            if (!dir.exists() && !dir.mkdirs()) return null;
            java.io.File outFile = new java.io.File(dir, "terminal_background.jpg");
            try (java.io.InputStream in = context.getContentResolver().openInputStream(uri);
                 java.io.OutputStream out = new java.io.FileOutputStream(outFile)) {
                if (in == null) return null;
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            }
            return outFile.getAbsolutePath();
        } catch (Exception e) {
            com.termux.shared.logger.Logger.logStackTraceWithMessage("TerminalViewPreferencesFragment",
                "Failed to copy the selected background image", e);
            return null;
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

        switch (key) {
            case "terminal_background":
                mPreferences.setTerminalBackground(value);
                break;
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
            case "terminal_background":
                return mPreferences.getTerminalBackground();
            case "extra_keys_preset":
                return mPreferences.getExtraKeysPreset();
            default:
                return defValue;
        }
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
