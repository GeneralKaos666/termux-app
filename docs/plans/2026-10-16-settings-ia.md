# Settings IA Rework Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reorganise the `Settings → Termux` preference tree from three module-named screens (Debugging / Terminal I/O / Terminal View) into three task-named screens (Display / Keyboard / Advanced), without changing any persisted preference key.

**Architecture:** Pure XML + fragment regrouping. Each sub-screen keeps its own `PreferenceFragmentCompat` + package-private `PreferenceDataStore`; we rename the fragments that own no external references, move leaf entries between XML files, and repoint `termux_preferences.xml`. Leaf `app:key` values are copied verbatim, so nothing is migrated. The Termux `DebuggingPreferencesFragment` class is deliberately **not** renamed because four plugin fragments call its static `setLogLevelListPreferenceData`.

**Tech Stack:** Android `PreferenceFragmentCompat` / `PreferenceDataStore`, XML preference resources, JUnit 4 + Robolectric 4.10 (already on the `:app` test classpath), Gradle.

**Spec:** [`docs/superpowers/specs/2026-10-16-settings-ia-design.md`](../specs/2026-10-16-settings-ia-design.md)

## Global Constraints

- Leaf `app:key` values are persistence keys. The 13 Termux leaf keys must never be renamed, added or removed:
  `appearance`, `terminal_background`, `terminal_background_choose_image`, `terminal_background_dim`,
  `terminal_margin_adjustment`, `soft_keyboard_enabled`, `soft_keyboard_enabled_only_if_no_hardware`,
  `extra_keys_preset`, `extra_keys_preset_note`, `log_level`, `terminal_view_key_logging_enabled`,
  `plugin_error_notifications_enabled`, `crash_report_notifications_enabled`.
- These strings are shared with plugin-app XMLs and must keep existing with these values:
  `termux_debugging_preferences_title` ("Debugging"), `termux_debugging_preferences_summary`,
  `termux_logging_header` ("Logging"), `termux_log_level_title` ("Log Level"). Do not rename or delete them.
- Keep `com.termux.app.fragments.settings.termux.DebuggingPreferencesFragment` (and its public static
  `setLogLevelListPreferenceData`) exactly as named — four plugin fragments call it.
- Do not touch `:termux-shared`, `:terminal-emulator`, `:terminal-view`, `app/src/main/AndroidManifest.xml`,
  or `app/src/main/res/xml/root_preferences.xml`.
- Keep the `@Keep` annotation on every renamed fragment class (the framework instantiates them by FQN).
- Style: 4-space indentation, LF line endings; XML keeps the existing element attribute order.
- No formatter/lint gate in CI; the gate is `./gradlew :app:testDebugUnitTest` and `./gradlew :app:assembleDebug`.
- Commit messages: Conventional Commits with **capitalized** types, GPG-signed (`git commit -S`). Unlock the
  agent first with `sh "$HOME/.config/opencode/skills/gpg-commit/scripts/unlock-gpg.sh"`; verify with
  `git log -1 --show-signature --format='%h %G?'` (expect `%G? = G`).

## Review Focus

Five failure modes the spec implies but ordinary "does it build" checks miss. Each is pinned by a test named
next to it, added in the task that owns the code.

1. **An in-place app upgrade must keep every preference.** A leaf key renamed or dropped silently resets that
   setting for existing users. → `every termux leaf key appears exactly once` (Task 4), plus the per-store
   round-trips (Tasks 1–3).
2. **The four plugin apps' Settings sections must keep rendering.** Deleting or renaming the shared
   `Debugging`/`Logging` strings breaks `termux_{api,float,tasker,widget}_preferences.xml` and
   `*_debugging_preferences.xml` at build time. → `plugin settings xml keeps referencing the shared termux
   strings` (Task 1; must stay green through Task 4).
3. **The extra-keys preset must keep persisting after it moves from Terminal View to Keyboard.** Dropping the
   `extra_keys_preset` case in the move means the control silently stops saving. →
   `keyboard store round-trips its leaves` (Task 1) and
   `display store round-trips its leaves and no longer owns the extra keys preset` (Task 2).
4. **A leaf must not end up on two screens, nor vanish mid-move.** → per-screen leaf-set tests
   (`display screen has exactly its expected leaves` Task 2, `keyboard screen has exactly its expected leaves`
   Task 1) and the exactly-once union in Task 4.
