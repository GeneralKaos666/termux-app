package com.termux.app.ui.appearance

import android.content.Context
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties

/**
 * The effective cursor style and blink rate the terminal uses: the app-local appearance preference
 * the screen wrote when one is set, the `termux.properties` value otherwise.
 *
 * Kept app-local so `:termux-shared` needs no change, and so the terminal read sites share a single
 * precedence rule. The property source is injected (defaulting to the shared properties) so the
 * rule can be tested without reading a real `termux.properties`.
 */
class CursorPreferences @JvmOverloads constructor(
    context: Context,
    private val propertyCursorStyle: () -> Int? = {
        TermuxAppSharedProperties.getProperties()?.getTerminalCursorStyle()
    },
    private val propertyCursorBlinkRate: () -> Int? = {
        TermuxAppSharedProperties.getProperties()?.getTerminalCursorBlinkRate()
    },
) {

    private val appearancePreferences =
        context.applicationContext.getSharedPreferences(
            AppearancePreferenceKeys.PREFERENCES_FILE,
            Context.MODE_PRIVATE,
        )

    /** The cursor style to use: the app preference when set, the property value otherwise. */
    fun cursorStyle(): Int? = cursorStyleOverride() ?: propertyCursorStyle()

    /** The app-local cursor-style override, or `null` when `termux.properties` governs. */
    fun cursorStyleOverride(): Int? =
        if (appearancePreferences.contains(AppearancePreferenceKeys.CURSOR_STYLE)) {
            appearancePreferences.getInt(AppearancePreferenceKeys.CURSOR_STYLE, CursorStyle.BLOCK.value)
        } else {
            null
        }

    /** The value `termux.properties` sets for the cursor style, or `null` when it sets none. */
    fun defaultCursorStyle(): Int? = propertyCursorStyle()

    /** Stores the cursor style, or removes the override when [style] is `null`. */
    fun setCursorStyle(style: Int?) = putPreference(AppearancePreferenceKeys.CURSOR_STYLE, style)

    /** The blink rate to use: the app preference when set, the property value otherwise. */
    fun cursorBlinkRate(): Int? = cursorBlinkRateOverride() ?: propertyCursorBlinkRate()

    /** The app-local blink-rate override, or `null` when `termux.properties` governs. */
    fun cursorBlinkRateOverride(): Int? =
        if (appearancePreferences.contains(AppearancePreferenceKeys.CURSOR_BLINK_RATE)) {
            appearancePreferences.getInt(AppearancePreferenceKeys.CURSOR_BLINK_RATE, 0)
        } else {
            null
        }

    /** Stores the blink rate, or removes the override when [rate] is `null`. */
    fun setCursorBlinkRate(rate: Int?) = putPreference(AppearancePreferenceKeys.CURSOR_BLINK_RATE, rate)

    private fun putPreference(key: String, value: Int?) {
        appearancePreferences.edit().apply {
            if (value == null) remove(key) else putInt(key, value)
        }.apply()
    }
}
