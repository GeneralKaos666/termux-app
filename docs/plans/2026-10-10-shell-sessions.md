# Milestone A — Shell & sessions UX — implementation plan

**Date:** 2026-10-10
**Spec:** `docs/superpowers/specs/2026-10-10-shell-and-sessions-ux-design.md`
**Status:** A1–A5 shipped and merged; A2's Step 2 is deliberately deferred (see Task A2, Step 2).
**Gate (after every task):** `./gradlew :app:assembleDebug` is green (using the host fix below), new/affected unit tests pass, and `git diff --stat` shows **no** changes under `terminal-emulator/`, `terminal-view/`, or `termux-shared/`.
**Commit convention (AGENTS.md):** Conventional Commits with capitalized types, e.g. `Changed(app): ...`. One commit per completed task.

> Note on the build: `:app`'s `javaCompile` depends on `downloadBootstraps`, which fetches ~180 MB `bootstrap-<arch>.zip` files and needs network. Pre-warm once (`./gradlew downloadBootstraps`) and avoid `clean` between tasks. Kotlin compilation (`compileDebugKotlin`) does **not** need the bootstraps, so use it for fast inner loops.
>
> **APK builds: prefer GitHub Actions (primary).** The repo already ships `.github/workflows/debug_build.yml`, which builds on `ubuntu-latest` (x86_64) with JDK 17 and uploads per-ABI APK artifacts. Trigger it by pushing a branch/PR (see below) rather than fighting the native toolchain locally. This is the source of truth for "does it build, and can we install it".
>
> **CI trigger mechanics (`debug_build.yml`):** after the local `workflow_dispatch` addition (commit `525b9d7b`), it can be started on demand with `gh workflow run "Build"` **once that commit is on the default branch** (GitHub only exposes dispatch for workflows present on the default branch). It also runs on `on.push.branches = [master, github-releases/**]` and on PRs to `master`. Download artifacts with `gh run download`. We are **not pushing** for now, so the on-demand path activates only after you push.
>
> **Local native build on this aarch64 Termux host (fallback only):** this machine's Android SDK is a Termux repackage whose NDK compiler entries are shell wrappers over Termux's clang, and whose usable host tree is `prebuilt/linux-x86_64`. Stock `ndk-build` fails because `build/tools/ndk_bin_common.sh` accepts `arm64` (macOS) but not Linux's `aarch64`, and its bundled `make`/`python3` are x86_64 ELF. To make `assembleDebug` work locally:
> 1. Make the NDK resolve `HOST_TAG=linux-x86_64` — either patch `ndk_bin_common.sh` to treat `aarch64` as `x86_64`, or prepend a `uname` shim that returns `x86_64` for `-m`.
> 2. Export `GNUMAKE=/data/data/com.termux/files/usr/bin/make` (Termux GNU Make 4.4.1).
>
> Run `./gradlew --stop` first so the Gradle daemon picks up the new environment (the daemon caches env at start; a stale daemon silently ignores it). The harmless `python3: Syntax error` line printed by `ndk-build` is the NDK's x86_64 `python3` and does not affect the build.

---

## Task A1 — Build enablement: Kotlin + Compose in `:app`

**Goal:** `:app` compiles Kotlin and can host a trivial Compose surface. No UI behaviour change.

**Files:**
- Modify: `build.gradle` (root) — add plugin classpath.
- Modify: `app/build.gradle` — apply plugins, enable Compose, add dependencies.
- Add: `app/src/main/java/com/termux/app/ui/Probe.kt` — a temporary `@Composable` used only to prove the toolchain; deleted in A2.

**Interfaces:**
- Produces: a working `org.jetbrains.kotlin.android` + `org.jetbrains.kotlin.plugin.compose` setup on AGP 8.13.2 / Gradle 9.2.1, `buildFeatures { compose true }`.
- Consumes: nothing from other modules.

