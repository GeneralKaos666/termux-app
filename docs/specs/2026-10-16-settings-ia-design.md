# Settings IA rework — design spec

**Date:** 2026-10-16
**Status:** awaiting user review (pre-planning)
**Origin:** user vision — "the same Termux underneath, just with a much better UI/UX…". Milestone C of
[`2026-10-10-shell-and-sessions-ux-design.md`](./2026-10-10-shell-and-sessions-ux-design.md) §7:
*"Rework the preferences IA; setup wizard"*.
**Scope:** the **Termux app preferences structure only** — the user-visible information architecture of
the `Settings → Termux` tree. The shipped first-run walkthrough (`FirstRunOverlay`) counts as the
"setup wizard" half of milestone C; **no wizard work is in this cycle**.
**Decisions taken by the user:**
- Scope is the preferences IA; categories are currently "named by module, not by task", and that is what we fix.
- **Restructure the existing XML tree** (`PreferenceFragmentCompat` + XML). No Compose rewrite of Settings.
- Depth: **regroup and move leaves** — keep sub-screens, make each one a task, moderate churn, and
  **leaf `app:key` values must not change** (they are the persistence keys).
- Taxonomy: **Approach A — Display · Keyboard · Advanced**.
- Keep the Termux debugging screen's Java class named `DebuggingPreferencesFragment` (its static helper is
  called from four plugin fragments); only its XML *contents* change.
- Do **not** tidy the root screen (plugin entries / About) in this cycle.

---

## 1. Background (what exists today, grounded in code)

The Settings UI is a three-level `PreferenceFragmentCompat` tree, all under `:app`.

**Root** — `app/src/main/res/xml/root_preferences.xml`, rendered by `SettingsActivity.RootPreferencesFragment`:
a `Termux` entry, four plugin-app entries (`termux_api`, `termux_float`, `termux_tasker`, `termux_widget`,
hidden unless installed), `about`, `donate`.

**Termux hub** — `app/src/main/res/xml/termux_preferences.xml`, rendered by
`app/src/main/java/com/termux/app/fragments/settings/TermuxPreferencesFragment.java`. Three entries, each
pointing at a sub-screen fragment:

| Entry title | Fragment | Sub-screen XML |
|---|---|---|
| Debugging | `…settings.termux.DebuggingPreferencesFragment` | `termux_debugging_preferences.xml` |
| Terminal I/O | `…settings.termux.TerminalIOPreferencesFragment` | `termux_terminal_io_preferences.xml` |
| Terminal View | `…settings.termux.TerminalViewPreferencesFragment` | `termux_terminal_view_preferences.xml` |

The names are module-shaped ("Terminal View", "Terminal I/O", "Debugging"), not task-shaped, and the leaves
are scattered by which class happened to own them — e.g. the on-screen-keyboard switches live under
"Terminal I/O" while the extra-keys preset lives under "Terminal View".

Each sub-screen fragment installs its own package-private `PreferenceDataStore` that `switch`-es on leaf
`app:key` strings and calls `TermuxAppSharedPreferences`:

- `TerminalViewPreferencesDataStore` — `terminal_margin_adjustment` (bool), `terminal_background`,
  `extra_keys_preset` (string), `terminal_background_dim` (int). Also wires the `appearance` click →
  `AppearanceActivity`, the background `ListPreference` entries/values, the choose-image visibility + picker
  (+ `syncTerminalBackground`), and the extra-keys preset entries/values.
- `TerminalIOPreferencesDataStore` — `soft_keyboard_enabled`, `soft_keyboard_enabled_only_if_no_hardware`.
- `DebuggingPreferencesDataStore` — `log_level` (string), `terminal_view_key_logging_enabled`,
  `plugin_error_notifications_enabled`, `crash_report_notifications_enabled` (bool).

**Cross-module couplings that constrain the change (verified):**

- `termux_debugging_preferences_title` / `_summary` are reused by the four plugin hub XMLs
  (`termux_{api,float,tasker,widget}_preferences.xml`) for their own "Debugging" entries → **must stay**.
- `termux_logging_header` and `termux_log_level_title` are reused by the four plugin debugging XMLs
  (`termux_{api,float,tasker,widget}_debugging_preferences.xml`) → **must stay**.
- `…settings.termux.DebuggingPreferencesFragment.setLogLevelListPreferenceData(...)` is called by the four
  plugin `DebuggingPreferencesFragment` classes → the class name (and the helper) is **frozen**.

## 2. Goals & success criteria

**Goal:** make every `Settings → Termux` category a task, not a module, without touching persistence,
without touching the plugin-app sections, and without changing leaf keys.

**Success criteria**

