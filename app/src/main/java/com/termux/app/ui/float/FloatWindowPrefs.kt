package com.termux.app.ui.float

import android.content.SharedPreferences

/** The position and size of the in-app floating terminal window, in pixels. */
data class FloatWindowState(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    companion object {
        /** The position and size used when no window geometry has been stored yet. */
        val DEFAULT = FloatWindowState(200, 200, 300, 420)
    }
}

/** App-local persistence for the float window's position and size, backed by [SharedPreferences]. */
class FloatWindowPrefs(
    private val prefs: SharedPreferences,
) {
    /** Reads the stored geometry, defaulting per key to [FloatWindowState.DEFAULT]. */
    fun read(): FloatWindowState = FloatWindowState(
        x = prefs.getInt(KEY_X, FloatWindowState.DEFAULT.x),
        y = prefs.getInt(KEY_Y, FloatWindowState.DEFAULT.y),
        width = prefs.getInt(KEY_WIDTH, FloatWindowState.DEFAULT.width),
        height = prefs.getInt(KEY_HEIGHT, FloatWindowState.DEFAULT.height),
    )

    /** Stores the given geometry. */
    fun write(state: FloatWindowState) {
        prefs.edit()
            .putInt(KEY_X, state.x)
            .putInt(KEY_Y, state.y)
            .putInt(KEY_WIDTH, state.width)
            .putInt(KEY_HEIGHT, state.height)
            .apply()
    }

    private companion object {
        const val KEY_X = "window_x"
        const val KEY_Y = "window_y"
        const val KEY_WIDTH = "window_width"
        const val KEY_HEIGHT = "window_height"
    }
}
