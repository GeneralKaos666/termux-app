# Shell & sessions UX — design spec

**Date:** 2026-10-10
**Status:** implemented. Milestones A (shell & sessions), B (sensible defaults) and C1 (terminal
background) all shipped; see [`../plans/2026-10-10-shell-sessions.md`](../plans/2026-10-10-shell-sessions.md)
for the per-task commits and status.
**Origin:** user vision — "the same Termux underneath, just with a much better UI/UX, sensible defaults, smooth interactions, and proper Android integration."
**Scope:** the experience layer only. The terminal engine (`:terminal-emulator`, `:terminal-view`, JNI) is untouched. First workstream is the **app shell and session management**; the other three parts of the vision (sensible defaults, settings/onboarding, Android integration) are queued as later milestones in §7 and are **not** planned here.
**Decisions taken by the user:** Jetpack Compose shell (wrap the existing `TerminalView`); keep `targetSdk 28` for now; keep the `com.termux` identity and package.

---

## 1. Background (what exists today, grounded in code)

The app is a single-activity Java app. `TermuxActivity` (~3k lines) owns everything: terminal sessions, the drawer, the extra-keys toolbar, the soft keyboard, and system-event handling.

Current shell, from `app/src/main/res/layout/activity_termux.xml`:

- A `DrawerLayout` (`@id/drawer_layout`) whose content is the custom `com.termux.view.TerminalView` and whose drawer is a **fixed 240dp** `LinearLayout` (`@id/left_drawer`) containing:
  - a 40dp gear `ImageButton` (`@id/settings_button`) top-left,
  - a plain `ListView` (`@id/terminal_sessions_list`) of sessions,
  - two `MaterialButton`s pinned to the bottom: "toggle keyboard" and "new session".
- Below the drawer, a `ViewPager` (`@id/terminal_toolbar_view_pager`, `height 37.5dp`, `background @color/black`) hosts the extra-keys row and the text-input row.
- The extra-keys widget is `com.termux.shared.termux.extrakeys.ExtraKeysView` in `:termux-shared` (`view_terminal_toolbar_extra_keys.xml`).

Theming: `Theme.TermuxActivity.DayNight.NoActionBar` hardcodes `colorPrimary`/`windowBackground` to `@color/black`, translucent status/navigation bars, and custom `attr` colors for the drawer and extra keys (`app/src/main/res/values/themes.xml`). `colors.xml` is empty. Material is on the classpath (`com.google.android.material:material:1.12.0`) but effectively only two buttons use it.

Toolchain (from `gradle.properties` / `app/build.gradle`): `minSdkVersion=21`, `targetSdkVersion=28`, `compileSdkVersion=36`, AGP 8.13.2, Gradle 9.2.1, `:app` is **Java-only** (source/target 1.8), `markwonVersion=4.6.2`.

Consequences that shape the design:

- Nothing in the shell is reusable or themed as a system; it is hand-positioned views edited inside a 3k-line activity.
- **`:app` has no Kotlin/Compose today**, so "use Compose" is first a build change, not a UI change.
- `ExtraKeysView` is in **`:termux-shared`**, which is published (JitPack) and consumed by the plugin apps. Its public behaviour is effectively frozen.

## 2. Goals & success criteria

**Goal:** replace the hand-built shell with a coherent, animated, touch-correct Material surface — without touching the terminal engine or the shared library's public API.

**Success criteria**

- Session management is visible at all times as tabs in a **top sessions bar**, each showing its **name and state (running/stopped)**, with rename and close as first-class actions and a one-tap "new session".
- The extra-keys bar and quick actions are driven by data (a key list), not by a `ViewPager` with two hardcoded pages.
- Every interactive control meets the 48dp minimum touch target and is reachable one-handed on a 360dp-wide phone.
- All new surfaces are Compose, are dark/light aware, and animate state changes with Material motion (no instant cuts between shell states).
- `:terminal-emulator` and `:terminal-view` compile and test unchanged; `:termux-shared`'s public API is source- and binary-compatible.
- `./gradlew :app:testDebugUnitTest` (and `assembleDebug` where the device/SDK allows) is green after each task.

**Non-goals**

- No rewrite of terminal emulation, input, or scrollback.
- No change to `targetSdk` (stays 28) or to the package/applicationId.
- No changes to `:termux-shared` public API. New UI lives in `:app`.
- No new settings surface or first-run onboarding in this workstream (queued in §7).
- No new navigation library and no DI/Room/ViewModel framework mandate — keep it to what the shell needs.

