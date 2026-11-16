package com.termux.app.data.appearance

/**
 * App-local settings the appearance screen owns.
 *
 * Font size already lives in the app's shared preferences. Cursor style and blink rate stay
 * app-local, and `termux.properties` is only the fallback: the override getters return the
 * app-local value (or `null` when unset), so the screen can represent and clear an override, while
 * [AppearancePreferences.getDefaultCursorStyle] exposes the `termux.properties` value the terminal
 * would use instead.
 */
interface AppearancePreferences {

    fun getFontSize(): Int

    fun setFontSize(size: Int)

    /** The app-local cursor-style override, or `null` when `termux.properties` governs. */
    fun getCursorStyleOverride(): Int?

    /** The `termux.properties` cursor style, or `null` when it sets none. */
    fun getDefaultCursorStyle(): Int?

    fun setCursorStyle(style: Int?)

    /** The app-local blink-rate override, or `null` when `termux.properties` governs. */
    fun getCursorBlinkRateOverride(): Int?

    fun setCursorBlinkRate(rate: Int?)

    /** Whether the terminal asks the font for ligatures. */
    fun getLigatures(): Boolean

    fun setLigatures(enabled: Boolean)
}

/**
 * Shared-preferences keys the appearance screen owns, kept app-local so `:termux-shared` needs no
 * change. The read sites on the terminal view consume these constants rather than literals.
 */
object AppearancePreferenceKeys {
    /** The app-local preferences file that holds the appearance keys. */
    const val PREFERENCES_FILE = "termux_appearance"

    const val CURSOR_STYLE = "appearance_cursor_style"
    const val CURSOR_BLINK_RATE = "appearance_cursor_blink_rate"

    /** Whether the terminal asks the font for ligatures. */
    const val LIGATURES = "appearance_ligatures"
    /** Ligatures are on unless the user turns them off. */
    const val LIGATURES_DEFAULT = true

    /** Bumped on every successful apply so the terminal can detect a change it has not shown. */
    const val STYLING_REVISION = "appearance_styling_revision"
}
