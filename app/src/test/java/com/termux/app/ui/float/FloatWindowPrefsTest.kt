package com.termux.app.ui.float

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

/** Unit tests for the app-local persistence of the float window position and size. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class FloatWindowPrefsTest {

    private fun prefs(): FloatWindowPrefs = FloatWindowPrefs(
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("termux_float_window", Context.MODE_PRIVATE),
    )

    @Test
    fun `a fresh read returns the default state and a written state round-trips`() {
        val prefs = prefs()

        assertEquals(FloatWindowState.DEFAULT, prefs.read())

        prefs.write(FloatWindowState(1, 2, 3, 4))
        assertEquals(FloatWindowState(1, 2, 3, 4), prefs.read())
    }
}