## 3. Design principles

1. **Wrap, don't fork, the terminal.** The `TerminalView` stays the single source of truth for terminal rendering and input. The shell only surrounds it.
2. **Incremental interop over big-bang rewrite.** Compose is introduced alongside the existing Java activity; migrate one surface at a time so the app is shippable at every step.
3. **Data-driven chrome.** Sessions, keys, and actions come from lists, so new entries are a data change, not a layout change.
4. **One-hand reachability.** Primary actions live in the bottom third; destructive ones require confirmation.
5. **Motion communicates state.** Bar expand/collapse, key expansion, and session switching use standard Material motion and honor "remove animations".
6. **Freeze the shared contract.** If a change would touch `:termux-shared`'s public API, re-implement locally in `:app` instead.

## 4. Architecture

### 4.1 Build enablement (prerequisite)

`:app` gains Kotlin + Compose, staying Java-compatible:

- Root `build.gradle`: add `org.jetbrains.kotlin.android` and `org.jetbrains.kotlin.plugin.compose` to the classpath (both are already resolved in the local Gradle cache: `org.jetbrains.kotlin`, `org.jetbrains.kotlin.plugin.compose`).
- `app/build.gradle`: apply the two plugins, add `buildFeatures { compose true }`, and add the Compose BOM. **Two ceilings on this toolchain (both verified):** Compose UI `1.12.0+` needs AGP 9.1 / compileSdk 37, and Compose `1.10.0+` / `activity-compose 1.12.0+` need minSdk 23 (the app is minSdk 21). The newest compatible set is **BOM `2025.11.01`** (UI `1.9.5`, Material3 `1.4.0`) with **`activity-compose:1.11.0`** and `lifecycle-runtime-compose:2.9.4`. Pin exact versions; do not float. Revisit only with the milestone-D toolchain bump.
- Keep `compileOptions` at Java 8/`coreLibraryDesugaring` as-is; Kotlin `jvmTarget` matches `1.8`.
- **No new Gradle module** in this workstream: Compose lives in `:app` so it can own the activity lifecycle directly and keep the terminal close. (A future split into `:app-ui` is possible but deliberately deferred.)

This is a standalone task with its own gate: the app must `assembleDebug` with zero UI changes before any Compose screen is written.

### 4.2 Interop model

- `TermuxActivity` remains the host and keeps ownership of `TerminalView`, sessions, and the service connection.
- New Compose surfaces are hosted via `ComposeView` (or an `AndroidView`-wrapped `TerminalView` inside a Compose root, chosen during the first UI task). The terminal is a leaf `AndroidView`; the shell is Compose around it.
- **Focus/IME handling is the highest-risk area**: the `TerminalView` must keep focus and the soft-keyboard `InputMethodManager` contract. The shell must not steal focus, and the IME inset must be applied to the terminal content, not the whole compose tree. This is validated explicitly on device.
- Session state flows from the existing `TermuxTerminalSession*Client` objects into a small observable holder the Compose surfaces read; no second source of truth for sessions is created.

### 4.3 What stays where

| Concern | Owner | Change |
|---|---|---|
| Emulation, rendering, input | `:terminal-view` / `:terminal-emulator` | none |
| Extra-keys widget core | `:termux-shared` | none (public API frozen) |
| Extra-keys **presentation/layout** in the app | `:app` | new Compose bar |
| Sessions model | `:app` (game, client classes) | thin observable wrapper |
| Shell UI | `:app`, Compose | new |
| Activity lifecycle, service | `:app` Java | kept, trimmed |

## 5. The new shell

### 5.1 Sessions bar (top, adaptive)

Replaces both the fixed 240dp drawer list and the gear button with a **sessions bar docked at the top of the terminal**: a horizontally-scrollable tab strip whose length adapts to the number of open sessions.

- Each **tab** shows the session title and a small **state dot** (running / stopped / exit code), sized to its content.
- **Adaptive length:** with one session the bar collapses to its minimal form (title + overflow) so the terminal keeps its vertical space; as sessions are added the bar grows to fit and then **scrolls horizontally** when the content exceeds the width. The active tab auto-scrolls into view on switch.
- **Tap** = switch session. **Long-press + drag** = reorder the tab; the session menu (rename / close / move left / move right) lives behind a trailing overflow on the active tab. Closing a running session confirms first. Reordering was added after this spec was written; see the plan's status notes.
- A trailing **"+"** action starts a new session.
- **Empty state:** when the last session is closed, the bar shows a centered "Start a session" action rather than leaving a blank terminal.
- The bar respects the top system inset (status bar) and sits above the terminal content; terminal rows are measured below it, not behind it.

