package com.termux.app.ui.appearance

import android.content.Context

/**
 * A monotonically increasing revision of the applied appearance, kept app-local.
 *
 * The appearance screen cannot rely on the `ACTION_RELOAD_STYLE` broadcast to refresh the live
 * terminal: the terminal activity's receiver is registered in `onStart()` and ignores the broadcast
 * while it is stopped, which is exactly the case while the full-screen editor — or Settings — sits on
 * top of it. Instead, [bumpRevision] is called after every successful apply and the terminal reloads
 * its styling whenever the stored revision differs from the one it last applied. That signal survives
 * the terminal being stopped, backgrounded, or recreated.
 */
object AppearanceStyling {

    /** Records that the appearance changed; returns the new revision. */
    @JvmStatic
    fun bumpRevision(context: Context): Long {
        val next = revision(context) + 1
        preferences(context).edit().putLong(AppearancePreferenceKeys.STYLING_REVISION, next).apply()
        return next
    }

    /** The current revision, `0` when the appearance has never been applied. */
    @JvmStatic
    fun revision(context: Context): Long =
        preferences(context).getLong(AppearancePreferenceKeys.STYLING_REVISION, 0L)

    /** Whether the stored appearance differs from the [appliedRevision] the terminal last showed. */
    @JvmStatic
    fun hasChanged(context: Context, appliedRevision: Long): Boolean =
        revision(context) != appliedRevision

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(
            AppearancePreferenceKeys.PREFERENCES_FILE,
            Context.MODE_PRIVATE,
        )
}
