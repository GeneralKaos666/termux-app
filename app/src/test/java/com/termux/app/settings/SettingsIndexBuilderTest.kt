package com.termux.app.settings

import android.content.Context
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import com.termux.R
import com.termux.app.domain.settings.SettingsEntry
import com.termux.app.domain.settings.filterSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

/** Tests for the androidx walker that turns a preference screen into search entries. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SettingsIndexBuilderTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private fun manager() = PreferenceManager(context)

    // A preference joins the hierarchy when its parent group takes it, which is what gives it a
    // PreferenceManager; the tests therefore build raw children and add them to an attached group.
    private fun preference(
        key: String,
        title: String,
        summary: String? = null,
        fragment: String? = null,
    ) = Preference(context).apply {
        this.key = key
        this.title = title
        this.summary = summary
        this.fragment = fragment
    }

    private fun category(key: String, title: String) =
        PreferenceCategory(context).apply {
            this.key = key
            this.title = title
        }

    private fun screen(): PreferenceScreen = manager().createPreferenceScreen(context)

    @Test
    fun `walks nested screens and skips keyless headers`() {
        val root = screen()
        root.addPreference(preference("display", "Display", "Colours", "DisplayFragment"))

        val category = category("keyboard_category", "Keyboard")
        root.addPreference(category)
        category.addPreference(preference("keyboard", "Keyboard", "Extra keys", "KeyboardFragment"))

        root.addPreference(Preference(context)) // no key, no title: a header only

        assertEquals(
            listOf(
                SettingsEntry("display", "Display", "Colours", "DisplayFragment"),
                SettingsEntry("keyboard", "Keyboard", "Extra keys", "KeyboardFragment"),
            ),
            settingsIndex(root),
        )
    }

    @Test
    fun `a category with a key is not itself a result`() {
        val root = screen()
        root.addPreference(category("category", "Category"))

        assertEquals(emptyList<SettingsEntry>(), settingsIndex(root))
    }

    @Test
    fun `a missing summary becomes an empty string`() {
        val root = screen()
        root.addPreference(preference("display", "Display"))

        assertEquals(listOf(SettingsEntry("display", "Display", "", null)), settingsIndex(root))
    }

    @Test
    fun `a leaf preference is a result even without a fragment`() {
        val root = screen()
        root.addPreference(preference("about", "About", "Version"))

        assertEquals(listOf(SettingsEntry("about", "About", "Version", null)), settingsIndex(root))
    }

    @Test
    fun `the hub indexes its three navigation rows`() {
        val manager = manager()
        val root = manager.createPreferenceScreen(context)
        @Suppress("DEPRECATION")
        manager.inflateFromResource(context, R.xml.termux_preferences, root)

        assertEquals(
            listOf(
                SettingsEntry(
                    "display", "Display", "Colours, font, background and layout",
                    "com.termux.app.fragments.settings.termux.DisplayPreferencesFragment",
                ),
                SettingsEntry(
                    "keyboard", "Keyboard", "On-screen keyboard and extra keys",
                    "com.termux.app.fragments.settings.termux.KeyboardPreferencesFragment",
                ),
                SettingsEntry(
                    "advanced", "Advanced", "Logging, notifications and diagnostics",
                    "com.termux.app.fragments.settings.termux.DebuggingPreferencesFragment",
                ),
            ),
            settingsIndex(root),
        )
    }

    @Test
    fun `a query matching a hub row keeps it`() {
        val manager = manager()
        val root = manager.createPreferenceScreen(context)
        @Suppress("DEPRECATION")
        manager.inflateFromResource(context, R.xml.termux_preferences, root)
        val entries = settingsIndex(root)

        assertEquals(listOf("display"), filterSettings(entries, "font").map { it.key })
        assertEquals(listOf("display"), filterSettings(entries, "COLOUR").map { it.key })
        assertEquals(listOf("keyboard"), filterSettings(entries, "keyboard").map { it.key })
        assertEquals(emptyList<SettingsEntry>(), filterSettings(entries, "wallpaper"))
    }

    @Test
    fun `the advanced screen indexes the backup row`() {
        // The ListPreference in that screen reads its persisted value on attach, so inflate against
        // a file of this test's own instead of whatever earlier tests left in the default one.
        val preferencesName = "settings-index-builder-test"
        val manager = manager().apply { sharedPreferencesName = preferencesName }
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        val root = manager.createPreferenceScreen(context)
        @Suppress("DEPRECATION")
        manager.inflateFromResource(context, R.xml.termux_debugging_preferences, root)

        val row = settingsIndex(root).single { it.key == "backup_restore" }
        assertTrue("backup row title was '${row.title}'", row.title.contains("Backup"))
        assertNull(row.fragment)
    }
}
