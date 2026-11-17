package com.termux.app.terminal.io

import android.annotation.SuppressLint
import android.content.Intent
import android.view.View
import com.termux.app.TermuxActivity
import com.termux.app.activities.SettingsActivity
import com.termux.app.domain.keys.KeySetPresets
import com.termux.app.terminal.TermuxTerminalSessionActivityClient
import com.termux.app.terminal.TermuxTerminalViewClient
import com.termux.app.ui.keys.ExtraKeysPage2Presets
import com.termux.shared.logger.Logger
import com.termux.shared.termux.extrakeys.ExtraKeysConstants
import com.termux.shared.termux.extrakeys.ExtraKeysInfo
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants
import com.termux.shared.termux.settings.properties.TermuxSharedProperties
import com.termux.shared.termux.terminal.io.TerminalExtraKeys
import com.termux.view.TerminalView
import org.json.JSONException

class TermuxTerminalExtraKeys(
    activity: TermuxActivity,
    terminalView: TerminalView,
    termuxTerminalViewClient: TermuxTerminalViewClient?,
    termuxTerminalSessionActivityClient: TermuxTerminalSessionActivityClient?,
) : TerminalExtraKeys(terminalView) {

    private var mExtraKeysInfo: ExtraKeysInfo? = null
    private var mExtraKeysInfoPage2: ExtraKeysInfo? = null

    @JvmField
    val mActivity: TermuxActivity = activity

    @JvmField
    val mTermuxTerminalViewClient: TermuxTerminalViewClient? = termuxTerminalViewClient

    @JvmField
    val mTermuxTerminalSessionActivityClient: TermuxTerminalSessionActivityClient? = termuxTerminalSessionActivityClient

    init {
        setExtraKeys()
    }

    /** Rebuilds the key matrix from the properties file and the Settings preset. */
    fun reloadExtraKeys() {
        setExtraKeys()
    }


    /**
     * Set the terminal extra keys and style.
     */
    private fun setExtraKeys() {
        mExtraKeysInfo = null
        mExtraKeysInfoPage2 = null

        try {
            // The mMap stores the extra key and style string values while loading properties
            // Check {@link #getExtraKeysInternalPropertyValueFromValue(String)} and
            // {@link #getExtraKeysStyleInternalPropertyValueFromValue(String)}
            var extrakeys = mActivity.getProperties().getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS, true) as String?

            // A preset selected in Settings only applies while the user has not set their own
            // extra-keys property in the properties file; the custom property always wins.
            if (mActivity.getProperties().getPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS, null, true) == null) {
                extrakeys = KeySetPresets.jsonFor(mActivity.getPreferences().getExtraKeysPreset())
            }

            var extrakeysPage2 = mActivity.getProperties().getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS_PAGE2, true) as String?

            // Same contract as the first keys page: the custom property wins over the Settings preset.
            if (mActivity.getProperties().getPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS_PAGE2, null, true) == null) {
                extrakeysPage2 = ExtraKeysPage2Presets.jsonFor(mActivity.getPreferences().getExtraKeysPage2Preset())
            }

            var extraKeysStyle = mActivity.getProperties().getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE, true) as String?

            val extraKeyDisplayMap = ExtraKeysInfo.getCharDisplayMapForStyle(extraKeysStyle)
            if (ExtraKeysConstants.EXTRA_KEY_DISPLAY_MAPS.DEFAULT_CHAR_DISPLAY == extraKeyDisplayMap && TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE != extraKeysStyle) {
                Logger.logError(TermuxSharedProperties.LOG_TAG, "The style \"" + extraKeysStyle + "\" for the key \"" + TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE + "\" is invalid. Using default style instead.")
                extraKeysStyle = TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE
            }

            mExtraKeysInfo = ExtraKeysInfo(extrakeys!!, extraKeysStyle, ExtraKeysConstants.CONTROL_CHARS_ALIASES)
            mExtraKeysInfoPage2 = ExtraKeysInfo(extrakeysPage2!!, extraKeysStyle, ExtraKeysConstants.CONTROL_CHARS_ALIASES)
        } catch (e: JSONException) {
            Logger.showToast(mActivity, "Could not load and set the \"" + TermuxPropertyConstants.KEY_EXTRA_KEYS + "\" property from the properties file: " + e.toString(), true)
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not load and set the \"" + TermuxPropertyConstants.KEY_EXTRA_KEYS + "\" property from the properties file: ", e)

            try {
                mExtraKeysInfo = ExtraKeysInfo(TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS, TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE, ExtraKeysConstants.CONTROL_CHARS_ALIASES)
                mExtraKeysInfoPage2 = ExtraKeysInfo(TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_PAGE2, TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE, ExtraKeysConstants.CONTROL_CHARS_ALIASES)
            } catch (e2: JSONException) {
                Logger.showToast(mActivity, "Can't create default extra keys", true)
                Logger.logStackTraceWithMessage(LOG_TAG, "Could create default extra keys: ", e)
                mExtraKeysInfo = null
                mExtraKeysInfoPage2 = null
            }
        }
    }

    fun getExtraKeysInfo(): ExtraKeysInfo? {
        return mExtraKeysInfo
    }

    fun getExtraKeysInfoPage2(): ExtraKeysInfo? {
        return mExtraKeysInfoPage2
    }

    @SuppressLint("RtlHardcoded")
    override fun onTerminalExtraKeyButtonClick(
        view: View,
        key: String,
        ctrlDown: Boolean,
        altDown: Boolean,
        shiftDown: Boolean,
        fnDown: Boolean,
    ) {
        if ("KEYBOARD" == key) {
            mTermuxTerminalViewClient?.onToggleSoftKeyboardRequest()
        } else if ("DRAWER" == key) {
            // The navigation drawer was removed in favour of the always-visible top sessions bar.
            // The legacy key is kept as a no-op so existing user extra-keys configs keep parsing.
        } else if ("PASTE" == key) {
            mTermuxTerminalSessionActivityClient?.onPasteTextFromClipboard(null)
        } else if ("SCROLL" == key) {
            val terminalView = mTermuxTerminalViewClient!!.getActivity().getTerminalView()
            terminalView.mEmulator?.toggleAutoScrollDisabled()
        } else if ("SETTINGS" == key) {
            // The quick-actions page was replaced by this second key grid, so its Settings shortcut
            // is now an ordinary placeable key.
            mActivity.startActivity(Intent(mActivity, SettingsActivity::class.java))
        } else {
            super.onTerminalExtraKeyButtonClick(view, key, ctrlDown, altDown, shiftDown, fnDown)
        }
    }

    companion object {
        private const val LOG_TAG = "TermuxTerminalExtraKeys"
    }
}