**Pinned versions (verified against AGP 8.13.2 / compileSdk 36 / minSdk 21):**
- Kotlin `2.2.21` (`org.jetbrains.kotlin.android`, `org.jetbrains.kotlin.plugin.compose`) — both 2.2.21.
- Compose BOM `2025.11.01` (Compose UI `1.9.5`, Material3 `1.4.0`); `androidx.activity:activity-compose:1.11.0`; `androidx.lifecycle:lifecycle-runtime-compose:2.9.4`.
- Keep `markwonVersion=4.6.2`; do not float versions.
- **Two version ceilings, both verified from artifact metadata, both forced by the current toolchain:**
  1. **compileSdk/AGP:** Compose UI `1.12.0+` declares `minCompileSdk=37` and requires **AGP 9.1.0+**. So BOM `2026.08.00`+ (including the `2026.09.00` tried first) fails.
  2. **minSdk:** Compose `1.10.0+` declares **minSdk 23**, and `activity-compose 1.12.0+` declares minSdk 23. The app is minSdk 21, so the newest usable line is Compose `1.9.x` with `activity-compose 1.11.0`.
  - Net: **BOM `2025.11.01` + activity-compose `1.11.0`** is the newest combination compatible with minSdk 21 / compileSdk 36 / AGP 8.13.2. Revisit both ceilings only as part of a toolchain + minSdk bump.

**Verification:** `./gradlew :app:assembleDebug` green (with the host fix above). `git diff --stat` touches only the two Gradle files and `Probe.kt`.

- [ ] **Step 1 — Root `build.gradle` classpath.**
  Inside `buildscript.dependencies`, add:
  ```groovy
  classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21"
  classpath "org.jetbrains.kotlin.plugin.compose:org.jetbrains.kotlin.plugin.compose.gradle.plugin:2.2.21"
  ```
  (Add `mavenCentral()`/`google()` are already present.)

- [ ] **Step 2 — `app/build.gradle` plugins.**
  ```groovy
  plugins {
      id "com.android.application"
      id "org.jetbrains.kotlin.android"
      id "org.jetbrains.kotlin.plugin.compose"
  }
  ```

- [ ] **Step 3 — `app/build.gradle` android block.**
  ```groovy
  buildFeatures {
      buildConfig true
      compose true
  }
  ```
  Add a Kotlin `jvmToolchain`/target consistent with Java 8:
  ```groovy
  kotlinOptions { jvmTarget = "1.8" }
  ```
  (Adjust to the accepted Kotlin DSL if the plugin reports the option as removed on 2.2.x.)

- [ ] **Step 4 — `app/build.gradle` dependencies.**
  ```groovy
  implementation platform("androidx.compose:compose-bom:2026.09.00")
  implementation "androidx.compose.ui:ui"
  implementation "androidx.compose.foundation:foundation"
  implementation "androidx.compose.material3:material3"
  implementation "androidx.activity:activity-compose:1.13.0"
  implementation "androidx.lifecycle:lifecycle-runtime-compose:2.9.4"
  debugImplementation "androidx.compose.ui:ui-tooling"
  ```

- [ ] **Step 5 — probe composable.**
  Add `Probe.kt` with a `@Composable fun ProbePreview()` returning a `Text("ok")`. Do not wire it into any screen yet.

- [ ] **Step 6 — verify.** Run `./gradlew downloadBootstraps` once, then `:app:assembleDebug` (with the aarch64 host fix above).

**Risks:** Java 8 target + Kotlin 2.2 may emit a JVM-target warning; if it becomes an error, raise Java/Kotlin target together (both to 11) — but only if required, and as a separate, called-out change.
**Gate:** green compile; no changes outside the two Gradle files and `Probe.kt`.
**Commit:** `Added(app): Enable Kotlin and Jetpack Compose in the app module`

---

## Task A2 — Compose host + TerminalView interop (IME/focus)

**Goal:** Host the existing `TerminalView` from a Compose root without regressing focus, soft keyboard, hardware keys, or rotation. This is the highest-risk task and is proven **before** any real UI is built.

**Files:**
- Modify: `app/src/main/java/com/termux/app/TermuxActivity.java` — replace `setContentView(R.layout.activity_termux)` with a Compose host that renders an `AndroidView` wrapping the terminal; keep the existing session/`TermuxTerminalViewClient` wiring intact.
- Add: `app/src/main/java/com/termux/app/ui/ShellHost.kt` — the Compose root (`AndroidView` for `TerminalView`, a placeholder for the sessions bar).
- Modify: `app/src/main/res/layout/activity_termux.xml` — emptied/retained only if still referenced; do not delete until A2 lands.
- Remove: `app/src/main/java/com/termux/app/ui/Probe.kt`.

**Interfaces:**
- Consumes: the `TerminalView` instance already created by the activity.
- Produces: `ShellHost(sessionHost: ..., terminalFactory: () -> View)`-style entry point the activity calls; no change to session classes.

