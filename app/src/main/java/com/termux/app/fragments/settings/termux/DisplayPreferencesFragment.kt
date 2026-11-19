package com.termux.app.fragments.settings.termux

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.annotation.Keep
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.termux.R
import com.termux.app.data.ui.AndroidUiPreferences
import com.termux.app.data.ui.UiPreferenceKeys
import com.termux.app.ui.appearance.AppearanceActivity
import com.termux.shared.logger.Logger
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences
import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants.TERMUX_APP
import java.io.File
import java.io.FileOutputStream

@Keep
class DisplayPreferencesFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = this.context ?: return

        val preferenceManager: PreferenceManager = preferenceManager
        preferenceManager.setPreferenceDataStore(DisplayPreferencesDataStore.getInstance(context))

        setPreferencesFromResource(R.xml.termux_display_preferences, rootKey)

        val snippets = findPreference<Preference>("snippets")
        if (snippets != null) {
            snippets.setOnPreferenceClickListener {
                startActivity(Intent(context, com.termux.app.ui.snippets.SnippetsActivity::class.java))
                true
            }
        }

        val appearance = findPreference<Preference>("appearance")
        if (appearance != null) {
            appearance.setOnPreferenceClickListener {
                startActivity(Intent(context, AppearanceActivity::class.java))
                true
            }
        }

        val terminalBackground = findPreference<ListPreference>("terminal_background")
        if (terminalBackground != null) {
            terminalBackground.setEntries(
                arrayOf<CharSequence>(
                    getString(R.string.termux_terminal_view_terminal_background_entry_solid),
                    getString(R.string.termux_terminal_view_terminal_background_entry_wallpaper),
                    getString(R.string.termux_terminal_view_terminal_background_entry_image),
                ),
            )
            terminalBackground.setEntryValues(
                arrayOf<CharSequence>(
                    TERMUX_APP.IVALUE_TERMINAL_BACKGROUND_SOLID,
                    TERMUX_APP.IVALUE_TERMINAL_BACKGROUND_WALLPAPER,
                    TERMUX_APP.IVALUE_TERMINAL_BACKGROUND_IMAGE,
                ),
            )
            terminalBackground.setOnPreferenceChangeListener { _, newValue ->
                updateBackgroundRowsVisibility(newValue.toString())
                true
            }
            updateBackgroundRowsVisibility(terminalBackground.value)
        }

        val chooseImage = findPreference<Preference>("terminal_background_choose_image")
        if (chooseImage != null) {
            chooseImage.setOnPreferenceClickListener {
                launchImagePicker()
                true
            }
        }
    }

    /**
     * Shows the rows that only apply to a picture background: the image picker in custom-image mode,
     * and the blur and opacity sliders in wallpaper or custom-image mode. In solid mode the picture
     * does not exist, so those rows would set a value with no visible effect.
     */
    private fun updateBackgroundRowsVisibility(mode: String?) {
        val chooseImage = findPreference<Preference>("terminal_background_choose_image")
        if (chooseImage != null)
            chooseImage.isVisible = TERMUX_APP.IVALUE_TERMINAL_BACKGROUND_IMAGE == mode

        val pictureMode = TERMUX_APP.IVALUE_TERMINAL_BACKGROUND_SOLID != mode
        for (key in arrayOf("terminal_background_blur", "terminal_background_opacity")) {
            val row = findPreference<Preference>(key)
            if (row != null) row.isVisible = pictureMode
        }
    }

    private fun launchImagePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "image/*"
        startActivityForResult(intent, REQUEST_CODE_PICK_IMAGE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE_PICK_IMAGE || resultCode != Activity.RESULT_OK || data == null)
            return

        val uri = data.data
        val context = this.context ?: return
        if (uri == null) return

        // Keep read access across reboots while the picker granted it.
        try {
            val flags = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (flags != 0)
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (ignored: Exception) {
        }

        val localPath = copyImageToAppStorage(context, uri)
        if (localPath == null) {
            Logger.showToast(context, "Failed to read the selected image", true)
            return
        }

        val preferences = TermuxAppSharedPreferences.build(context, true)!!
        preferences.setTerminalBackgroundImagePath(localPath)
        preferences.setTerminalBackground(TERMUX_APP.IVALUE_TERMINAL_BACKGROUND_IMAGE)

        val terminalBackground = findPreference<ListPreference>("terminal_background")
        if (terminalBackground != null) {
            terminalBackground.value = TERMUX_APP.IVALUE_TERMINAL_BACKGROUND_IMAGE
            updateBackgroundRowsVisibility(TERMUX_APP.IVALUE_TERMINAL_BACKGROUND_IMAGE)
        }

        // Push the new picture into the shell behind us.
        val activity = this.activity
        if (activity is com.termux.app.TermuxActivity)
            activity.syncTerminalBackground(true)
    }

    private fun copyImageToAppStorage(context: Context, uri: Uri): String? {
        try {
            val dir = File(context.filesDir, "backgrounds")
            if (!dir.exists() && !dir.mkdirs()) return null
            val outFile = File(dir, "terminal_background.jpg")
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return null
                FileOutputStream(outFile).use { out ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                    }
                }
            }
            return outFile.absolutePath
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(
                "DisplayPreferencesFragment",
                "Failed to copy the selected background image", e,
            )
            return null
        }
    }

    private companion object {
        private const val REQUEST_CODE_PICK_IMAGE = 1001
    }

}

