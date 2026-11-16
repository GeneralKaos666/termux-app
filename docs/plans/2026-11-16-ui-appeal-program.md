# UI Appeal Program Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the seven UI workstreams from the 2026-11-16 brainstorm — session-tab context, command palette, snippets, animated first run, keycap polish, theme gallery, and terminal polish — so the app looks alive and is easier to discover.

**Architecture:** Every feature follows the existing `:app` split: pure models and formatting in `com.termux.app.domain.*`, persistence and Android adapters in `com.termux.app.data.*`, Compose UI in `com.termux.app.ui.*`, and thin Java wiring in `TermuxActivity` through the existing state classes (`SessionsState`, `ShellChromeState`, `FirstRunState`). `:termux-shared` changes stay additive and backward compatible, because the plugin apps consume it.

**Tech Stack:** Kotlin + Jetpack Compose (Material 3), existing `TerminalView`/`TerminalSession` in `:terminal-emulator` and `:terminal-view`, Robolectric + plain JUnit for tests, Gradle 9.3.1 / AGP 9.1.1 / Kotlin 2.4.21.

**Spec:** None exists yet. This program plan records the design decisions from the chat brainstorm; each workstream below is self-contained and can be promoted to its own spec if it grows. Treat this document as the spec until that happens.

## Global Constraints

- `minSdkVersion=23`, `targetSdkVersion=28`, `compileSdkVersion=37`; toolchain values live in `gradle.properties`, never in module build files.
- Java source/target 17, core library desugaring on; do not use JDK APIs above 17.
- Package identity is frozen: `manifestPlaceholders.TERMUX_PACKAGE_NAME = "com.termux"`, `android:sharedUserId="${TERMUX_PACKAGE_NAME}"`, and every name in `TermuxConstants` that the companion apps resolve.
- `:termux-shared` is a published library. Its changes must be additive; never remove or rename public members. New members must be Java 8 compatible (no default methods) because plugin apps compile against them.
- No new Gradle modules. Put app-only code in `:app` under `domain`/`data`/`ui`.
- No `./gradlew clean` while bootstraps are cached: it deletes `app/src/main/cpp/bootstrap-*.zip` and forces a ~115 MB re-download.
- Tests: `./gradlew :app:testDebugUnitTest` for `:app`, `./gradlew :terminal-emulator:test` for the engine, `./gradlew test` before a merge. `:app` unit tests are Robolectric (`includeAndroidResources = true`).
- Commit messages: `Added(scope): ...`, `Changed(scope): ...`, `Fixed(scope): ...` — capitalized type, present tense.
- New user-visible text goes in `app/src/main/res/values/strings.xml` with a descriptive `name`; do not hardcode English in composables.
- Motion must respect `shellAnimationsEnabled()` (`ui/theme/Theme.kt`); when animations are off, every new animation is instant.
- The extra-keys grid is `ExtraKeysView` in `:termux-shared`; key visuals are drawn there, not in Compose.
- Snippets and palette actions insert text into the session; they never execute it automatically.

## Review Focus

- A background tab produces output while the activity is visible: the badge must appear only for the inactive session, must clear when that tab is selected, and must not fire on every repaint of the active tab.
- `/proc` reads for cwd and foreground command are denied, race with process exit, or contain spaces: the tab must show no subtitle and must not crash or poll per frame.
- A snippet or palette command contains spaces, quotes, or a newline: the app inserts the exact text and never appends Enter.
- An imported `.itermcolors` file is truncated, has missing ANSI entries, or channel values outside 0..1: import is rejected with a message and the current theme survives intact.
- The splash or first-run animation runs after a process restore, a rotation, or with animations disabled: it must not replay, block the first session, or delay input by more than 600 ms.

---

## Sequencing

1. **Foundation** (T1-T2) — preference store and session context. Everything else reads these.
2. **Sessions bar** (T3-T5) — context subtitles, activity badges, overflow fade, drag lift, empty prompt.
3. **Palette and snippets** (T6-T9) — palette model, overlay, action bridge, snippet store, snippet editor, chips row.
4. **First run and splash** (T10-T12) — installer stage reporting, branded splash, typed overlay.
5. **Keyboard** (T13-T14) — keycap visuals, popup chevron, layout gallery.
6. **Theming** (T15-T17) — iTerm import, gallery, import/export UI, live preview.
7. **Terminal polish** (T18-T20) — link action sheet, visual bell, themed selection toolbar.
8. **Settings** (T21) — settings search.

Each workstream ships on its own. Do not start workstream N+1 before workstream N's tests are green, because later tasks read its interfaces.

---

### Task 1: App UI preference store