**Verification (device checklist — required before A3):**
- [x] Soft keyboard opens on tap, closes on back, and terminal reflows without flicker.
- [x] Hardware keyboard input works; Ctrl/Alt combos unchanged.
- [x] Focus stays on the terminal after the bar placeholder is shown/hidden.
- [x] Rotation and multi-window do not crash or lose the session.
- [x] No `Window leaked`/`InputMethodManager` warnings in logcat.
- [x] `:app:assembleDebug` green (with the aarch64 host fix); `:terminal-*` tests untouched and green.

**Results (device `10.0.0.169:44425`, NX769J API 34, arm64-v8a; build 0.118.0 debuggable):**
- View tree intact: `ComposeView(id/terminal_shell_host) → AndroidComposeView → ViewFactoryHolder → TerminalView`, with `DrawerLayout`, `ViewPager` extra-keys and bottom-space insets unchanged.
- Tap → `mInputShown=true`, `mServedView=com.termux.view.TerminalView`, `mServedInputConnection=TerminalView$2`; terminal reflows (`ADJUST_RESIZE`); BACK hides IME and keeps activity focus.
- Hardware keys: injected key events created `files/home/a2k` (typed via keycodes). Compose's `AndroidComposeView.dispatchKeyEvent` forwards unconsumed events to the focused child (`FocusOwner.dispatchKeyEvent → ViewGroup.dispatchKeyEvent`).
- Ctrl combo: toolbar CTRL toggle + `c` sent SIGINT (running `sleep 12` process disappeared); CTRL auto-reset verified (follow-up `touch ln` created the file). Alt uses the identical `mClient.readAltKey()` path.
- Rotation: landscape reflows terminal to `[7,99][2473,291]` with focus + IME intact; portrait restore clean.
- Multi-window: system placed the app in a `mode=3` multi-window task and it did not crash; a stable, focused split pane was not automatable on this RedMagic/ZTE ROM (recommend one manual check).
- Logcat: crash buffer empty; no `FATAL EXCEPTION`/`Window leaked`/`ANR`.
- Note: `adb shell input text` is unreliable on this ROM; verify key input with `input keyevent` keycodes instead.

**Steps:**
- [x] **Step 1** — Introduce `ShellHost` as the single Compose entry; `AndroidView` hosts `TerminalView`, with `Modifier.fillMaxSize()`.
- [x] **Step 3** — Keep the `ViewPager` extra-keys bar temporarily (still Java) so only one variable changes at a time.
- [x] **Step 4** — Run the device checklist; record results in the task report.

**Implementation notes (deviation):** The existing XML shell (`DrawerLayout`, `TermuxActivityRootView`, extra-keys `ViewPager`, bottom-space insets) is retained; only terminal *hosting* moved to Compose (`ComposeView` + `ShellHost`). The Compose host is focus-disabled (`isFocusable=false`, `isFocusableInTouchMode=false`, `descendantFocusability=FOCUS_AFTER_DESCENDANTS`) so `TerminalView` owns the IME input connection.

**Gate:** checklist passes; otherwise stop and revise the interop approach before A3.
**Commit:** `Changed(app): Host TerminalView from a Compose shell root`

---

## Task A3 — Top sessions bar

**Goal:** Replace the 240dp drawer session list with an always-visible, adaptive top tab strip.

**Files:**
- Add: `app/src/main/java/com/termux/app/ui/sessions/SessionsBar.kt` — the Compose tab strip.
- Add: `app/src/main/java/com/termux/app/ui/sessions/SessionItem.kt` — observed model (title, running, exitCode, active).
- Modify: `app/src/main/java/com/termux/app/TermuxActivity.java` — feed the model from the existing session client; remove the gear button and `ListView` wiring for the drawer; keep `TermuxSessionsListViewController` logic ported into the model.
- Modify: `app/src/main/res/layout/activity_termux.xml` — drop `@id/left_drawer` contents (drawer removed here).
- Tests: `app/src/test/java/com/termux/app/ui/sessions/SessionItemMappingTest.java` (or `.kt`).

**Interfaces:**
- Produces: `List<SessionItem>` + callbacks `onSelect(id)`, `onRename(id)`, `onClose(id)`, `onNew()`.
- Consumes: existing `TermuxTerminalSessionClient`/session list.

