package com.termux.app.data.appearance

import android.content.Context
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences

/**
 * The Android-backed [AppearancePreferences]. Font size uses the app's existing preference; the
 * cursor settings are resolved by [CursorPreferences], which prefers an app-local override and
 * falls back to `termux.properties`.
 */
class AndroidAppearancePreferences(context: Context) : AppearancePreferences {

    private val appPreferences: TermuxAppSharedPreferences? =
        TermuxAppSharedPreferences.build(context.applicationContext)

    private val cursorPreferences = CursorPreferences(context)

    /**
     * The selectable font-size range in physical pixels, from the app's density-aware defaults
     * (index 1 is the minimum, index 2 the maximum), so the slider matches what the terminal clamps to.
     */
    val fontSizeRange: IntRange =
        TermuxAppSharedPreferences.getDefaultFontSizes(context.applicationContext).let { it[1]..it[2] }

    override fun getFontSize(): Int = appPreferences?.getFontSize() ?: DEFAULT_FONT_SIZE

    override fun setFontSize(size: Int) {
        appPreferences?.setFontSize(size)
    }

    override fun getCursorStyleOverride(): Int? = cursorPreferences.cursorStyleOverride()

    override fun getDefaultCursorStyle(): Int? = cursorPreferences.defaultCursorStyle()

    override fun setCursorStyle(style: Int?) = cursorPreferences.setCursorStyle(style)

    override fun getCursorBlinkRateOverride(): Int? = cursorPreferences.cursorBlinkRateOverride()

    override fun setCursorBlinkRate(rate: Int?) = cursorPreferences.setCursorBlinkRate(rate)

    private val appearancePreferences =
        context.applicationContext.getSharedPreferences(
            AppearancePreferenceKeys.PREFERENCES_FILE,
            Context.MODE_PRIVATE,
        )

    override fun getLigatures(): Boolean =
        appearancePreferences.getBoolean(
            AppearancePreferenceKeys.LIGATURES,
            AppearancePreferenceKeys.LIGATURES_DEFAULT,
        )

    override fun setLigatures(enabled: Boolean) {
        appearancePreferences.edit().putBoolean(AppearancePreferenceKeys.LIGATURES, enabled).apply()
    }

    private companion object {
        const val DEFAULT_FONT_SIZE = 14
    }
}