**Files:**
- Create: `app/src/main/java/com/termux/app/data/ui/UiPreferences.kt`
- Create: `app/src/main/java/com/termux/app/data/ui/AndroidUiPreferences.kt`
- Test: `app/src/test/java/com/termux/app/data/ui/AndroidUiPreferencesTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `interface UiPreferences { fun getBoolean(key: String, def: Boolean): Boolean; fun setBoolean(key: String, value: Boolean); fun getString(key: String, def: String?): String?; fun setString(key: String, value: String?) }`, `class AndroidUiPreferences(context: Context) : UiPreferences` (file name `termux_ui`), and `object UiPreferenceKeys` with `TAB_SUBTITLES`, `ACTIVITY_BADGES`, `COMMAND_PALETTE`, `SNIPPETS`, `SNIPPETS_ROW`, `KEYCAP_VISUALS`, `LINK_ACTION_SHEET`, `VISUAL_BELL`, `FIRST_RUN_TIPS` — all `String`.

- [ ] **Step 1: Write the failing test**

```kotlin
@RunWith(RobolectricTestRunner::class)
class AndroidUiPreferencesTest {
    @Test fun boolean_and_string_round_trip() {
        val prefs = AndroidUiPreferences(ApplicationProvider.getApplicationContext())
        prefs.setBoolean(UiPreferenceKeys.COMMAND_PALETTE, false)
        prefs.setString(UiPreferenceKeys.SNIPPETS, "build\tnpm run build")
        assertFalse(prefs.getBoolean(UiPreferenceKeys.COMMAND_PALETTE, true))
        assertEquals("build\tnpm run build", prefs.getString(UiPreferenceKeys.SNIPPETS, null))
    }
    @Test fun missing_key_returns_default() { ... }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.data.ui.AndroidUiPreferencesTest"`
Expected: FAIL — `Unresolved reference: AndroidUiPreferences`.

- [ ] **Step 3: Implement the interface and adapter**

`AndroidUiPreferences` wraps `context.getSharedPreferences("termux_ui", Context.MODE_PRIVATE)`; `cacheDir` is not used. Values are app-private.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.data.ui.AndroidUiPreferencesTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/termux/app/data/ui app/src/test/java/com/termux/app/data/ui
git commit -m "Added(ui): Add app UI preference store"
```

---

### Task 2: Session context and unseen output

**Files:**
- Create: `app/src/main/java/com/termux/app/domain/sessions/SessionContext.kt`
- Create: `app/src/main/java/com/termux/app/domain/sessions/SessionOutputTracker.kt`
- Create: `app/src/main/java/com/termux/app/domain/sessions/ForegroundProcess.kt`
- Modify: `app/src/main/java/com/termux/app/domain/sessions/SessionItem.kt`
- Test: `app/src/test/java/com/termux/app/domain/sessions/SessionContextTest.kt`
- Test: `app/src/test/java/com/termux/app/domain/sessions/SessionOutputTrackerTest.kt`
- Test: `app/src/test/java/com/termux/app/domain/sessions/ForegroundProcessTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `fun cwdLabel(cwd: String?, home: String): String?`
  - `fun tabSubtitle(cwd: String?, home: String, command: String?): String?`
  - `fun readForegroundCommand(pid: Int, procRoot: File = File("/proc")): String?`
  - `class SessionOutputTracker { fun recordOutput(id: Int, active: Boolean); fun clear(id: Int); fun hasUnseen(id: Int): Boolean; fun remove(id: Int) }`
  - `SessionItem` gains `val subtitle: String? = null` and `val unseenOutput: Boolean = false`.

- [ ] **Step 1: Write the failing tests**

```kotlin
class SessionContextTest {
    @Test fun home_is_tilde() = assertEquals("~", cwdLabel("/home/u", "/home/u"))
    @Test fun descendant_keeps_relative_tail() = assertEquals("~/repos/app", cwdLabel("/home/u/repos/app", "/home/u"))
    @Test fun outside_home_is_basename_elided_at_24() {
        assertEquals("…/a-very-long-directory", cwdLabel("/sdcard/a-very-long-directory-name-that-overflows", "/home/u"))
    }
    @Test fun subtitle_joins_with_middle_dot_and_skips_nulls() {
        assertEquals("~/repos · npm", tabSubtitle("/home/u/repos", "/home/u", "npm"))
        assertEquals("~/repos", tabSubtitle("/home/u/repos", "/home/u", null))
        assertNull(tabSubtitle(null, "/home/u", null))
    }
}

class SessionOutputTrackerTest {
    @Test fun output_on_inactive_session_marks_unseen() { ... }
    @Test fun output_on_active_session_does_not_mark() { ... }
    @Test fun selecting_clears_and_removed_ids_are_forgotten() { ... }
}

class ForegroundProcessTest {
    @Test fun parses_comm_after_last_paren() =
        assertEquals("my (odd) shell", parseStatComm("42 (my (odd) shell) S 1 42 42 0 -1"))
    @Test fun missing_proc_returns_null() { ... }
    @Test fun pid_below_1_returns_null() { ... }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.domain.sessions.*"`
Expected: FAIL — unresolved `cwdLabel`, `SessionOutputTracker`, `parseStatComm`.

- [ ] **Step 3: Implement**

`cwdLabel`: return `"~"` for exact home; replace `home + "/"` prefix with `"~/"`; otherwise take `File(cwd).name` and, if longer than 24 chars, prefix `"…/"`. Return `null` for null/blank cwd.
`tabSubtitle`: join non-null labels with `" · "`; `null` when both are null.
`readForegroundCommand`: if `pid < 1` return `null`; read `procRoot/$pid/stat`, parse the pgrp (field 5 after the last `)`), read `procRoot/$pid/task/$pid/children`, and return the comm of the first child whose stat pgrp equals the shell pgrp; catch `IOException`/`SecurityException` and return `null`. Guard with a 100 ms cache inside the caller, not here.
`SessionOutputTracker` holds `MutableSet<Int>`; `recordOutput` adds only when `!active`; `clear`/`remove` remove.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.domain.sessions.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/termux/app/domain/sessions app/src/test/java/com/termux/app/domain/sessions
git commit -m "Added(sessions): Add tab context and unseen-output model"
```

---

### Task 3: Tab subtitles and activity dot

**Files:**
- Modify: `app/src/main/java/com/termux/app/domain/sessions/SessionItems.kt`
- Modify: `app/src/main/java/com/termux/app/ui/sessions/SessionsState.kt`
- Modify: `app/src/main/java/com/termux/app/ui/sessions/SessionsBar.kt`
- Modify: `app/src/main/java/com/termux/app/TermuxActivity.java` (`refreshSessionsBar`)
- Modify: `app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java` (`onTextChanged`)
- Test: `app/src/test/java/com/termux/app/domain/sessions/SessionItemsTest.kt`
- Test: `app/src/test/java/com/termux/app/ui/sessions/SessionsStateTest.kt`

**Interfaces:**
- Consumes: `cwdLabel`, `tabSubtitle`, `SessionOutputTracker`, `SessionItem.subtitle`, `SessionItem.unseenOutput`.
- Produces: `fun sessionItems(snapshots: List<SessionSnapshot>, ids: List<Int>, activeIndex: Int, contexts: Map<Int, String?> = emptyMap(), unseen: Set<Int> = emptySet()): List<SessionItem>`; `SessionsState.markSelected(id: Int)` and `SessionsState.unseen: Set<Int>` (Compose state).

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun contexts_and_unseen_are_projected_per_id() {
    val items = sessionItems(snapshots, listOf(7, 9), activeIndex = 0,
        contexts = mapOf(7 to "~/repos"), unseen = setOf(9))
    assertEquals("~/repos", items[0].subtitle)
    assertFalse(items[0].unseenOutput)
    assertTrue(items[1].unseenOutput)
}

@Test fun selecting_a_session_clears_its_unseen_flag() {
    val state = SessionsState()
    state.unseen = setOf(3)
    state.markSelected(3)
    assertTrue(3 !in state.unseen)
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*SessionItemsTest" --tests "*SessionsStateTest"`
Expected: FAIL — parameter `contexts` not found.

- [ ] **Step 3: Implement**

Extend `sessionItems` with defaulted params so existing callers compile. `SessionsBar.SessionTab` renders `item.subtitle` under the title at `labelSmall`, `onSurfaceVariant`, single line ellipsis, only when the tab is active or the bar is wide enough for two-line tabs. `SessionStateDot` draws a 6dp warn-colored halo when `item.unseenOutput`.
`TermuxActivity.refreshSessionsBar()` reads `TerminalSession.getCwd()` into the contexts map and passes `mSessionOutputTracker.unseen()`. `TermuxTerminalSessionActivityClient.onTextChanged` calls `tracker.recordOutput(id, active)` before its existing repaint branch; `onSessionSelected` calls `tracker.clear(id)`.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, including the pre-existing `SessionItemMappingTest`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/termux/app app/src/test/java/com/termux/app
git commit -m "Changed(sessions): Show tab context and unseen output"
```

---

### Task 4: Overflow fade and drag lift

**Files:**
- Modify: `app/src/main/java/com/termux/app/ui/sessions/SessionsBar.kt`
- Test: `app/src/test/java/com/termux/app/ui/sessions/SessionsBarLayoutTest.kt`

**Interfaces:**
- Consumes: `ShellAnimations` from `ui/theme/Motion.kt`.
- Produces: `internal fun edgeFadeAlpha(scrollFraction: Float, atStart: Boolean, atEnd: Boolean): Float` and drag elevation in `SessionTab`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun fully_scrolled_in_past_still_fades_start() {
    assertEquals(0f, edgeFadeAlpha(scrollFraction = 0f, atStart = true, atEnd = false), 0.001f)
}
@Test fun mid_scroll_fades_both_edges() { ... }
@Test fun scrolled_to_end_hides_end_fade() { ... }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*SessionsBarLayoutTest"`
Expected: FAIL — unresolved `edgeFadeAlpha`.

- [ ] **Step 3: Implement**

Add `Modifier.drawWithContent` fades at the row's leading/trailing 16dp using `edgeFadeAlpha`. On `onDragStart`, set `graphicsLayer { scaleX = 1.04f; scaleY = 1.04f; shadowElevation = 8.dp.toPx() }` and call `view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)`; reset on drag end/cancel. Animate scale with `shellSpringSpec()` only when `animationsEnabled`.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*SessionsBarLayoutTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -m "Changed(sessions): Add overflow fade and drag lift"
```

---

### Task 5: Empty-bar prompt card

**Files:**
- Modify: `app/src/main/java/com/termux/app/ui/sessions/SessionsBar.kt`
- Create: `app/src/main/java/com/termux/app/domain/sessions/StarterCommands.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/termux/app/domain/sessions/StarterCommandsTest.kt`

**Interfaces:**
- Consumes: `ShellHostCallbacks.onNewSession`.
- Produces: `val starterCommands: List<StarterCommand>` where `data class StarterCommand(val label: String, val command: String)`; `fun starterChips(isFirstSession: Boolean, hasBootstrap: Boolean): List<StarterCommand>`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun chips_include_help_and_listing_after_bootstrap() {
    val chips = starterChips(isFirstSession = true, hasBootstrap = true)
    assertTrue(chips.any { it.command == "pkg upgrade" })
    assertTrue(chips.size <= 4)
}
@Test fun no_command_chips_before_bootstrap() = assertTrue(starterChips(true, false).isEmpty())
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*StarterCommandsTest"`
Expected: FAIL.

- [ ] **Step 3: Implement**

Replace the empty-bar `Text` with a `Surface` card: prompt line `$ ` in primary, label `R.string.sessions_empty_prompt`, and up to four chips. Tapping a chip calls `onNewSession()` and returns the command through `onStarterCommand(command)`; `TermuxActivity` starts the session and writes the command text plus `\n` (this is the one place Enter is allowed, because the user tapped a command, not a snippet).

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*StarterCommandsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -m "Changed(sessions): Replace empty bar with starter prompt"
```

---

### Task 6: Palette model

**Files:**
- Create: `app/src/main/java/com/termux/app/domain/palette/PaletteEntry.kt`
- Create: `app/src/main/java/com/termux/app/domain/palette/PaletteFilter.kt`
- Test: `app/src/test/java/com/termux/app/domain/palette/PaletteFilterTest.kt`

**Interfaces:**
- Consumes: `SessionItem` list, `Snippet` list (Task 9), static app actions.
- Produces: `enum class PaletteAction { NEW_SESSION, NEW_FLOAT_SESSION, JUMP_SESSION, OPEN_APPEARANCE, OPEN_SETTINGS, TOGGLE_KEYS, PASTE, RUN_SNIPPET }`; `data class PaletteEntry(val id: String, val label: String, val hint: String?, val action: PaletteAction, val payload: String? = null)`; `fun filterPalette(entries: List<PaletteEntry>, query: String): List<PaletteEntry>`; `fun paletteEntries(sessions: List<SessionItem>, snippets: List<Snippet>): List<PaletteEntry>`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun empty_query_returns_input_order() { ... }
@Test fun prefix_match_ranks_before_subsequence() {
    val q = "set"
    val out = filterPalette(listOf(entry("Open settings"), entry("Set theme")), q)
    assertEquals("Set theme", out.first().label)
}
@Test fun non_match_is_dropped() { ... }
@Test fun query_is_case_insensitive() { ... }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*PaletteFilterTest"`
Expected: FAIL.

- [ ] **Step 3: Implement**

`filterPalette` trims the query, matches case-insensitively on label then hint, ranks exact-prefix (`0`), word-prefix (`1`), substring (`2`), subsequence (`3`), and drops the rest. Stable sort by rank. `paletteEntries` emits app actions first, then one entry per session (`JUMP_SESSION`, payload = id), then one per snippet (`RUN_SNIPPET`, payload = command).

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*PaletteFilterTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -m "Added(palette): Add command palette model and filter"
```

---

### Task 7: Palette overlay and open gestures

**Files:**
- Create: `app/src/main/java/com/termux/app/ui/palette/CommandPalette.kt`
- Create: `app/src/main/java/com/termux/app/ui/palette/PaletteState.kt`
- Modify: `app/src/main/java/com/termux/app/ui/ShellHost.kt`
- Modify: `app/src/main/java/com/termux/app/ui/sessions/SessionsBar.kt`
- Modify: `app/src/main/java/com/termux/app/TermuxActivity.java`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/termux/app/ui/palette/PaletteStateTest.kt`

**Interfaces:**
- Consumes: `filterPalette`, `paletteEntries`, `UiPreferences`.
- Produces: `class PaletteState { var visible: Boolean; var query: String; var entries: List<PaletteEntry>; var selectedIndex: Int }`; `ShellHostCallbacks.onPaletteAction(action: PaletteAction, payload: String?)`; `onPaletteQueryChanged(query: String)`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun selecting_moves_and_clamps() { ... }
@Test fun closing_resets_query_and_index() { ... }
@Test fun filtering_keeps_selection_in_range() { ... }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*PaletteStateTest"`
Expected: FAIL.

- [ ] **Step 3: Implement**

`CommandPalette` is a full-width `Surface` with a `TextField`, a `LazyColumn` of `ListItem`s (label + hint), keyboard `Up`/`Down`/`Enter`/`Escape` handling, and scrim dismissal. `ShellHost` renders it above the chrome when `PaletteState.visible`. Open on long-press of the sessions `+` button and on `KEYCODE_K` with Ctrl (handled in `TermuxActivity.dispatchKeyEvent` before the terminal sees it). Action dispatch in `TermuxActivity`: `NEW_SESSION` → `addNewSession`, `NEW_FLOAT_SESSION` → existing float path, `JUMP_SESSION` → `onSessionSelected(payload.toInt())`, `OPEN_APPEARANCE` → `AppearanceActivity`, `OPEN_SETTINGS` → `SettingsActivity`, `TOGGLE_KEYS` → `updateExtraKeysBar`, `PASTE` → existing clipboard paste path (never auto-Enter), `RUN_SNIPPET` → insert `payload` text only.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -m "Added(palette): Add command palette overlay"
```

---

### Task 8: Snippet store

**Files:**
- Create: `app/src/main/java/com/termux/app/domain/snippets/Snippet.kt`
- Create: `app/src/main/java/com/termux/app/domain/snippets/SnippetCodec.kt`
- Create: `app/src/main/java/com/termux/app/data/snippets/SnippetStore.kt`
- Test: `app/src/test/java/com/termux/app/domain/snippets/SnippetCodecTest.kt`
- Test: `app/src/test/java/com/termux/app/data/snippets/SnippetStoreTest.kt`

**Interfaces:**
- Consumes: `UiPreferences`, `UiPreferenceKeys.SNIPPETS`.
- Produces: `data class Snippet(val id: String, val label: String, val command: String)`; `fun parseSnippets(raw: String?): List<Snippet>`; `fun serializeSnippets(items: List<Snippet>): String`; `class SnippetStore(private val prefs: UiPreferences) { fun load(): List<Snippet>; fun save(items: List<Snippet>): List<Snippet> }` (returns the normalized, capped list).

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun blank_lines_are_skipped() { ... }
@Test fun tabs_inside_command_are_preserved() {
    val s = parseSnippets("build\tnpm run build -- --watch")
    assertEquals("npm run build -- --watch", s.single().command)
}
@Test fun store_caps_at_20_and_drops_blank_labels() { ... }
@Test fun store_survives_corrupt_raw_value() { ... }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*SnippetCodecTest" --tests "*SnippetStoreTest"`
Expected: FAIL.

- [ ] **Step 3: Implement**

Line format `label\tcommand`; split on the first tab only; trim label and command; drop rows with a blank label or command; cap at 20; id is a stable incrementing slug (`snippet-1`, …) assigned on save, preserving ids for unchanged rows. Corrupt input yields an empty list plus `save` rewrites the normalized value.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*Snippet*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -m "Added(snippets): Add snippet store and codec"
```

---

### Task 9: Snippet editor and chips row

**Files:**
- Create: `app/src/main/java/com/termux/app/ui/snippets/SnippetsScreen.kt`
- Create: `app/src/main/java/com/termux/app/ui/snippets/SnippetsActivity.kt`
- Modify: `app/src/main/java/com/termux/app/ui/keys/ExtraKeysBar.kt`
- Modify: `app/src/main/java/com/termux/app/ui/ShellHost.kt`
- Modify: `app/src/main/res/xml/termux_display_preferences.xml`
- Modify: `app/src/main/java/com/termux/app/fragments/settings/termux/DisplayPreferencesFragment.java`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/termux/app/ui/snippets/SnippetsStateTest.kt`

**Interfaces:**
- Consumes: `SnippetStore`, `UiPreferences`, `ShellHostCallbacks.onInsertText(text: String)`.
- Produces: `class SnippetsState { var items: List<Snippet>; fun add(label, command); fun update(id, label, command); fun remove(id); fun move(from, to); fun save() }`; a chips row above the extra-keys grid, hidden when `SNIPPETS_ROW` is false.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun add_rejects_blank_and_trims() { ... }
@Test fun move_reorders_and_persists_order() { ... }
@Test fun remove_drops_exactly_one() { ... }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*SnippetsStateTest"`
Expected: FAIL.

- [ ] **Step 3: Implement**

`SnippetsScreen`: `LazyColumn` of editable rows (label field, command field, drag handle, delete), `FloatingActionButton` to add, Apply in the top bar. Chips row: `LazyRow` of `AssistChip`s above `ExtraKeysBar`, only when the bar is visible and snippets exist; tap calls `onInsertText(command)` (no Enter). Add a `Preference` (`snippets`) in `termux_display_preferences.xml` opening `SnippetsActivity`.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*Snippets*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -m "Added(snippets): Add snippet editor and quick chips"
```

---

### Task 10: Installer stage reporting

**Files:**
- Modify: `app/src/main/java/com/termux/app/TermuxInstaller.java`
- Modify: `app/src/main/java/com/termux/app/TermuxActivity.java`
- Modify: `app/src/main/java/com/termux/app/ui/firstrun/FirstRunState.kt`
- Test: `app/src/test/java/com/termux/app/ui/firstrun/FirstRunStateTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `enum class BootstrapStage { DOWNLOADING, EXTRACTING, FINISHING }` in `ui/firstrun`; `interface BootstrapProgressListener { void onStage(BootstrapStage stage); }`; `TermuxInstaller.setupBootstrapIfNeeded(Activity, BootstrapProgressListener, Runnable)` plus the existing two-arg overload delegating with a no-op listener; `FirstRunState.stage: BootstrapStage?`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun installing_without_stage_shows_preparing() { ... }
@Test fun stage_updates_are_observable() {
    val state = FirstRunState()
    state.stage = BootstrapStage.EXTRACTING
    assertEquals(BootstrapStage.EXTRACTING, state.stage)
}
@Test fun failure_clears_stage_and_installing() { ... }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*FirstRunStateTest"`
Expected: FAIL — unresolved `BootstrapStage`.

- [ ] **Step 3: Implement**

Add the listener parameter; call `onStage(DOWNLOADING)` before the download, `EXTRACTING` before extraction, `FINISHING` before post-install setup. Keep the existing `ProgressDialog` for now so behavior does not regress. In `TermuxActivity.startFirstRunBootstrapInstall`, pass a listener that assigns `mFirstRunState.setStage(...)` on the main thread; on failure set `installing = false` and `stage = null`.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*FirstRunStateTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -m "Changed(firstrun): Report bootstrap install stages"
```

---

### Task 11: Branded splash and typed welcome

**Files:**
- Create: `app/src/main/res/drawable/splash_background.xml`
- Create: `app/src/main/java/com/termux/app/ui/splash/IntroSplash.kt`
- Create: `app/src/main/java/com/termux/app/ui/splash/SplashTiming.kt`
- Modify: `app/src/main/res/values/themes.xml`
- Modify: `app/src/main/java/com/termux/app/ui/ShellHost.kt`
- Modify: `app/src/main/java/com/termux/app/ui/ShellChromeState.kt`
- Test: `app/src/test/java/com/termux/app/ui/splash/SplashTimingTest.kt`

**Interfaces:**
- Consumes: `shellAnimationsEnabled()`.
- Produces: `fun splashDurationMs(animationsEnabled: Boolean): Int` (620 with animation, 0 without); `ShellChromeState.splashVisible: Boolean` with `fun dismissSplash()`; `IntroSplash(visible: Boolean, onDone: () -> Unit)`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun splash_is_instant_when_animations_are_off() = assertEquals(0, splashDurationMs(false))
@Test fun splash_is_short_when_enabled() = assertTrue(splashDurationMs(true) in 300..600)
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*SplashTimingTest"`
Expected: FAIL.

- [ ] **Step 3: Implement**

`IntroSplash` draws a centered terminal prompt: a 2dp block cursor blinking twice, then the word `termux` typed over 420 ms, then fades out. `ShellHost` shows it only when `splashVisible` and the session list is empty or first composition after a cold start; `TermuxActivity.onCreate` sets `splashVisible = savedInstanceState == null` so rotation and restore do not replay it. `windowBackground` becomes `splash_background.xml` (black with a small centered prompt glyph) so the native window matches the Compose frame.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*SplashTimingTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -m "Added(splash): Add branded intro splash"
```

---

### Task 12: Animated first-run overlay

**Files:**
- Modify: `app/src/main/java/com/termux/app/ui/firstrun/FirstRunOverlay.kt`
- Create: `app/src/main/java/com/termux/app/ui/firstrun/FirstRunCopy.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/termux/app/ui/firstrun/FirstRunCopyTest.kt`

**Interfaces:**
- Consumes: `BootstrapStage`, `splashDurationMs` pattern (reduced-motion gate), `FirstRunState`.
- Produces: `fun logLines(stage: BootstrapStage?): List<String>` (stable copy, one line per stage, no fake percentage); `fun tipCommands(): List<String>`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun each_stage_has_distinct_lines() { ... }
@Test fun finishing_includes_ready_line() = assertTrue(logLines(BootstrapStage.FINISHING).last().contains("ready", ignoreCase = true))
@Test fun null_stage_shows_only_prompt_lines() = assertEquals(0, logLines(null).size)
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*FirstRunCopyTest"`
Expected: FAIL.

- [ ] **Step 3: Implement**

Replace the centered text block with a terminal-styled panel: `$ ` prompt, typed welcome (reuse the typewriter from Task 11), a `logLines(stage)` list in monospace, a linear progress indicator while installing, and the Install button. After install, show `tipCommands()` as chips that start a session with that command. When animations are off, render all lines immediately.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*FirstRunCopyTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -m "Changed(firstrun): Animate first-run as a terminal boot"
```

---

### Task 13: Keycap visuals and popup affordance

**Files:**
- Modify: `termux-shared/src/main/java/com/termux/shared/termux/extrakeys/ExtraKeysView.java`
- Create: `termux-shared/src/main/java/com/termux/shared/termux/extrakeys/ExtraKeysAffordance.java`
- Modify: `app/src/main/res/values/themes.xml`
- Test: `termux-shared/src/test/java/com/termux/shared/termux/extrakeys/ExtraKeysAffordanceTest.java`

**Interfaces:**
- Consumes: `ExtraKeyButton.getPopup()`.
- Produces: `public static boolean showsPopupChevron(@Nullable ExtraKeyButton button)`; pressed-state drawing in `ExtraKeysView` (inset 1dp + inner shadow line) gated by `keycap-visuals` property (default true).

- [ ] **Step 1: Write the failing test**

```java
@Test public void chevron_only_when_popup_exists() {
    assertEquals(true, ExtraKeysAffordance.showsPopupChevron(new ExtraKeyButton(":", "!", false, false)));
    assertEquals(false, ExtraKeysAffordance.showsPopupChevron(new ExtraKeyButton("ESC", null, false, false)));
    assertEquals(false, ExtraKeysAffordance.showsPopupChevron(null));
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :termux-shared:testDebugUnitTest --tests "*ExtraKeysAffordanceTest"`
Expected: FAIL — class not found. If `:termux-shared` still has no test source set, add `testImplementation(libs.junit)` to `termux-shared/build.gradle` in this step (the ledger records this gap).

- [ ] **Step 3: Implement**

Draw a 3dp chevron in the key's top-right corner when `showsPopupChevron` is true. Draw the pressed state as a 1dp inset rounded rect using the active background color and a 1dp darker top edge. Keep the existing colors from `themes.xml`; add rounded corners (4dp) via `GradientDrawable` background instead of the current flat drawable.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :termux-shared:testDebugUnitTest --tests "*ExtraKeysAffordanceTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -m "Changed(keys): Add keycap depth and popup chevrons"
```

---

### Task 14: Key layout gallery

**Files:**
- Create: `app/src/main/java/com/termux/app/ui/keys/KeyLayoutGallery.kt`
- Create: `app/src/main/java/com/termux/app/domain/keys/KeyPreview.kt`
- Modify: `app/src/main/java/com/termux/app/ui/appearance/AppearanceScreen.kt` (or the keyboard preferences host, whichever renders presets today)
- Test: `app/src/test/java/com/termux/app/domain/keys/KeyPreviewTest.kt`

**Interfaces:**
- Consumes: `KeySetPresets.Preset`, `KeySetPresets.ALL`.
- Produces: `data class KeyPreviewRow(val keys: List<String>)`; `fun previewRows(preset: KeySetPresets.Preset, maxColumns: Int = 8): List<KeyPreviewRow>` (drops overflow keys, never wraps into a third row).

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun packs_keys_into_requested_columns() { ... }
@Test fun drops_keys_beyond_max_columns_without_throwing() { ... }
@Test fun preserves_preset_order() { ... }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*KeyPreviewTest"`
Expected: FAIL.

- [ ] **Step 3: Implement**

Gallery = `LazyRow` of cards; each card renders `previewRows` as miniature keycaps (10sp labels, 2dp gaps) plus the preset name and a `Use` button. Selected card gets a primary border. Applying writes the existing preset preference, reusing `TermuxTerminalExtraKeys.setExtraKeys` path.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*KeyPreviewTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -m "Added(keys): Add key layout gallery"
```

---

### Task 15: iTerm2 color import and export

**Files:**
- Create: `app/src/main/java/com/termux/app/domain/appearance/ItermColors.kt`
- Modify: `app/src/main/java/com/termux/app/domain/appearance/ColorSchemes.kt` (add four presets)
- Test: `app/src/test/java/com/termux/app/domain/appearance/ItermColorsTest.kt`
- Test fixture: `app/src/test/resources/iterm/solarized.itermcolors`

**Interfaces:**
- Consumes: `TerminalColorSet`.
- Produces: `fun parseItermColors(xml: String): TerminalColorSet?` (null when any of `Ansi 0`..`Ansi 15`, `Foreground Color`, `Background Color` is missing or out of range); `fun exportItermColors(colors: TerminalColorSet): String`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun parses_solarized_fixture() { ... }
@Test fun missing_ansi_key_returns_null() { ... }
@Test fun channel_above_one_returns_null() { ... }
@Test fun export_then_parse_round_trips() { ... }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*ItermColorsTest"`
Expected: FAIL.

- [ ] **Step 3: Implement**

Parse the plist `<dict>` with `XmlPullParser` (available on Android and in Robolectric): each color entry is a dict of `Red/Green/Blue Component` reals. Map to ARGB with `round(value * 255)`. Export the same shape. Add Tokyo Night, Catppuccin Mocha, Gruvbox Light, and Rosé Pine presets to `ColorSchemes` in the existing `preset(...)` form; a test asserts each has 256 ANSI entries and all colors are opaque.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*ItermColorsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -m "Added(appearance): Add iTerm color import and export"
```

---

### Task 16: Theme gallery and import/export UI

**Files:**
- Create: `app/src/main/java/com/termux/app/ui/appearance/ThemeGallery.kt`
- Modify: `app/src/main/java/com/termux/app/ui/appearance/AppearanceScreen.kt`
- Modify: `app/src/main/java/com/termux/app/ui/appearance/AppearanceActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/termux/app/ui/appearance/AppearanceStateTest.kt`

**Interfaces:**
- Consumes: `ColorSchemes.presets`, `parseItermColors`, `exportItermColors`, `AppearanceState`.
- Produces: `fun groupPresets(presets: List<ColorSchemePreset>): List<PresetGroup>` with `data class PresetGroup(val name: String, val presets: List<ColorSchemePreset>)`; activity results for `GetContent` (import) and `CreateDocument("application/xml")` (export).

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun groups_split_dark_and_light() { ... }
@Test fun every_preset_appears_in_exactly_one_group() { ... }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*AppearanceStateTest"`
Expected: FAIL — unresolved `groupPresets`.

- [ ] **Step 3: Implement**

Gallery replaces the current `PresetRow` with a grid of cards (three per row): miniature 8-color swatch strip + name. `Import` opens `GetContent`, parses, stages the colors in `AppearanceState` (user still presses Apply), and shows a snackbar on rejection. `Export` opens `CreateDocument`, writes `exportItermColors(state.currentColors())`. Grouping is by preset brightness (relative luminance of background above 0.5 = Light).

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*AppearanceStateTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -m "Changed(appearance): Add theme gallery with import and export"
```

---

### Task 17: Real-output live preview

**Files:**
- Create: `app/src/main/java/com/termux/app/domain/appearance/PreviewSnapshot.kt`
- Modify: `app/src/main/java/com/termux/app/ui/appearance/AppearanceActivity.kt`
- Modify: `app/src/main/java/com/termux/app/ui/appearance/AppearanceScreen.kt`
- Modify: `app/src/main/java/com/termux/app/TermuxActivity.java` (Style action)
- Test: `app/src/test/java/com/termux/app/domain/appearance/PreviewSnapshotTest.kt`

**Interfaces:**
- Consumes: `TerminalBuffer` transcript via `TerminalEmulator.getScreen()`; existing intent launch of `AppearanceActivity`.
- Produces: `fun buildPreviewLines(transcript: String?, maxLines: Int = 14, maxColumns: Int = 60): List<String>`; `AppearanceActivity.EXTRA_PREVIEW_LINES` (`StringArrayList`).

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun strips_control_sequences_and_trailing_spaces() { ... }
@Test fun keeps_last_n_lines() { ... }
@Test fun null_transcript_returns_empty_list() { ... }
@Test fun long_lines_are_elided_at_max_columns() { ... }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*PreviewSnapshotTest"`
Expected: FAIL.

- [ ] **Step 3: Implement**

Read the last `maxLines` rows from the active emulator, strip SGR/OSC sequences with a small regex, trim trailing spaces, elide with `…` at `maxColumns`. `AppearanceActivity` reads the extra and passes it to `PreviewPane`; when absent, the pane keeps today's sample text.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*PreviewSnapshotTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -m "Changed(appearance): Preview the current terminal output"
```

---

### Task 18: Link action sheet

**Files:**
- Create: `app/src/main/java/com/termux/app/ui/terminal/LinkActions.kt`
- Create: `app/src/main/java/com/termux/app/domain/terminal/LinkAction.kt`
- Modify: `app/src/main/java/com/termux/app/terminal/TermuxTerminalViewClient.java`
- Modify: `app/src/main/java/com/termux/app/TermuxActivity.java`
- Modify: `app/src/main/java/com/termux/app/ui/ShellHost.kt`
- Test: `app/src/test/java/com/termux/app/domain/terminal/LinkActionTest.kt`

**Interfaces:**
- Consumes: `UiPreferences.LINK_ACTION_SHEET`, `ShareUtils`.
- Produces: `enum class LinkAction { OPEN, COPY, SHARE }`; `fun availableLinkActions(url: String): List<LinkAction>` (SHARE only when the scheme is http/https); `LinkActionState` with `visible`, `url`, `actions`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun http_gets_open_copy_share() { ... }
@Test fun mailto_gets_open_and_copy_only() { ... }
@Test fun blank_url_gets_no_actions() { ... }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*LinkActionTest"`
Expected: FAIL.

- [ ] **Step 3: Implement**

In `TermuxTerminalViewClient.onUrlClicked` (or the current open path at line ~202), when the pref is on, set `LinkActionState` instead of calling `ShareUtils.openUrl`. Compose renders a `ModalBottomSheet` with one row per action. When the pref is off, keep today's direct open. Copy uses `ShareUtils.copyTextToClipboard`; Share uses the existing share intent.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*LinkActionTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -m "Added(terminal): Add link action sheet"
```

---

### Task 19: Visual bell

**Files:**
- Modify: `termux-shared/src/main/java/com/termux/shared/termux/settings/properties/TermuxPropertyConstants.java`
- Modify: `app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java`
- Modify: `app/src/main/java/com/termux/app/ui/SnackbarState.kt`
- Test: `app/src/test/java/com/termux/app/ui/SnackbarStateTest.kt`

**Interfaces:**
- Consumes: `onBell` (line 225).
- Produces: `IVALUE_BELL_BEHAVIOUR_VISUAL = "visual"` in `TermuxPropertyConstants`; `SnackbarState.showBell()` with 400 ms throttle.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun repeated_bells_within_throttle_show_once() { ... }
@Test fun bell_after_throttle_shows_again() { ... }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*SnackbarStateTest"`
Expected: FAIL.

- [ ] **Step 3: Implement**

Add the constant without removing or renumbering the existing ones; accept it in the property parser switch. In `onBell`, case `VISUAL` calls `mActivity.showBellFlash()` which sets a 250 ms bar highlight and a `SnackbarState` message. Throttle by `SystemClock.elapsedRealtime()`.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*SnackbarStateTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -m "Added(terminal): Add visual bell behavior"
```

---

### Task 20: Themed selection handles and copy toolbar

**Files:**
- Modify: `terminal-view/src/main/java/com/termux/view/textselection/TextSelectionHandleView.java`
- Modify: `terminal-view/src/main/java/com/termux/view/TerminalView.java` (handle color plumbing)
- Modify: `app/src/main/java/com/termux/app/TermuxActivity.java` (pass the theme color)
- Modify: `app/src/main/res/values/themes.xml`
- Test: `terminal-view/src/test/java/com/termux/view/textselection/HandleTintTest.java`

**Interfaces:**
- Consumes: the terminal appearance cursor color and the Compose shell accent.
- Produces: `public static int handleTint(int terminalBackground, int shellAccent)` in `terminal-view` (returns the accent when contrast against the background is at least 3:1, else white or black, whichever contrasts more); `TerminalView.setSelectionHandleTint(int color)`.

- [ ] **Step 1: Write the failing test**

```java
@Test public void keeps_accent_when_it_contrasts() {
    assertEquals(0xFFFF0000, HandleTint.handleTint(0xFF000000, 0xFFFF0000));
}
@Test public void flips_to_white_on_low_contrast_dark_background() { ... }
@Test public void flips_to_black_on_low_contrast_light_background() { ... }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :terminal-view:test --tests "*HandleTintTest"`
Expected: FAIL — class not found.

- [ ] **Step 3: Implement**

`HandleTint` computes relative luminance and WCAG contrast; tint the left/right handle drawables with `DrawableCompat.setTint` (wrap with `mutate()`, since the drawables are shared). `TextSelectionHandleView` reads the tint from `TerminalView`; `TermuxActivity` sets it from the resolved theme accent on create and on appearance reload. Add a `termuxSelectionToolbarStyle` used by `android:windowActionModeOverlay` content (button text in the shell accent, background `surfaceVariant`).

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :terminal-view:test --tests "*HandleTintTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -m "Changed(terminal): Theme selection handles and toolbar"
```

---

### Task 21: Settings search

**Files:**
- Create: `app/src/main/java/com/termux/app/domain/settings/SettingsIndex.kt`
- Modify: `app/src/main/java/com/termux/app/fragments/settings/TermuxPreferencesFragment.java`
- Modify: `app/src/main/res/xml/termux_preferences.xml` (add a `SearchView` host preference or toolbar)
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/termux/app/domain/settings/SettingsIndexTest.kt`

**Interfaces:**
- Consumes: `Preference` tree titles.
- Produces: `data class SettingsEntry(val key: String, val title: String, val summary: String, val fragment: String?)`; `fun filterSettings(entries: List<SettingsEntry>, query: String): List<SettingsEntry>`; `fun settingsIndex(screen: PreferenceScreen): List<SettingsEntry>`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun filters_on_title_and_summary_case_insensitively() { ... }
@Test fun blank_query_returns_full_index() { ... }
@Test fun index_walks_nested_screens() { ... }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*SettingsIndexTest"`
Expected: FAIL.

- [ ] **Step 3: Implement**

Add a `SearchView` in the settings toolbar. On query change, replace the displayed screen with a filtered `PreferenceScreen` built from matching entries (each row navigates to its original fragment). `settingsIndex` recurses into `PreferenceGroup`s and skips headers without a key. Add section icons to the three top-level entries via `app:icon`.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*SettingsIndexTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -m "Added(settings): Add settings search"
```

---

## Deliberately not in this plan

- **Wallpaper and blur controls in the appearance screen** — already shipped in Display preferences (`terminal_background`, `_choose_image`, `_dim`, `_blur`, `_opacity`). Moving them would create a second owner for the same preferences, which the ledger already flags as a problem. If the appearance screen should link to them instead, that is a one-line preference navigation, not a workstream.
- **Sound and haptic cue toggles** — `bell-behaviour` already covers vibrate/beep/ignore and Task 19 adds `visual`; a separate toggle would duplicate it.
- **Desktop/widget-style home screen additions** — out of scope for the UI appeal work.

---

## Workstream acceptance

Each workstream is done when:

- Its task tests pass in `./gradlew :app:testDebugUnitTest` (or `:termux-shared:testDebugUnitTest` for Task 13).
- `./gradlew test` is green.
- The new text appears in `strings.xml`, not in code.
- Motion honors `shellAnimationsEnabled()`.
- A preview (`@Preview`) exists for each new Compose screen.
- The relevant entry in `docs/ledger/manual-checks.md` is added for device-only behavior: tab subtitle truncation at 320dp, drag lift feel, palette keyboard navigation, first-run animation on a cold start, keycap press feel, and import rejection message.