**Success details (from spec §5.1):**
- tab = title + running-state dot; adapts: minimal with one session, scrolls when many, auto-scrolls active into view.
- tap = switch; long-press/overflow = rename (dialog) / close (confirm if running); trailing "+" = new.
- empty state = "Start a session".
- top system inset respected; terminal measured below the bar.

**Verification:** mapping unit test (running/stopped/exit-code → state) passes; device checklist: switch/add/rename/close, empty state, 1 vs many sessions, rotation.
**Commit:** `Added(app): Add adaptive top sessions bar`

**Result (2026-10-11): done, committed.**

Implemented as a Compose `SessionsBar` hosted by the Compose shell, with a pure
`SessionItem` mapping so the model is unit-testable without Android. The
`DrawerLayout`/`left_drawer` and `TermuxSessionsListViewController` were deleted
outright (not left empty); `activity_termux.xml` now has the `ComposeView` as a
direct child of the root `RelativeLayout`, from the top to above the toolbar
`ViewPager`.

Extras beyond the sketch, found while device-testing:
- One-step Close. Stock Termux keeps a session in the list when its process exits
  with a non-zero status, so a "kill and hope" Close left a dead tab behind.
  `TermuxTerminalSessionActivityClient.closeSession()` now records handles to
  remove on exit, so a running session's tab disappears when the process dies.
- The status dot now also reports `stateDescription` to accessibility; it was
  conveying running/exited/error by colour alone.
- The auto-scroll effect is keyed on the active index, not the whole list. Keyed
  on the list it re-ran on every title update (constant while commands run) and
  yanked the strip back, so the user could not browse other tabs.
- The legacy `DRAWER` extra key is a documented no-op, and the `Ctrl+Alt+←/→`
  drawer shortcuts are gone, so existing user configs still parse.

Device checks on `10.0.0.169:44425` (NX769J, API 34, arm64-v8a): bar shows with
1 session; add/switch/rename; long-press menu → Close with confirm for a running
session removes the tab in one step; a finished session (`exit 3`) stays and
Close removes it with no dialog; 6 sessions scroll and the active tab is scrolled
into view; manual scroll position survives a title refresh; rotation preserves
the active session; closing the last session finishes the activity; no crashes
and no termux-tagged logcat output. Not reachable in practice: the empty state,
because removing the last session finishes the activity (stock behaviour).

`./gradlew :app:testDebugUnitTest` has a pre-existing failure unrelated to A3:
`FileReceiverActivityTest.testIsSharedTextAnUrl` NPEs in Robolectric teardown
("ShadowActivityThread.reset: ActivityThread not set"). It fails identically on a
clean tree (verified with `git stash`). `SessionItemMappingTest` passes.

---

## Task A4 — Quick actions & extra-keys bar

**Goal:** Replace the bottom `ViewPager` with a data-driven Compose bar; **default key set unchanged**.

**Files:**
- Add: `app/src/main/java/com/termux/app/ui/keys/ExtraKeysBar.kt` — collapsible key row + expanded set.
- Add: `app/src/main/java/com/termux/app/ui/keys/KeySet.kt` — the default key list (data).
- Modify: `app/src/main/java/com/termux/app/TermuxActivity.java` — remove `TerminalToolbarViewPager` usage; route key presses to the existing `ExtraKeysView`/terminal input path.
- Modify: `app/src/main/res/layout/activity_termux.xml` — remove `@id/terminal_toolbar_view_pager`.

**Interfaces:**
- Consumes: existing key handling (`ExtraKeysView`, modifiers, `TermuxTerminalExtraKeys`).
- Produces: `KeySet` data + `onKey(...)` callback.

**Verification:** unit test that `KeySet` matches today's defaults exactly; device checklist: all default keys send the right sequences, active/modifier state renders, expansion animates, 48dp targets on 360dp width.
**Commit:** `Changed(app): Replace extra-keys ViewPager with a Compose bar`

---

## Task A5 — Chrome polish

**Goal:** Settings entry, guarded dynamic color, motion, snackbars/undo.

**Files:**
- Add: `app/src/main/java/com/termux/app/ui/theme/Theme.kt` — Material theme; dynamic color guarded to API 31+.
- Modify: `app/src/main/res/values/themes.xml` — keep the terminal theme; stop hardcoding shell colors where the Compose theme takes over.
- Modify: `ShellHost.kt`/activity — settings action in the bar overflow; snackbar host.