5. **A hub entry mis-wired to the wrong fragment leaves a screen unreachable.** →
   `hub points the keyboard entry at the keyboard fragment` (Task 1),
   `hub points the display entry at the display fragment` (Task 2),
   `hub titles the advanced entry Advanced and keeps the debugging fragment` (Task 3), and the final
   exact-order assertion (Task 4).

---

### Task 1: Keyboard screen

Extract an additive Keyboard screen: new XML with the two soft-keyboard switches plus the extra-keys preset
and note, a renamed `KeyboardPreferencesFragment` that gains the `extra_keys_preset` wiring, and a retitled
hub entry. The old `termux_terminal_io_preferences.xml` stays on disk (orphaned) until Task 2 so no
referenced resource disappears. After this task the extra-keys preset is live on both Terminal View and
Keyboard — same persisted key, so no inconsistency.

**Files:**
- Create: `app/src/main/res/xml/termux_keyboard_preferences.xml`
- Create: `app/src/test/java/com/termux/app/settings/PreferenceXml.kt`
- Create: `app/src/test/java/com/termux/app/settings/SettingsPreferencesXmlTest.kt`
- Create: `app/src/test/java/com/termux/app/fragments/settings/termux/PreferenceDataStoresTest.kt`
- Modify (rename class + file): `app/src/main/java/com/termux/app/fragments/settings/termux/TerminalIOPreferencesFragment.java`
  → `…/KeyboardPreferencesFragment.java`
- Modify: `app/src/main/res/xml/termux_preferences.xml`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Produces: class `com.termux.app.fragments.settings.termux.KeyboardPreferencesFragment` with
  `public static KeyboardPreferencesDataStore KeyboardPreferencesDataStore.getInstance(Context context)`;
  `KeyboardPreferencesDataStore` handles keys `soft_keyboard_enabled`,
  `soft_keyboard_enabled_only_if_no_hardware` (bools) and `extra_keys_preset` (string).
- Produces (test helpers, package `com.termux.app.settings`):
  `data class PreferenceElement(tag: String, key: String?, fragment: String?, title: String?, summary: String?)`,
  `fun parsePreferenceScreen(xmlRes: Int): List<PreferenceElement>`,
  `fun leafKeysOf(xmlRes: Int): List<String>`,
  `fun resolveString(raw: String?): String?`, and `const val RES_AUTO`.

- [ ] **Step 1: Write the test helpers**

`app/src/test/java/com/termux/app/settings/PreferenceXml.kt`. `resources.getXml` returns `app:fragment` as a
literal string and `app:title` as a numeric reference like `@2131820980`, so `resolveString` must handle a
literal, `@string/name`, and `@<id>`.

```kotlin
package com.termux.app.settings

import org.robolectric.RuntimeEnvironment
import org.xmlpull.v1.XmlPullParser

const val RES_AUTO = "http://schemas.android.com/apk/res-auto"

data class PreferenceElement(
    val tag: String,
    val key: String?,
    val fragment: String?,
    val title: String?,
    val summary: String?,
)

/** Parses a preference XML resource into its elements, in document order, excluding the root PreferenceScreen. */
fun parsePreferenceScreen(xmlRes: Int): List<PreferenceElement> {
    val parser = RuntimeEnvironment.getApplication().resources.getXml(xmlRes)
    val elements = mutableListOf<PreferenceElement>()
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG && parser.name != "PreferenceScreen") {
            elements += PreferenceElement(
                tag = parser.name,
                key = parser.getAttributeValue(RES_AUTO, "key"),
                fragment = parser.getAttributeValue(RES_AUTO, "fragment"),
                title = parser.getAttributeValue(RES_AUTO, "title"),
                summary = parser.getAttributeValue(RES_AUTO, "summary"),
            )
        }
        event = parser.next()
    }
    return elements
}

/** Leaf keys: every non-category element that carries an app:key. */
fun leafKeysOf(xmlRes: Int): List<String> =
    parsePreferenceScreen(xmlRes)
        .filter { it.tag != "PreferenceCategory" }
        .mapNotNull { it.key }

/** Resolves an attribute value ("literal", "@string/name" or "@2131820980") to its display text. */
fun resolveString(raw: String?): String? {
    val app = RuntimeEnvironment.getApplication()
    val res = app.resources
    if (raw == null) return null
    if (raw.startsWith("@string/")) {
        val id = res.getIdentifier(raw.removePrefix("@string/"), "string", app.packageName)
        return if (id != 0) res.getString(id) else null
    }
    if (raw.startsWith("@")) {
        val id = raw.removePrefix("@").toIntOrNull() ?: return raw
        return runCatching { res.getString(id) }.getOrNull()
    }
    return raw
}
```