Rationale for choosing a top bar over the sheet/drawer: sessions are a primary, frequently-switched concern, so a always-visible, one-tap strip beats a surface that must be opened first.

### 5.2 Quick actions & extra keys

- The bottom `ViewPager` is replaced by a Compose bar, height driven by content (not the rigid 37.5dp), with the same default key set so muscle memory is preserved.
- Keys come from a list (label, key code / sequence, active state). An **expanded** state reveals the wider key set (mirrors today's second page) with animation.
- Quick actions (keyboard toggle, new session, paste, more) are surfaced as icon buttons with proper content descriptions and haptics.
- The existing `ExtraKeysView` remains the engine for key handling where practical; the change is the container and layout, implemented in `:app`.

### 5.3 Chrome & interaction polish

- The 40dp gear is replaced by a properly-sized accessible entry point in the sessions bar (trailing overflow, alongside "+"), or a floating affordance, opening settings.
- Chrome is **floating/edge**: the top sessions bar plus bottom floating affordances, no full-height dedicated layout chrome, so terminal space is preserved.
- Material dynamic color is used where available (API 31+), guarded; on older APIs fall back to a Termux palette seeded from the current black background. The terminal's own theme stays independent of the shell theme.
- State changes (bar collapse/expand, key expansion, session switch, last-session-closed) animate with Material motion and respect the system "remove animations" setting.
- Snackbars for reversible outcomes (session renamed/closed) with an undo where reasonable.

## 6. Sensible defaults (context only — later milestone)

Listed so the shell is designed to accommodate them, not to implement now:

- First-run: a friendly "install packages" prompt and storage setup, instead of dropping into a bare shell.
- Preset extra-key sets for common tools; a good default prompt; sensible font size/padding.
- These depend on the shell existing first, which is why they follow workstream A.

## 7. Program milestones

Each milestone is its own approval-gated cycle: in-chat design → explicit yes → implement → gate → commit. Plan one, ship one.

| # | Milestone | Depends on | Notes |
|---|---|---|---|
| A | **Shell & sessions UX** (this spec) | — | Build enablement + sessions surface + quick actions |
| B | Sensible defaults | A | Default keys/font/prompt, package-install prompt |
| C | Settings & first-run onboarding | A | Rework the preferences IA |

## 8. Risks & mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| Compose steals focus from `TerminalView` / breaks the IME | High | Dedicated first UI task; `AndroidView` leaf; device-verify keyboard open/close, hardware keys, and rotation before any further Compose work |
| Compose + Kotlin on this toolchain (AGP 8.13.2 / Gradle 9.2.1 / Java 8 target) fails to configure | Medium | Build-enablement task gated on `assembleDebug` alone; versions pinned to cached BOM |
| Accidentally changing `:termux-shared` public API | Medium | Extra-keys presentation re-implemented in `:app`; a reviewer check that `termux-shared/` has no diff in this workstream |
| 3k-line Java activity becomes harder to reason about as two stacks coexist | Medium | Strict "one surface at a time"; each task compiles and ships independently |
| `downloadBootstraps` (~180MB) makes CI/test cycles slow | Medium | Pre-warm once; scope test gates to the affected module; avoid full `clean` per task |
| Device testing unavailable | Medium | Robolectric for logic; explicit list of device-only checks tracked per task |

## 9. Verification strategy

- Pure logic (key lists, session state mapping, action availability) → JVM unit tests under `app/src/test`.
- Compose surfaces → Robolectric where possible (`includeAndroidResources = true` is already set); the rest is a written **device checklist** per task (keyboard, IME insets, focus, rotation, multi-window).
- Terminal parity → existing `:terminal-emulator` tests must stay green and untouched.
- Gate per task: `:app` compiles, new tests pass, no `termux-shared/` or `terminal-*/` diff.

## 10. Resolved decisions

All four open questions were answered by the user on 2026-10-10:

1. **Sessions surface:** a **top, horizontally-adaptive sessions bar (tab strip)**, not a bottom sheet or drawer. See §5.1.
2. **Expanded keys:** **keep today's exact default key set** for zero-migration; improve in milestone B.
3. **Dynamic color:** **guarded Material You** on API 31+, Termux palette fallback (see §5.3).
4. **Top chrome:** **floating affordances** plus the top sessions bar — no full app bar (see §5.3).