**Verification:** device checklist: API 31+ uses Material You, older falls back to Termux palette, terminal theme unchanged; "remove animations" respected; rename/close shows a snackbar with undo.
**Commit:** `Changed(app): Polish shell theme, motion, and session feedback`

---

## Sequencing & stop points

1. A1 → gate → commit. **Stop and report.**
2. A2 (interop proof) → device checklist → commit. **Stop: this decision can invalidate A3+ if it fails.**
3. A3, A4, A5 → each gated and committed.
4. Milestone B (defaults) is planned only after A ships.

---

## Milestone C — Terminal background wallpaper (proposed)

**Goal:** Put something behind the terminal instead of the flat background colour: a
picture of the user's choosing, or a live (animated) wallpaper.

**Decision (2026-10-11): Option 2 — static wallpaper / image. The design below is the
concrete C1 plan.**

---

## Task C1 — Static terminal background (system wallpaper + custom image)

**Goal:** Show the system wallpaper — or an image picked from storage — behind the
terminal, with a dim overlay for readability. Solid-colour mode stays the default and
behaves exactly as today.

**Decisions (2026-10-11):** split into two commits — C1a ships the system wallpaper +
dim + settings; C1b adds the custom image picker. Controls live in the Settings screen
only (no quick action). Both committed and gated separately.

**Key code finding:** the terminal is already transparent. `TerminalView.onDraw` paints
no background (except when no emulator is attached), and today's "terminal background"
is the window decor colour set in
`TermuxTerminalSessionActivityClient.updateBackgroundColor()`. So the wallpaper is
painted by the Compose shell behind the terminal AndroidView — `:terminal-emulator`
and `:terminal-view` stay untouched; only `:app` (plus additive prefs in
`:termux-shared`) changes.

**Files:**
- Add: `app/src/main/java/com/termux/app/ui/background/TerminalBackgroundState.kt` —
  Compose-observable `bitmap` + `dimPercent`, mutated from Java like the other shell
  states.
- Modify: `app/src/main/java/com/termux/app/ui/ShellHost.kt` — wrap the terminal
  AndroidView in a `Box` that draws the bitmap (cropped to fill) plus a black dim
  overlay (`alpha = dimPercent / 100`) in a `drawBehind`; nothing drawn when
  `bitmap == null`.
- Modify (additive): `termux-shared/.../TermuxPreferenceConstants.java` — in `TERMUX_APP`:
  `KEY_TERMINAL_BACKGROUND = "terminal_background"`,
  `IVALUE_TERMINAL_BACKGROUND_SOLID = "solid"`,
  `IVALUE_TERMINAL_BACKGROUND_WALLPAPER = "wallpaper"`,
  `IVALUE_TERMINAL_BACKGROUND_IMAGE = "image"` (used by C1b),
  `DEFAULT_IVALUE_TERMINAL_BACKGROUND = solid`,
  `KEY_TERMINAL_BACKGROUND_DIM = "terminal_background_dim"`,
  `DEFAULT_TERMINAL_BACKGROUND_DIM = 60`,
  `KEY_TERMINAL_BACKGROUND_IMAGE = "terminal_background_image"` (C1b).
- Modify (additive): `termux-shared/.../TermuxAppSharedPreferences.java` — getters and
  setters for the keys above + `shouldUseWallpaperTerminalBackground()` helper.
- Modify: `app/src/main/res/xml/termux_terminal_view_preferences.xml` — a "Terminal
  background" category with a `ListPreference` (`terminal_background`: Solid colour /
  System wallpaper; "Custom image" joins in C1b) and a `SeekBarPreference`
  (`terminal_background_dim`, 0–90, default 60).
- Modify: `app/src/main/java/com/termux/app/fragments/settings/termux/TerminalViewPreferencesFragment.java`
  — extend `TerminalViewPreferencesDataStore` with `putString/getString` (background
  mode) and `putInt/getInt` (dim) cases so the preferences write through
  `TermuxAppSharedPreferences`.
- Modify: `app/src/main/java/com/termux/app/TermuxActivity.java` —
  `syncTerminalBackground()`: solid → clear bitmap; wallpaper → decode the system
  wallpaper on a single background thread and push the bitmap into the state. Called
  from `onResume()` (covers returning from Settings — there is no styling-change
  broadcast from the preferences screens) and from a context-registered
  `Intent.ACTION_WALLPAPER_CHANGED` receiver so wallpaper edits apply live. Use
  `WallpaperManager.getDrawable()` (deprecated on newer APIs; targetSdk 28 keeps the
  legacy path; suppress the warning), downsample to screen size.
