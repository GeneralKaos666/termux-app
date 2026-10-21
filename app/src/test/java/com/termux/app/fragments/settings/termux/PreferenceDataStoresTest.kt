package com.termux.app.fragments.settings.termux

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PreferenceDataStoresTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun reset() {
        clearInstance(KeyboardPreferencesDataStore::class.java)
        clearInstance(DisplayPreferencesDataStore::class.java)
        clearInstance(DebuggingPreferencesDataStore::class.java)
    }

    private fun clearInstance(type: Class<*>) {
        val field = type.getDeclaredField("mInstance")
        field.isAccessible = true
        field.set(null, null)
    }

    @Test
    fun `keyboard store round-trips its leaves`() {
        val store = KeyboardPreferencesDataStore.getInstance(context)
        store.putBoolean("soft_keyboard_enabled", false)
        assertEquals(false, store.getBoolean("soft_keyboard_enabled", true))
        store.putBoolean("soft_keyboard_enabled_only_if_no_hardware", true)
        assertEquals(true, store.getBoolean("soft_keyboard_enabled_only_if_no_hardware", false))
        store.putString("extra_keys_preset", "vim")
        assertEquals("vim", store.getString("extra_keys_preset", "default"))
        store.putString("extra_keys_page2_preset", "function")
        assertEquals("function", store.getString("extra_keys_page2_preset", "default"))
    }

    @Test
    fun `display store round-trips its leaves and no longer owns the extra keys preset`() {
        val store = DisplayPreferencesDataStore.getInstance(context)
        store.putBoolean("terminal_margin_adjustment", false)
        assertEquals(false, store.getBoolean("terminal_margin_adjustment", true))
        store.putString("terminal_background", "wallpaper")
        assertEquals("wallpaper", store.getString("terminal_background", "solid"))
        store.putInt("terminal_background_dim", 42)
        assertEquals(42, store.getInt("terminal_background_dim", 60))
        store.putInt("interface_transparency", 55)
        assertEquals(55, store.getInt("interface_transparency", 30))
        assertEquals("fallback", store.getString("extra_keys_preset", "fallback"))
    }

    @Test
    fun `debugging store round-trips the advanced leaves`() {
        val store = DebuggingPreferencesDataStore.getInstance(context)
        store.putBoolean("terminal_view_key_logging_enabled", true)
        assertEquals(true, store.getBoolean("terminal_view_key_logging_enabled", false))
        store.putBoolean("plugin_error_notifications_enabled", false)
        assertEquals(false, store.getBoolean("plugin_error_notifications_enabled", true))
        store.putBoolean("crash_report_notifications_enabled", false)
        assertEquals(false, store.getBoolean("crash_report_notifications_enabled", true))
        store.putString("log_level", "2")
        assertEquals("2", store.getString("log_level", "1"))
    }
}