- [ ] **Step 2: Write the failing XML tests**

`app/src/test/java/com/termux/app/settings/SettingsPreferencesXmlTest.kt`. These reference
`R.xml.termux_keyboard_preferences`, which does not exist yet, so the class will not compile — that is the red.

```kotlin
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
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.settings.SettingsPreferencesXmlTest"`
Expected: compilation failure — `Unresolved reference: termux_keyboard_preferences`.

- [ ] **Step 4: Add the Keyboard strings**

In `app/src/main/res/values/strings.xml`, inside the `Termux App Preferences` block, add (leave the existing
`termux_keyboard_header` string in place — the not-yet-deleted `termux_terminal_io_preferences.xml` still uses it):

```xml
<string name="termux_keyboard_preferences_title">Keyboard</string>
<string name="termux_keyboard_preferences_summary">On-screen keyboard and extra keys</string>
<string name="termux_keyboard_soft_keyboard_header">Soft keyboard</string>
<string name="termux_keyboard_extra_keys_header">Extra keys</string>
```

- [ ] **Step 5: Create `app/src/main/res/xml/termux_keyboard_preferences.xml`**

```xml
<PreferenceScreen xmlns:app="http://schemas.android.com/apk/res-auto">

    <PreferenceCategory
        app:key="soft_keyboard"
        app:title="@string/termux_keyboard_soft_keyboard_header">

        <SwitchPreferenceCompat
            app:key="soft_keyboard_enabled"
            app:summaryOff="@string/termux_soft_keyboard_enabled_off"
            app:summaryOn="@string/termux_soft_keyboard_enabled_on"
            app:title="@string/termux_soft_keyboard_enabled_title" />

        <SwitchPreferenceCompat
            app:key="soft_keyboard_enabled_only_if_no_hardware"
            app:summaryOff="@string/termux_soft_keyboard_enabled_only_if_no_hardware_off"
            app:summaryOn="@string/termux_soft_keyboard_enabled_only_if_no_hardware_on"
            app:title="@string/termux_soft_keyboard_enabled_only_if_no_hardware_title" />

    </PreferenceCategory>

    <PreferenceCategory
        app:key="extra_keys"
        app:title="@string/termux_keyboard_extra_keys_header">

        <ListPreference
            app:key="extra_keys_preset"
            app:title="@string/termux_terminal_view_extra_keys_preset_title"
            app:defaultValue="default" />

        <Preference
            app:key="extra_keys_preset_note"
            app:title="@string/termux_terminal_view_extra_keys_preset_note_title"
            app:summary="@string/termux_terminal_view_extra_keys_preset_note_summary"
            app:selectable="false" />

    </PreferenceCategory>

</PreferenceScreen>
```

- [ ] **Step 6: Rename the fragment and move the extra-keys wiring**

In `app/src/main/java/com/termux/app/fragments/settings/termux/`:

1. Rename the file `TerminalIOPreferencesFragment.java` → `KeyboardPreferencesFragment.java`.
2. Rename `class TerminalIOPreferencesFragment` → `class KeyboardPreferencesFragment` and
   `class TerminalIOPreferencesDataStore` → `class KeyboardPreferencesDataStore` (including the struct field,
   constructor, and `getInstance` return type).
3. Point it at the new XML: `setPreferencesFromResource(R.xml.termux_keyboard_preferences, rootKey);` and use
   `KeyboardPreferencesDataStore.getInstance(context)`.
4. Add the extra-keys preset setup to `onCreatePreferences`, copied verbatim from
   `TerminalViewPreferencesFragment.java` lines 68–82 (the `ListPreference` `extraKeysPreset` block), and add
   `import androidx.preference.ListPreference;` plus
   `import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants.TERMUX_APP;`.
5. Add the moved case to the DataStore, copied verbatim from `TerminalViewPreferencesDataStore`'s
   `putString`/`getString`:
   - `putString`: `case "extra_keys_preset": mPreferences.setExtraKeysPreset(value); break;`
   - `getString`: `case "extra_keys_preset": return mPreferences.getExtraKeysPreset();`