- Modify: `TermuxTerminalSessionActivityClient.updateBackgroundColor()` — no-op when
  wallpaper background is configured (the decor colour sits hidden behind the Compose
  layer anyway, so this only avoids overdraw).
- C1b only: "Choose image" preference (`ACTION_GET_CONTENT` +
  `takePersistableUriPermission`), decode → copy into app-private storage so a revoked
  grant does not blank the terminal, third list entry `image`.

**Behaviour:**
- `solid` (default): exactly today's behaviour.
- `wallpaper`: system wallpaper behind the terminal; text stays readable via the dim
  overlay. Terminal colour themes keep working: they change text and non-default cell
  colours; the default background shows the picture.
- Empty terminal (no emulator attached) still paints black; acceptable.

**Verification:** unit test for `TerminalBackgroundState` defaults and the dim
clamping; device checklist: text readable over a bright wallpaper with default dim,
dim slider changes overlay, solid/wallpaper round-trip through rotation and app
restart, IME and extra-keys unaffected, scroll with heavy output stays smooth,
coloured-cell apps (e.g. htop) render correctly, wallpaper change applies live.

**Commits:**
1. `Added(app): Add system wallpaper terminal background`
2. `Added(app): Add custom image terminal background`

---

## Milestone B — Sensible defaults (planned)

**Goal:** Make the first-run experience and the out-of-the-box defaults friendlier,
without changing behaviour for existing installs. From spec §6.

### Task B1 — First-run onboarding

**Goal:** On a fresh install, instead of dropping into a bare shell (today: a bare
progress dialog for the ~180 MB bootstrap), show a friendly first-run flow: a short
welcome, an "Install packages" action that runs the existing bootstrap install with
progress and retry, and the storage-permission step up front.

**Decision (2026-10-11): Compose overlay in the shell (not a dialog restyle).**

**Files:**
- Add: a Compose first-run overlay in `ShellHost.kt` driven by a Java-mutated
  `FirstRunState` (same pattern as `SessionsState`/`ExtraKeysState`).
- Modify: `app/src/main/java/com/termux/app/TermuxActivity.java` — route the
  empty-sessions branch of `onServiceConnected` (currently
  `TermuxInstaller.setupBootstrapIfNeeded(...)` immediately) through the overlay;
  wire `requestStoragePermission(...)` into the flow.
- Modify: `app/src/main/java/com/termux/app/TermuxInstaller.java` — surface the
  progress/callback it already produces (or keep the dialog as the progress UI).

**Behaviour:** bootstrap missing → welcome → install → progress → first session.
Failsafe session still reachable. Existing installs see nothing new.

**Verification:** device checklist on a wiped-data install: full flow, cancel, retry
after failure, permission denial, rotation mid-install, back key behaviour.

**Commit:** `Added(app): Add first-run onboarding`

### Task B2 — Preset extra-key sets

**Goal:** One-tap extra-keys layouts for common tools from Settings (Default /
Compact / Vim / Emacs), implemented by writing the existing `extra-keys` property and
reloading the bar. Custom layouts via the properties file keep working and win over
presets.

**Files:**
- Add: `app/src/main/java/com/termux/app/ui/keys/KeySetPresets.kt` — preset matrices
  as reference data (like `KeySet.DEFAULT`).
- Modify: `app/src/main/res/xml/termux_terminal_view_preferences.xml` +
  `TerminalViewPreferencesFragment` — a list preference that writes
  `TermuxPropertyConstants.KEY_EXTRA_KEYS` through `TermuxSharedProperties`.
- Modify: `app/src/main/java/com/termux/app/TermuxActivity.java` — recreate
  `TermuxTerminalExtraKeys`' `ExtraKeysInfo` and refresh the bar on change.

**Verification:** unit test that each preset is a valid matrix and matches the
property syntax; device: switching presets updates the bar live, modifiers and popups
still work, custom property wins.

**Commit:** `Added(app): Add extra-keys presets`

### Task B3 — Sensible font size and padding defaults

**Goal:** Friendlier defaults for new installs: font size one step above the minimum,
margins already sane (margin adjustment defaults on). Existing installs keep their
stored prefs — only fresh installs see the new defaults.

