package com.termux.app

import android.content.Intent
import android.content.IntentFilter
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY

/** Actions sent by this app to its own activity; they must never cross the app boundary. */
internal fun internalActivityFilter(): IntentFilter = IntentFilter().apply {
    addAction(TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH)
    addAction(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE)
    addAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS)
}

/** The one system broadcast this activity still listens to. */
internal fun systemActivityFilter(): IntentFilter = IntentFilter(Intent.ACTION_WALLPAPER_CHANGED)