Do not yet remove anything from `TerminalViewPreferencesFragment.java` (Task 2 does that).

- [ ] **Step 7: Repoint the hub's second entry**

In `app/src/main/res/xml/termux_preferences.xml`, change the middle entry (currently
`termux_terminal_io_preferences_title` / `_summary` → `TerminalIOPreferencesFragment`) to:

```xml
<Preference
    app:title="@string/termux_keyboard_preferences_title"
    app:summary="@string/termux_keyboard_preferences_summary"
    app:fragment="com.termux.app.fragments.settings.termux.KeyboardPreferencesFragment"/>
```

Then delete the now-unused strings `termux_terminal_io_preferences_title` and
`termux_terminal_io_preferences_summary`.

- [ ] **Step 8: Run the XML tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.settings.SettingsPreferencesXmlTest"`
Expected: PASS (3 tests).

- [ ] **Step 9: Write the DataStore round-trip test**

`app/src/test/java/com/termux/app/fragments/settings/termux/PreferenceDataStoresTest.kt`. The store caches a
`static mInstance`, so reset it before each test; assert only round-trips (put then get), never defaults,
since the cached SharedPreferences may carry values across methods.

```kotlin
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
        val field = KeyboardPreferencesDataStore::class.java.getDeclaredField("mInstance")
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
    }
}
```

- [ ] **Step 10: Run the DataStore test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.fragments.settings.termux.PreferenceDataStoresTest"`
Expected: PASS (1 test).

- [ ] **Step 11: Run the whole `:app` suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 12: Commit**

```bash
sh "$HOME/.config/opencode/skills/gpg-commit/scripts/unlock-gpg.sh"
git add app/src/main/res/xml/termux_keyboard_preferences.xml \
        app/src/main/res/xml/termux_preferences.xml \
        app/src/main/res/values/strings.xml \
        app/src/main/java/com/termux/app/fragments/settings/termux/KeyboardPreferencesFragment.java \
        app/src/test/java/com/termux/app/settings/PreferenceXml.kt \
        app/src/test/java/com/termux/app/settings/SettingsPreferencesXmlTest.kt \
        app/src/test/java/com/termux/app/fragments/settings/termux/PreferenceDataStoresTest.kt
git rm app/src/main/java/com/termux/app/fragments/settings/termux/TerminalIOPreferencesFragment.java
git commit -S -m "Changed(app): Give the keyboard and extra keys their own settings screen"
```

---

### Task 2: Display screen

Rename Terminal View → Display, trim it to Style/Background/Layout, delete its `extra_keys_preset` wiring, and
delete the two superseded XML files. Safe to delete `termux_terminal_io_preferences.xml` — Task 1 already
repointed its only loader. Deletion of both files frees the header strings removed here.

**Files:**
- Create: `app/src/main/res/xml/termux_display_preferences.xml`
- Delete: `app/src/main/res/xml/termux_terminal_view_preferences.xml`, `app/src/main/res/xml/termux_terminal_io_preferences.xml`
- Modify (rename class + file): `app/src/main/java/com/termux/app/fragments/settings/termux/TerminalViewPreferencesFragment.java`
  → `…/DisplayPreferencesFragment.java`
- Modify: `app/src/main/res/xml/termux_preferences.xml`, `app/src/main/res/values/strings.xml`
- Modify: `app/src/test/java/com/termux/app/settings/SettingsPreferencesXmlTest.kt`,
  `app/src/test/java/com/termux/app/fragments/settings/termux/PreferenceDataStoresTest.kt`

**Interfaces:**
- Consumes: `leafKeysOf` / `parsePreferenceScreen` / `resolveString` from Task 1.
- Produces: class `com.termux.app.fragments.settings.termux.DisplayPreferencesFragment` with
  `public static DisplayPreferencesDataStore DisplayPreferencesDataStore.getInstance(Context context)`;
  `DisplayPreferencesDataStore` handles `terminal_margin_adjustment` (bool), `terminal_background` (string),
  `terminal_background_dim` (int) — and no longer `extra_keys_preset`.

- [ ] **Step 1: Write the failing Display tests**

Append to `SettingsPreferencesXmlTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.settings.SettingsPreferencesXmlTest"`
Expected: compilation failure — `Unresolved reference: termux_display_preferences`.

- [ ] **Step 3: Add the Display strings**

In `app/src/main/res/values/strings.xml`, inside the `Termux App Preferences` block, add:

```xml
<string name="termux_display_preferences_title">Display</string>
<string name="termux_display_preferences_summary">Colours, font, background and layout</string>
<string name="termux_display_style_header">Style</string>
<string name="termux_display_background_header">Background</string>
<string name="termux_display_layout_header">Layout</string>
```

Do not delete the old `termux_terminal_view_*` strings here — the old XML still references them until Step 6.

- [ ] **Step 4: Create `app/src/main/res/xml/termux_display_preferences.xml`**

```xml
<PreferenceScreen xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto">

    <PreferenceCategory
        app:key="style"
        app:title="@string/termux_display_style_header">

        <Preference
            app:key="appearance"
            app:title="@string/termux_terminal_view_appearance_title"
            app:summary="@string/termux_terminal_view_appearance_summary" />

    </PreferenceCategory>

    <PreferenceCategory
        app:key="background"
        app:title="@string/termux_display_background_header">

        <ListPreference
            app:key="terminal_background"
            app:title="@string/termux_terminal_view_terminal_background_title"
            app:defaultValue="solid" />

        <Preference
            app:key="terminal_background_choose_image"
            app:title="@string/termux_terminal_view_terminal_background_choose_image_title"
            app:summary="@string/termux_terminal_view_terminal_background_choose_image_summary" />

        <SeekBarPreference
            app:key="terminal_background_dim"
            app:title="@string/termux_terminal_view_terminal_background_dim_title"
            app:summary="@string/termux_terminal_view_terminal_background_dim_summary"
            app:defaultValue="60"
            app:min="0"
            android:max="90"
            app:showSeekBarValue="true"
            app:adjustable="true" />

    </PreferenceCategory>

    <PreferenceCategory
        app:key="layout"
        app:title="@string/termux_display_layout_header">

        <SwitchPreferenceCompat
            app:key="terminal_margin_adjustment"
            app:summaryOff="@string/termux_terminal_view_terminal_margin_adjustment_off"
            app:summaryOn="@string/termux_terminal_view_terminal_margin_adjustment_on"
            app:title="@string/termux_terminal_view_terminal_margin_adjustment_title" />

    </PreferenceCategory>

</PreferenceScreen>
```

- [ ] **Step 5: Rename the fragment and drop the extra-keys wiring**

In `app/src/main/java/com/termux/app/fragments/settings/termux/`:

1. Rename the file `TerminalViewPreferencesFragment.java` → `DisplayPreferencesFragment.java`.
2. Rename `class TerminalViewPreferencesFragment` → `class DisplayPreferencesFragment` and
   `class TerminalViewPreferencesDataStore` → `class DisplayPreferencesDataStore` (field, constructor,
   `getInstance` return type, and the `mInstance` field type).
3. Point it at the new XML: `setPreferencesFromResource(R.xml.termux_display_preferences, rootKey);` and use
   `DisplayPreferencesDataStore.getInstance(context)`.
4. Delete the whole `ListPreference extraKeysPreset = findPreference("extra_keys_preset"); if (...) { … }`
   block (now in the Keyboard fragment).
5. Delete `case "extra_keys_preset":` from both `putString` and `getString` in the DataStore.
6. Update the log-tag string in `copyImageToAppStorage`'s error path from `"TerminalViewPreferencesFragment"`
   to `"DisplayPreferencesFragment"`.

Everything else — the `appearance` click listener, background entries/values, choose-image visibility and
picker, and `terminal_background_dim` — stays byte-for-byte.

- [ ] **Step 6: Repoint the hub's third entry and delete the superseded XML**

In `app/src/main/res/xml/termux_preferences.xml`, change the last entry to:

```xml
<Preference
    app:title="@string/termux_display_preferences_title"
    app:summary="@string/termux_display_preferences_summary"
    app:fragment="com.termux.app.fragments.settings.termux.DisplayPreferencesFragment"/>
```

Then delete the two files:

```bash
git rm app/src/main/res/xml/termux_terminal_view_preferences.xml app/src/main/res/xml/termux_terminal_io_preferences.xml
```