**Files:** the resource `sizes` int-array + `DEFAULT_FONTSIZE` plumbing in
`TermuxAppSharedPreferences`; verify line spacing.

**Open question:** exact target sizes after a quick device A/B on a 360dp-wide screen.

**Verification:** device checklist on a fresh install: text crispness, row count,
rotation.

**Commit:** `Changed(app): Adjust default font size and padding`

### Sequencing & stop points

- B1 → gate → commit. B2 and B3 each gated and committed.
- The default shell prompt stays owned by the bootstrap (termux-packages); dropped from
  this milestone.
- B and C1 touch different areas (first-run overlay vs. terminal background), so they
  can be interleaved; implementation order is the user's call.

---

## Status (2026-10-11)

- A1, A2, A3, A4, A5 all committed locally (not pushed): `c4ff069c`, `201b6865`, `cae77c17`, `a503d779`, `3b923a74`.
- A5 notes: settings entry landed as a quick action (⚙) instead of a top-bar overflow; close snackbar has no undo (close SIGKILLs running shells and destroys finished ones, so undo isn't reasonable — documented deviation from "undo where reasonable").
- Milestone B (sensible defaults) fully implemented: B1 first-run overlay (`fb0e6d84`), B2 extra-keys presets (`114897d4`), B3 font default 14dp (`bc51696d`); the prompt task was dropped from the plan (owned by termux-packages). All device verification deferred (offline test connection).
- Milestone C (terminal background wallpaper) — user chose Option 2 (static image); split decided: C1a system wallpaper first, C1b custom image picker second; controls in Settings only.
- C1a shipped: `b872fe71 Added(app): Add system wallpaper terminal background`. Device-gate note: the test device's system wallpaper is black and its firmware blocks programmatic wallpaper changes, so the visual effect was gated via settings UI, pref persistence and crash-free runs; the visible picture pipeline gets its visual proof in C1b (custom image uses the same paint path).
- C1b shipped: `653f8a73 Added(app): Add custom image terminal background`. Device verification deferred (wireless test connection offline at commit time); still needs a device pass: picker flow, persistence, dim, restart, solid/wallpaper round-trip.
- Milestone C code-complete; device gate outstanding for C1b (and the visual proof for both C tasks).
- B1 shipped: `fb0e6d84 Added(app): Add first-run onboarding`. Compose overlay + installer guard (isBootstrapInstalled, latest-callback re-registration for rotation mid-install). Device verification deferred (needs a wiped-data install pass).
- B2 shipped: `114897d4 Added(app): Add extra-keys presets`. Presets in Settings (Default/Compact/Vim/Emacs), custom property wins, applied on resume. Unit tests pin the property text (hermetic, since Robolectric 4.10 fails at teardown on this JDK 21 setup - same cause as the pre-existing FileReceiverActivityTest failure). Device verification deferred.
- B3 shipped: `bc51696d Changed(termux-shared): Adjust default font size for fresh installs`. Decision (2026-10-12): 14dp (one +2 step up, user chose over keeping 12dp or 16dp). Margin adjustment already defaults on upstream, so no padding change was needed. Device verification deferred.
## Device verification (2026-10-12, NX769J @ 10.0.0.169:45751)

All planned device gates ran on the pinned test device:

- **C1b custom image**: full picker flow (photo picker → Browse → file), copy to app-private
  storage, pref persistence, gradient visible behind terminal, dim slider applies. PASS.
- **C1a system wallpaper**: PASS once the device wallpaper is a static image (user confirmed).
  With the ZTE live wallpaper, WallpaperManager.getDrawable() yields no usable frame (black) —
  a device/engine quirk, not app code; noted as a known limitation.
- **B1 onboarding**: fresh install shows the overlay (Welcome / body / Install), Install runs the
  bootstrap, the first session appears and the overlay dismisses; relaunch shows no overlay.
  Not exercised: cancel/retry after failure, rotation mid-install, back key (left for a later pass).
- **B2 presets**: Vim and Compact presets apply live from Settings (verified via the extra-keys
  grid a11y labels); a custom extra-keys property in termux.properties wins over the preset
  (F1-F4 shown while preset=compact). PASS.
- **B3 font default**: fresh install renders at the 14dp default — measured terminal row pitch
  49.5px matches 14dp at the device's 2.55x density (12dp would be ~42px). PASS.
- **Icon fix**: `7d166ae5` — quick action icons now white (user confirmed).

Screenshot caveat: on this device `screencap` does not show Compose-rendered pixels (only the
Android-view layers), so Compose visuals were gated through a11y dumps and the user's eyes.

---

## Status (2026-10-15)

- Session reordering landed after A5, outside this plan: `50126a9b Added(app): Allow reordering
  sessions in the top bar` on `feat/sessions-reorder`. It changed the bar's gesture contract from
  spec §5.1 — long-press now starts a drag instead of opening the session menu, and
  rename/close/move left/move right stay behind the active tab's overflow (⋮). Spec §5.1 updated to
  match.
- Bug fixed: `7972a541 Fixed(app): Restore tap-to-switch and repair reorder in the sessions bar`.
  The reorder commit had swapped the tab's `combinedClickable(onClick = onSelect)` for a long-press
  drag detector, so taps never reached `onSelect` (and "Switch to" had been dropped from the menu),
  so there was no way left to switch sessions from the bar; and `onDragEnd` reset `dragOffsetX`
  before reading it and used a hardcoded `itemWidth = 100f`, so a finished drag never called
  `onMove`. The drop index now comes from the already-tested `dropIndex`/`TabBounds` helpers in
  `SessionReorder.kt`, measured against tab bounds captured at drag start.
- Device gate (NX769J @ 10.0.0.169:33921, API 34): tap reaches `onSelect` and switches the active
  tab; a long-press drag across a neighbour calls `onMove(id, target)` and the list order swaps,
  with the active tab's bounds following its new index. Unit suite green (123 tests, 0 failures);
  no spurious tap fires after a drag.
- Diagnostics note for future device passes: `logcat` returns nothing on this ROM, so the gesture
  path was traced to an app-private file (`getFileStreamPath(...).appendText`), removed before
  committing. `input draganddrop` does **not** trigger Compose's long-press drag (it registers as a
  click); a slow `input swipe x1 y x2 y 12000` does.