- `Settings → Termux` offers exactly three task-named screens: **Display** ("Colours, font, background and
  layout"), **Keyboard** ("On-screen keyboard and extra keys"), **Advanced** ("Logging, notifications and
  diagnostics").
- On-screen keyboard *and* extra-keys settings live together (both are "Keyboard").
- Background, style and layout settings live together (both are "Display").
- Logging and notifications are distinguishable inside "Advanced".
- Every leaf's persisted value is unchanged: **no leaf `app:key` is renamed, added or removed**, and no
  data migration is required. An in-place app upgrade keeps every setting.
- No change to `:termux-shared`, `:terminal-emulator` or `:terminal-view`.
- `./gradlew :app:testDebugUnitTest` and `./gradlew :app:assembleDebug` are green.

**Non-goals (this cycle)**

- No Compose/Material rewrite of Settings; no new navigation.
- No setup wizard (the shipped walkthrough stands).
- No plugin-app section changes; plugin strings stay as they are.
- No surfacing of `termux.properties`-only options in the UI.
- No night-mode toggle (still `termux.properties`).
- No renaming of persisted keys and no preference migration.

## 3. Target IA

```
Settings
└─ Termux                                 ("Preferences for Termux app")
   ├─ Display      "Colours, font, background and layout"
   │   ├─ Style        → Appearance  (opens AppearanceActivity)
   │   ├─ Background   → Background · Choose custom image · Background dim
   │   └─ Layout       → Terminal Margin Adjustment
   ├─ Keyboard     "On-screen keyboard and extra keys"
   │   ├─ Soft keyboard → Soft Keyboard Enabled · Only If No Hardware
   │   └─ Extra keys    → Preset · Custom layouts win (note)
   └─ Advanced     "Logging, notifications and diagnostics"
       ├─ Logging        → Log Level · Terminal View Key Logging
       └─ Notifications  → Plugin Error Notifications · Crash Report Notifications
```

The root screen is unchanged. Category headers are advisory grouping; the *screen* is the task unit.

## 4. Changes

### 4.1 XML resources (`app/src/main/res/xml/`)

| Action | File | Result |
|---|---|---|
| rename + trim | `termux_terminal_view_preferences.xml` → `termux_display_preferences.xml` | `Style` (`appearance`) · `Background` (`terminal_background`, `terminal_background_choose_image`, `terminal_background_dim`) · `Layout` (`terminal_margin_adjustment`) |
| new | `termux_keyboard_preferences.xml` | `Soft keyboard` (`soft_keyboard_enabled`, `soft_keyboard_enabled_only_if_no_hardware`) · `Extra keys` (`extra_keys_preset`, `extra_keys_preset_note`) |
| edit in place | `termux_debugging_preferences.xml` | split `logging` into `Logging` (`log_level`, `terminal_view_key_logging_enabled`) + `Notifications` (`plugin_error_notifications_enabled`, `crash_report_notifications_enabled`) |
| delete | `termux_terminal_io_preferences.xml` | two switches moved to Keyboard |
| edit | `termux_preferences.xml` | three entries → Display / Keyboard / Advanced (new titles + summaries, new fragment FQNs) |

Only `PreferenceCategory` keys/titles and leaf homes change. Leaf `app:key` values are copied verbatim.
`termux_debugging_preferences.xml` keeps its name because its fragment class keeps its name (§1).

### 4.2 Java (`app/src/main/java/com/termux/app/fragments/settings/termux/`)

- `TerminalViewPreferencesFragment` → **`DisplayPreferencesFragment`** (+ `DisplayPreferencesDataStore`).
  - Retains: `appearance` click → `AppearanceActivity`; `terminal_background` entries/values + change listener;
    `terminal_background_choose_image` click, image picker/`onActivityResult`, `copyImageToAppStorage`,
    `syncTerminalBackground`; `terminal_background_dim` int; `terminal_margin_adjustment` bool.
  - Removes: the `extra_keys_preset` case (both `putString`/`getString`) and its entries/values setup.
- `TerminalIOPreferencesFragment` → **`KeyboardPreferencesFragment`** (+ `KeyboardPreferencesDataStore`).
  - Retains: the two soft-keyboard booleans.
  - Gains: the `extra_keys_preset` `putString`/`getString` case and the preset entries/values setup (moved
    from the Terminal View fragment verbatim, using the same `TERMUX_APP` constants).
- `DebuggingPreferencesFragment` / `DebuggingPreferencesDataStore` — **name unchanged** (§1). Only its XML
  changes; `configureLoggingPreferences` still finds `"logging"` and adds `log_level`, which still exists.
- `TermuxPreferencesFragment` (hub) — unchanged; it has no leaf keys.
- `SettingsActivity.RootPreferencesFragment` — unchanged.

No fragment is declared in the manifest; the framework instantiates them from `app:fragment` by FQN, so the
`@Keep` annotations stay on the renamed classes.

### 4.3 Strings (`app/src/main/res/values/strings.xml`)

**Add**
- `termux_display_preferences_title` = "Display" · `termux_display_preferences_summary` = "Colours, font, background and layout"
- `termux_keyboard_preferences_title` = "Keyboard" · `termux_keyboard_preferences_summary` = "On-screen keyboard and extra keys"
- `termux_advanced_preferences_title` = "Advanced" · `termux_advanced_preferences_summary` = "Logging, notifications and diagnostics"
- `termux_display_style_header` = "Style"
- `termux_display_layout_header` = "Layout"
- `termux_keyboard_soft_keyboard_header` = "Soft keyboard"
- `termux_notifications_header` = "Notifications"

**Rename** (same value, new home; no plugin XML references these)
- `termux_terminal_view_background_header` → `termux_display_background_header` ("Background")
- `termux_terminal_view_extra_keys_header` → `termux_keyboard_extra_keys_header` ("Extra keys")

**Remove** (referenced only by the files being renamed/deleted)
- `termux_terminal_io_preferences_title`, `termux_terminal_io_preferences_summary`
- `termux_terminal_view_preferences_title`, `termux_terminal_view_preferences_summary`
- `termux_terminal_view_view_header`, `termux_keyboard_header`

**Keep unchanged, on purpose** (reused by plugin-app XMLs)
- `termux_debugging_preferences_title` / `_summary`, `termux_logging_header`, `termux_log_level_title`, and
  every leaf title/summary string.

## 5. Behaviour preservation & migration

- All 13 Termux leaf `app:key`s keep their exact strings, so `TermuxAppSharedPreferences` and the persisted
  values are untouched; an upgrade changes only which screen a leaf renders on. **No migration code.**
- Runtime behaviour is preserved one-for-one: the appearance screen still opens; the background list still
  drives the same entry values and the choose-image row's visibility; the image picker still copies the file
  and syncs the live shell; the dim seekbar, margin switch, extra-keys preset list (now under Keyboard),
  soft-keyboard switches, log-level list data and both notification switches behave as before.
- One deliberate relocation: the extra-keys preset list and its `TerminalViewPreferencesDataStore` case move
  from Display to Keyboard. Covered by tests (§6).

## 6. Tests

Nothing today inflates preference XML, and wiring `PreferenceManager` + `TermuxAppSharedPreferences` under
Robolectric is fiddly, so the new test parses the XML resources directly and asserts structure — it cannot be
flaky and it pins exactly the risk (an accidental key rename/drop or a mis-pointed hub entry).

New `app/src/test/java/com/termux/app/settings/SettingsPreferencesXmlTest.kt`
(`@RunWith(RobolectricTestRunner::class)`, JUnit 4, following `AppearanceActivityTest`):

1. **Hub entries** — `termux_preferences.xml` has exactly three `Preference` children whose `app:fragment`
   values are, in order, `…termux.DisplayPreferencesFragment`, `…termux.KeyboardPreferencesFragment`,
   `…termux.DebuggingPreferencesFragment`, and whose titles are the Display/Keyboard/Advanced strings.
2. **Per-screen leaves** — for each of `termux_display_preferences.xml`, `termux_keyboard_preferences.xml`,
   `termux_debugging_preferences.xml`, the set of leaf `app:key`s equals the expected set (Display:
   `appearance, terminal_background, terminal_background_choose_image, terminal_background_dim,
   terminal_margin_adjustment`; Keyboard: `extra_keys_preset, extra_keys_preset_note, soft_keyboard_enabled,
   soft_keyboard_enabled_only_if_no_hardware`; Advanced: `log_level, terminal_view_key_logging_enabled,
   plugin_error_notifications_enabled, crash_report_notifications_enabled`).
3. **Frozen inventory (regression guard)** — the union of leaf keys across the three screens equals a
   hard-coded 13-key list, and each key appears exactly once; the deleted
   `termux_terminal_io_preferences.xml` no longer exists.
4. **Category split** — `termux_debugging_preferences.xml` has a `Notifications` category containing the two
   notification switches, distinct from `Logging`.

`app:key`/`app:fragment` are read from the `http://schemas.android.com/apk/res-auto` namespace via
`XmlPullParser` (categories are `PreferenceCategory`, leaves are anything else with an `app:key`).
Resources are reached through `RuntimeEnvironment.getApplication().resources.getXml(...)` (Robolectric),
so no new test dependency is needed; the `@ConscryptMode(OFF)` used by `AppearanceActivityTest` is
reused.

Verification gate: `./gradlew :app:testDebugUnitTest` then `./gradlew :app:assembleDebug`.

## 7. Out of scope / follow-ups

- Any plugin-app section change.
- A Compose/Material 3 Settings rewrite.