Now that nothing references them, delete these strings from `app/src/main/res/values/strings.xml`:
`termux_terminal_view_preferences_title`, `termux_terminal_view_preferences_summary`,
`termux_terminal_view_view_header`, `termux_terminal_view_background_header`,
`termux_terminal_view_extra_keys_header`, `termux_keyboard_header`.

- [ ] **Step 7: Add the "store no longer owns it" test**

Append to `PreferenceDataStoresTest.kt` (and reset `DisplayPreferencesDataStore.mInstance` in the existing
`@Before` too):

```kotlin
    @Test
    fun `display store round-trips its leaves and no longer owns the extra keys preset`() {
        val store = DisplayPreferencesDataStore.getInstance(context)
        store.putBoolean("terminal_margin_adjustment", false)
        assertEquals(false, store.getBoolean("terminal_margin_adjustment", true))
        store.putString("terminal_background", "wallpaper")
        assertEquals("wallpaper", store.getString("terminal_background", "solid"))
        store.putInt("terminal_background_dim", 42)
        assertEquals(42, store.getInt("terminal_background_dim", 60))
        assertEquals("fallback", store.getString("extra_keys_preset", "fallback"))
    }
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.settings.SettingsPreferencesXmlTest" --tests "com.termux.app.fragments.settings.termux.PreferenceDataStoresTest"`
Expected: PASS.

- [ ] **Step 9: Run the whole `:app` suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/res/xml/termux_display_preferences.xml \
        app/src/main/res/xml/termux_preferences.xml \
        app/src/main/res/values/strings.xml \
        app/src/main/java/com/termux/app/fragments/settings/termux/DisplayPreferencesFragment.java \
        app/src/test/java/com/termux/app/settings/SettingsPreferencesXmlTest.kt \
        app/src/test/java/com/termux/app/fragments/settings/termux/PreferenceDataStoresTest.kt
git rm app/src/main/java/com/termux/app/fragments/settings/termux/TerminalViewPreferencesFragment.java
git commit -S -m "Changed(app): Move style, background and layout onto a Display screen"
```

---

### Task 3: Advanced screen

Split the Debugging screen into Logging and Notifications and retitle the hub entry to "Advanced". The
fragment class keeps its name (Global Constraints); only its XML and the hub titles change.

**Files:**
- Modify: `app/src/main/res/xml/termux_debugging_preferences.xml`
- Modify: `app/src/main/res/xml/termux_preferences.xml`, `app/src/main/res/values/strings.xml`
- Modify: `app/src/test/java/com/termux/app/settings/SettingsPreferencesXmlTest.kt`,
  `app/src/test/java/com/termux/app/fragments/settings/termux/PreferenceDataStoresTest.kt`

**Interfaces:**
- Consumes: `parsePreferenceScreen` / `resolveString` / `leafKeysOf` from Task 1; `DebuggingPreferencesDataStore`
  (unchanged, already the owner of all four Advanced leaves).

- [ ] **Step 1: Write the failing Advanced tests**

Append to `SettingsPreferencesXmlTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.settings.SettingsPreferencesXmlTest"`
Expected: FAIL — no `Notifications` category, and the advanced entry's title resolves to "Debugging".

- [ ] **Step 3: Add the Advanced strings**

In `app/src/main/res/values/strings.xml`, inside the `Termux App Preferences` block, add:

```xml
<string name="termux_advanced_preferences_title">Advanced</string>
<string name="termux_advanced_preferences_summary">Logging, notifications and diagnostics</string>
<string name="termux_notifications_header">Notifications</string>
```

Keep `termux_logging_header` and `termux_debugging_preferences_title`/`_summary` — plugin XMLs still use them.

- [ ] **Step 4: Split the Advanced XML**

Replace `app/src/main/res/xml/termux_debugging_preferences.xml` with the two categories, moving the two
notification switches out of `logging` into a new `notifications` category:

```xml
<PreferenceScreen xmlns:app="http://schemas.android.com/apk/res-auto">

    <PreferenceCategory
        app:key="logging"
        app:title="@string/termux_logging_header">

        <ListPreference
            app:defaultValue="1"
            app:key="log_level"
            app:title="@string/termux_log_level_title"
            app:useSimpleSummaryProvider="true" />

        <SwitchPreferenceCompat
            app:key="terminal_view_key_logging_enabled"
            app:summaryOff="@string/termux_terminal_view_key_logging_enabled_off"
            app:summaryOn="@string/termux_terminal_view_key_logging_enabled_on"
            app:title="@string/termux_terminal_view_key_logging_enabled_title" />

    </PreferenceCategory>

    <PreferenceCategory
        app:key="notifications"
        app:title="@string/termux_notifications_header">

        <SwitchPreferenceCompat
            app:key="plugin_error_notifications_enabled"
            app:summaryOff="@string/termux_plugin_error_notifications_enabled_off"
            app:summaryOn="@string/termux_plugin_error_notifications_enabled_on"
            app:title="@string/termux_plugin_error_notifications_enabled_title" />

        <SwitchPreferenceCompat
            app:key="crash_report_notifications_enabled"
            app:summaryOff="@string/termux_crash_report_notifications_enabled_off"
            app:summaryOn="@string/termux_crash_report_notifications_enabled_on"
            app:title="@string/termux_crash_report_notifications_enabled_title" />

    </PreferenceCategory>

</PreferenceScreen>
```

`DebuggingPreferencesFragment.configureLoggingPreferences` still finds the `logging` category and re-adds
`log_level` — no Java change is needed. Do not touch `DebuggingPreferencesFragment.java`.

- [ ] **Step 5: Retitle the hub's first entry**

In `app/src/main/res/xml/termux_preferences.xml`, change the first entry's title and summary (leave its
`app:fragment` as `…DebuggingPreferencesFragment`):

```xml
<Preference
    app:title="@string/termux_advanced_preferences_title"
    app:summary="@string/termux_advanced_preferences_summary"
    app:fragment="com.termux.app.fragments.settings.termux.DebuggingPreferencesFragment"/>
```

- [ ] **Step 6: Add the store round-trip test**

Append to `PreferenceDataStoresTest.kt` (and reset `DebuggingPreferencesDataStore.mInstance` in `@Before`):

```kotlin
    @Test
    fun `debugging store round-trips the advanced leaves`() {
        val store = DebuggingPreferencesDataStore.getInstance(context)
        store.putBoolean("terminal_view_key_logging_enabled", true)
        assertEquals(true, store.getBoolean("terminal_view_key_logging_enabled", false))
        store.putBoolean("plugin_error_notifications_enabled", false)
        assertEquals(false, store.getBoolean("plugin_error_notifications_enabled", true))
        store.putBoolean("crash_report_notifications_enabled", false)
        assertEquals(false, store.getBoolean("crash_report_notifications_enabled", true))
    }
```

`log_level` is round-tripped by that same test's last assertion. An earlier draft of this plan excluded it,
claiming `Logger.setLogLevel` was "covered by existing logger tests"; that claim was false — `:termux-shared`
has no unit-test source set — and the final review caught it. Dropping the `log_level` case from `putString`
now makes `debugging store round-trips the advanced leaves` fail at its last line.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.settings.SettingsPreferencesXmlTest" --tests "com.termux.app.fragments.settings.termux.PreferenceDataStoresTest"`
Expected: PASS.

- [ ] **Step 8: Run the whole `:app` suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/res/xml/termux_debugging_preferences.xml \
        app/src/main/res/xml/termux_preferences.xml \
        app/src/main/res/values/strings.xml \
        app/src/test/java/com/termux/app/settings/SettingsPreferencesXmlTest.kt \
        app/src/test/java/com/termux/app/fragments/settings/termux/PreferenceDataStoresTest.kt
git commit -S -m "Changed(app): Separate logging from notifications under Advanced"
```

---

### Task 4: Pin the finished information architecture

The tree is now Display / Keyboard / Advanced; this task normalises the hub to that order and locks the whole
IA with end-to-end assertions, then verifies the app builds.

**Files:**
- Modify: `app/src/main/res/xml/termux_preferences.xml`
- Modify: `app/src/test/java/com/termux/app/settings/SettingsPreferencesXmlTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 1–3.

- [ ] **Step 1: Write the failing end-to-end IA test**

Append to `SettingsPreferencesXmlTest.kt`:

```kotlin
    @Test
    fun `hub lists display, keyboard and advanced in that order`() {
        val entries = parsePreferenceScreen(R.xml.termux_preferences).filter { it.tag == "Preference" }
        assertEquals(
            listOf(
                "com.termux.app.fragments.settings.termux.DisplayPreferencesFragment",
                "com.termux.app.fragments.settings.termux.KeyboardPreferencesFragment",
                "com.termux.app.fragments.settings.termux.DebuggingPreferencesFragment",
            ),
            entries.map { it.fragment },
        )
        assertEquals(listOf("Display", "Keyboard", "Advanced"), entries.map { resolveString(it.title) })
        assertEquals(
            listOf(
                "Colours, font, background and layout",
                "On-screen keyboard and extra keys",
                "Logging, notifications and diagnostics",
            ),
            entries.map { resolveString(it.summary) },
        )
    }

    @Test
    fun `every termux leaf key appears exactly once`() {
        val all = listOf(
            R.xml.termux_display_preferences,
            R.xml.termux_keyboard_preferences,
            R.xml.termux_debugging_preferences,
        ).flatMap { leafKeysOf(it) }
        assertEquals(all.size, all.toSet().size)
        assertEquals(
            setOf(
                "appearance", "terminal_background", "terminal_background_choose_image",
                "terminal_background_dim", "terminal_margin_adjustment", "soft_keyboard_enabled",
                "soft_keyboard_enabled_only_if_no_hardware", "extra_keys_preset", "extra_keys_preset_note",
                "log_level", "terminal_view_key_logging_enabled", "plugin_error_notifications_enabled",
                "crash_report_notifications_enabled",
            ),
            all.toSet(),
        )
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.settings.SettingsPreferencesXmlTest"`
Expected: FAIL — the hub order is Advanced, Keyboard, Display.

- [ ] **Step 3: Reorder the hub entries**

In `app/src/main/res/xml/termux_preferences.xml`, order the three `Preference` children so the Display entry
is first, the Keyboard entry second, and the Advanced (`DebuggingPreferencesFragment`) entry last. Change no
attributes other than their order.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.settings.SettingsPreferencesXmlTest"`
Expected: PASS.

- [ ] **Step 5: Audit the strings file**

Confirm `app/src/main/res/values/strings.xml` no longer contains any of `termux_terminal_io_preferences_*`,
`termux_terminal_view_preferences_*`, `termux_terminal_view_view_header`,
`termux_terminal_view_background_header`, `termux_terminal_view_extra_keys_header`, `termux_keyboard_header`,
and still contains `termux_debugging_preferences_title`, `termux_debugging_preferences_summary`,
`termux_logging_header`, `termux_log_level_title`.

Run: `grep -n "termux_terminal_io_preferences\|termux_terminal_view_preferences\|termux_terminal_view_view_header\|termux_terminal_view_background_header\|termux_terminal_view_extra_keys_header\|termux_keyboard_header" app/src/main/res/values/strings.xml`
Expected: no output (exit 1).

- [ ] **Step 6: Run the whole `:app` suite and build the APK**

Run: `./gradlew :app:testDebugUnitTest && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL for both.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/res/xml/termux_preferences.xml \
        app/src/test/java/com/termux/app/settings/PreferenceXml.kt \
        app/src/test/java/com/termux/app/settings/SettingsPreferencesXmlTest.kt
git commit -S -m "Changed(app): Order the Termux settings screens by task"
```

---

## Notes for the executor

- Every task ends with a green `./gradlew :app:testDebugUnitTest`; do not start the next task on a red suite.
- Between Tasks 1 and 2 the extra-keys preset is reachable from both Terminal View and Keyboard, and
  `termux_terminal_io_preferences.xml` is an orphan. Both are resolved by Task 2; do not "fix" them early.
- The spec describes the two category-header strings moving homes as "renames"
  (`termux_terminal_view_extra_keys_header` → `termux_keyboard_extra_keys_header`,
  `termux_terminal_view_background_header` → `termux_display_background_header`). This plan adds the new
  strings and deletes the old ones in the task that removes their last user, because a true rename would
  leave the still-referenced old XML without a resource and break AAPT. The resulting string inventory is
  the same as the spec's.
- If Gradle re-downloads the four ~30 MB bootstrap zips, that is the documented `downloadBootstraps` step,
  not a fault. Do not run `./gradlew clean` (it deletes the cached zips).
- The plan's tests are structural; they do not inflate the fragments. Manual check after Task 4: open
  `Settings → Termux` and confirm three task-named entries, each opening the expected screen with the
  expected rows, and that toggling a row still persists across app restart.