- Known limitations, not fixed here: tab labels fall back to a positional name,
  `Session <list index + 1>`, built in `TermuxActivity.refreshSessionsBar`, so reordering a session
  whose shell has not set a terminal title renames it instead of visibly moving it; and only the
  active tab carries the overflow menu, so a non-active session cannot be renamed or closed until
  it is tapped first.

---

## Chrome slimming + second key grid (2026-10-20)

Design retargeted in chat (no separate spec): the sessions bar was halved (56dp → 28dp) and the
dot/chevron strip above the extra keys was deleted, the collapse chevron moving onto the bar itself
(with a floating restore handle when collapsed). The extra-keys pager then lost its quick-actions
page entirely: page 2 is now a **second key grid with its own layout**, page 3 (text-send) gained a
back arrow to page 1.

Revised 2026-10-21: the halved tabs proved too small to tap, so the chip minimum went 24dp → 32dp
(bar 36dp measured on device at 408dpi: 82px chips). Still well under the 48dp Material default.

- Page 2 is a real second `ExtraKeysView` (not a Compose imitation), so custom layouts, modifiers,
  popups and long-press repeat work there exactly as on page 1.
- Its layout resolves like page 1: the `extra-keys-page2` property in
  `~/.termux/termux.properties` always wins, otherwise the "Second page preset" picker in
  Settings → Keyboard (`default`, `function`).
- Both grids share one `SpecialButtonState` map, so CTRL/ALT toggled on one page applies on the
  other; `ExtraKeysView.reload()` now clears only the buttons of the instance it runs on to make
  that sharing possible.
- `QuickAction`, `QuickActionsPage` and the `onExtraKeysAction` callback are gone. The dropped
  actions survive as engine keys: `KEYBOARD`/`PASTE`/`SCROLL` already did, and `SETTINGS` was added
  next to them in `TermuxTerminalExtraKeys`. `action_text_input` was removed with the page.
- Deviation from the approved design: the proposed "None" preset was dropped — it resolved to
  `default`, so it was an entry that changed nothing.
- Unit suite green (520 tests, 0 failures) including new `ExtraKeysPage2PresetsTest` and updated
  height/state/settings-XML/preference-store tests. Device gate outstanding: chevron overlay and
  collapse handle, two grids with per-page layouts, and the back-arrow → terminal IME handoff.
