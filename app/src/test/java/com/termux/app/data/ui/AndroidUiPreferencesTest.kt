package com.termux.app.data.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class AndroidUiPreferencesTest {

    private fun prefs() = AndroidUiPreferences(RuntimeEnvironment.getApplication())

    @Test
    fun `boolean and string round trip`() {
        val prefs = prefs()
        prefs.setBoolean(UiPreferenceKeys.COMMAND_PALETTE, false)
        prefs.setString(UiPreferenceKeys.SNIPPETS, "build\tnpm run build")
        assertFalse(prefs.getBoolean(UiPreferenceKeys.COMMAND_PALETTE, true))
        assertEquals("build\tnpm run build", prefs.getString(UiPreferenceKeys.SNIPPETS, null))
    }

    @Test
    fun `missing key returns the default`() {
        val prefs = prefs()
        assertEquals(true, prefs.getBoolean(UiPreferenceKeys.TAB_SUBTITLES, true))
        assertNull(prefs.getString(UiPreferenceKeys.SNIPPETS, null))
    }

    @Test
    fun `null string clears the value`() {
        val prefs = prefs()
        prefs.setString(UiPreferenceKeys.SNIPPETS, "x")
        prefs.setString(UiPreferenceKeys.SNIPPETS, null)
        assertNull(prefs.getString(UiPreferenceKeys.SNIPPETS, null))
    }
}
