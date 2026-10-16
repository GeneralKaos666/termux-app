package com.termux.app.settings

import com.termux.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SettingsPreferencesXmlTest {

    @Test
    fun `keyboard screen has exactly its expected leaves`() {
        assertEquals(
            setOf(
                "soft_keyboard_enabled",
                "soft_keyboard_enabled_only_if_no_hardware",
                "extra_keys_preset",
                "extra_keys_preset_note",
            ),
            leafKeysOf(R.xml.termux_keyboard_preferences).toSet(),
        )
    }

    @Test
    fun `hub points the keyboard entry at the keyboard fragment`() {
        val entries = parsePreferenceScreen(R.xml.termux_preferences).filter { it.tag == "Preference" }
        assertTrue(
            "no hub entry targets KeyboardPreferencesFragment",
            entries.any {
                it.fragment == "com.termux.app.fragments.settings.termux.KeyboardPreferencesFragment"
            },
        )
    }

    @Test
    fun `plugin settings xml keeps referencing the shared termux strings`() {
        val pluginXmls = listOf(
            R.xml.termux_api_preferences, R.xml.termux_float_preferences,
            R.xml.termux_tasker_preferences, R.xml.termux_widget_preferences,
            R.xml.termux_api_debugging_preferences, R.xml.termux_float_debugging_preferences,
            R.xml.termux_tasker_debugging_preferences, R.xml.termux_widget_debugging_preferences,
        )
        // app:title comes back as "@<id>", so assert the resolved text, not the "@string/name" form.
        val titles = pluginXmls.flatMap { parsePreferenceScreen(it) }.mapNotNull { resolveString(it.title) }
        assertTrue("plugin xml no longer resolves the shared 'Debugging' title", titles.contains("Debugging"))
        assertTrue("plugin xml no longer resolves the shared 'Logging' header", titles.contains("Logging"))
    }

    @Test
    fun `display screen has exactly its expected leaves`() {
        assertEquals(
            setOf(
                "appearance",
                "terminal_background",
                "terminal_background_choose_image",
                "terminal_background_dim",
                "terminal_margin_adjustment",
            ),
            leafKeysOf(R.xml.termux_display_preferences).toSet(),
        )
    }

    @Test
    fun `hub points the display entry at the display fragment`() {
        val entries = parsePreferenceScreen(R.xml.termux_preferences).filter { it.tag == "Preference" }
        assertTrue(
            "no hub entry targets DisplayPreferencesFragment",
            entries.any {
                it.fragment == "com.termux.app.fragments.settings.termux.DisplayPreferencesFragment"
            },
        )
    }

    @Test
    fun `the superseded terminal screens are gone`() {
        val res = RuntimeEnvironment.getApplication().resources
        val pkg = RuntimeEnvironment.getApplication().packageName
        for (name in listOf("termux_terminal_view_preferences", "termux_terminal_io_preferences")) {
            assertEquals("$name should have been deleted", 0, res.getIdentifier(name, "xml", pkg))
        }
    }

    @Test
    fun `advanced screen separates logging from notifications`() {
        val elements = parsePreferenceScreen(R.xml.termux_debugging_preferences)
        assertEquals(
            setOf(
                "log_level",
                "terminal_view_key_logging_enabled",
                "plugin_error_notifications_enabled",
                "crash_report_notifications_enabled",
            ),
            elements.filter { it.tag != "PreferenceCategory" }.mapNotNull { it.key }.toSet(),
        )

        val notificationsAt = elements.indexOfFirst {
            it.tag == "PreferenceCategory" && resolveString(it.title) == "Notifications"
        }
        assertTrue("no Notifications category", notificationsAt >= 0)
        val afterNotifications = elements.drop(notificationsAt + 1)
            .takeWhile { it.tag != "PreferenceCategory" }
            .mapNotNull { it.key }
        assertEquals(
            setOf("plugin_error_notifications_enabled", "crash_report_notifications_enabled"),
            afterNotifications.toSet(),
        )
    }

    @Test
    fun `hub titles the advanced entry Advanced and keeps the debugging fragment`() {
        val entries = parsePreferenceScreen(R.xml.termux_preferences).filter { it.tag == "Preference" }
        val advanced = entries.single {
            it.fragment == "com.termux.app.fragments.settings.termux.DebuggingPreferencesFragment"
        }
        assertEquals("Advanced", resolveString(advanced.title))
    }
}