class DisplayPreferencesDataStore private constructor(context: Context) : PreferenceDataStore() {

    private val mContext: Context = context
    private val mPreferences: TermuxAppSharedPreferences? = TermuxAppSharedPreferences.build(context, true)
    private val mUiPreferences: AndroidUiPreferences = AndroidUiPreferences(context)

    companion object {
        private var mInstance: DisplayPreferencesDataStore? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): DisplayPreferencesDataStore {
            if (mInstance == null) {
                mInstance = DisplayPreferencesDataStore(context.applicationContext)
            }
            return mInstance!!
        }
    }

    override fun putBoolean(key: String?, value: Boolean) {
        val preferences = mPreferences ?: return
        if (key == null) return

        when (key) {
            UiPreferenceKeys.LINK_ACTION_SHEET ->
                mUiPreferences.setBoolean(key, value)
            "terminal_margin_adjustment" ->
                preferences.setTerminalMarginAdjustment(value)
            else -> {}
        }
    }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        val preferences = mPreferences ?: return false

        return when (key) {
            UiPreferenceKeys.LINK_ACTION_SHEET -> mUiPreferences.getBoolean(key, defValue)
            "terminal_margin_adjustment" -> preferences.isTerminalMarginAdjustmentEnabled()
            else -> false
        }
    }

    override fun putString(key: String?, value: String?) {
        val preferences = mPreferences ?: return
        if (key == null) return

        when (key) {
            "terminal_background" ->
                preferences.setTerminalBackground(value)
            else -> {}
        }
    }

    override fun getString(key: String?, defValue: String?): String? {
        val preferences = mPreferences ?: return defValue

        return when (key) {
            "terminal_background" -> preferences.getTerminalBackground()
            else -> defValue
        }
    }

    override fun putInt(key: String?, value: Int) {
        val preferences = mPreferences ?: return
        if (key == null) return

        if ("terminal_background_dim" == key)
            preferences.setTerminalBackgroundDim(value)
        else if ("terminal_background_blur" == key)
            preferences.setTerminalBackgroundBlur(value)
        else if ("terminal_background_opacity" == key)
            preferences.setTerminalBackgroundOpacity(value)
        else if ("interface_transparency" == key)
            preferences.setInterfaceTransparency(value)
    }

    override fun getInt(key: String?, defValue: Int): Int {
        val preferences = mPreferences ?: return defValue

        if ("terminal_background_dim" == key)
            return preferences.getTerminalBackgroundDim()
        if ("terminal_background_blur" == key)
            return preferences.getTerminalBackgroundBlur()
        if ("terminal_background_opacity" == key)
            return preferences.getTerminalBackgroundOpacity()
        if ("interface_transparency" == key)
            return preferences.getInterfaceTransparency()
        return defValue
    }

}
