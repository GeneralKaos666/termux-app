# In-app Terminal Appearance screen — implementation plan

**Date:** 2026-10-14
**Status:** complete — C1–C4 shipped and merged (see §Status)
**Path:** bounded (per brainstorming). The flow being changed already exists in the repo.
**Origin:** "what can we do to make this app even better?" → direction **C — First-class terminal appearance**.
**Scope:** an in-app appearance surface in `:app` that replaces the external Termux:Styling app for
colors, font, font size, and cursor. The terminal engine (`:terminal-emulator`, `:terminal-view`) is
untouched, and `:termux-shared` gets **no diff**.

---

## Status — complete (2026-10-16)

**C1–C4 all shipped and merged.** Merged as PR #1 (state MERGED) by GeneralKaos666; the merge commit
`4b3a01436936aaf05e1b9f1d152537f85f924aff` equals `origin/master`, and the remote branch
`feat/in-app-terminal-appearance` was deleted afterwards.

- **C1** — appearance model + store (`TerminalAppearance`, `ColorSchemes`, `AppearanceStore`).
- **C2** — appearance screen UI + live preview (`AppearanceActivity`, `AppearanceScreen`, `ColorPicker`).
- **C3** — entry points wired: context-menu **Style** and the Settings row open the in-app screen; the
  external `com.termux.styling` launch was dropped (`cc64d6cd`).
- **C4** — cursor style/blink applied; a later follow-up (`349cbd66`) stored them as nullable
  overrides so following `termux.properties` is representable and the override can be cleared.

Gate at merge: `./gradlew :app:testDebugUnitTest` green (17 classes / 111 tests / 0 failures), and
`git diff --stat` confirmed no changes under `terminal-emulator/`, `terminal-view/` or `termux-shared/`.

Carried forward (not part of this plan's scope; see `docs/ledger/outstanding-work.md`):
dead string resources were removed on 2026-10-16, and three of the shipped colour presets still lack an
individual device check.

---

## Decisions taken by the user (2026-10-14)

1. **Direction:** build **C** first; **B** (session durability & tab ergonomics) is queued — see Appendix.
2. **v1 controls:** all of — color-scheme presets, custom color editor, cursor style + blink,
   font family picker, font size, and a live preview pane.
3. **Entry point:** **replace** Termux:Styling. The context-menu **Style** item opens the new in-app
   screen; it is also reachable from Settings. The external `com.termux.styling` launch is dropped.
4. **Apply model:** **full-screen screen with an explicit Apply**. Edits are staged; nothing is
   written until Apply. Back with unsaved edits asks to discard.

---

## Grounding (what exists today, from code)

- `TermuxActivity.showStylingDialog()` (`app/src/main/java/com/termux/app/TermuxActivity.java:1114`)
  fires an Intent at `com.termux.styling`; if absent it shows an "install styling" dialog. The
  context-menu item is `action_style_terminal` (`CONTEXT_MENU_STYLING_ID`, added at line 1007).
- Terminal colors come from `~/.termux/colors.properties`
  (`TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE`). Keys: `foreground`, `background`, `cursor`,
  `color0`…`color255`. Parsed by
  `terminal-emulator/src/main/java/com/termux/terminal/TerminalColorScheme.updateWith(Properties)`;
  hex parsed by `TerminalColors.parse(String)` (public, reachable from `:app` via `:terminal-view`).
- Font comes from `~/.termux/font.ttf` (`TermuxConstants.TERMUX_FONT_FILE`).
- Both are loaded by
  `TermuxTerminalSessionActivityClient.checkForFontAndColors()`
  (`app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java:517`), which
  calls `TerminalColors.COLOR_SCHEME.updateWith(props)`, resets the live emulator's colors, calls
  `updateBackgroundColor()`, and sets the typeface.
- Reload path already exists: `TermuxActivity.updateTermuxActivityStyling(context, recreate)` (line
  1279) broadcasts `TERMUX_ACTIVITY.ACTION_RELOAD_STYLE`, handled at line 1323 →
  `reloadActivityStyling(...)`.
- Font **size** is already an app pref: `TermuxAppSharedPreferences.getFontSize()/setFontSize()`
  (`KEY_FONTSIZE`); changed today by pinch/keyboard via `TermuxTerminalViewClient.changeFontSize`.
- Cursor **style** and **blink rate** are properties read via
  `TermuxSharedProperties.getTerminalCursorStyle()/getTerminalCursorBlinkRate()` (consumed in
  `TermuxTerminalSessionActivityClient:272` and `TermuxTerminalViewClient:656`). They are **not**
  surfaced in any Settings screen today (`termux_terminal_io_preferences.xml` only has soft-keyboard
  toggles).
- Precedent for app-owned knobs that must not change the shared file's meaning: extra-keys preset is
  stored additively in `TermuxAppSharedPreferences` (`setExtraKeysPreset`) and applied in
  `TermuxTerminalExtraKeys.setExtraKeys()`, with the properties file still winning.
- Settings uses `PreferenceFragmentCompat` + a `PreferenceDataStore`
  (`TerminalViewPreferencesFragment.java`); the terminal-view prefs XML is
  `app/src/main/res/xml/termux_terminal_view_preferences.xml`.
- Constraint (AGENTS.md / prior spec): `:termux-shared` public API is frozen (guideline: re-implement
  in `:app` rather than change it). Gate after each task: `git diff` shows no
  `terminal-emulator/`, `terminal-view/`, or `termux-shared/` changes.

---

## Design

### Entry points
- Context menu **Style** (`action_style_terminal`) → `AppearanceActivity` (replaces the external
  intent).
- New **Appearance** preference row under Settings → *Terminal view*.

### The screen
Full-screen Compose (`AppearanceActivity` hosting `AppearanceScreen`), controls:
1. **Presets** — named schemes as data (Termux Default, Nord, Dracula, Solarized Dark, Solarized
   Light, Gruvbox Dark, One Dark).
2. **Custom colors** — background, foreground, cursor, and the 16 ANSI colors, edited with a
   dependency-free HSV picker + hex field.
3. **Cursor** — style (block/underline/bar) and blink rate.
4. **Font family** — `ACTION_GET_CONTENT` for a `.ttf`, copied to `~/.termux/font.ttf`.
5. **Font size** — exposes the existing `KEY_FONTSIZE` value.
6. **Preview pane** — sample lines (prompt, filenames, error, ANSI swatches) rendered with the
   *staged* colors and typeface.
- Actions: **Apply**, **Reset to default** (Termux default scheme), and Back → discard confirm when
  dirty.

### Persistence
| Setting | Store | Reason |
|---|---|---|
| Colors (fg/bg/cursor + 16 ANSI) | `~/.termux/colors.properties` | engine source of truth; keeps hand edits / Termux:Styling compatible |
| Font family | `~/.termux/font.ttf` | the file `checkForFontAndColors()` loads |
| Font size | existing `KEY_FONTSIZE` pref | already present |
| Cursor style / blink | app-local `SharedPreferences`, read with property fallback | keeps `:termux-shared` untouched |

Precedence for cursor style/blink: app pref wins when set; otherwise fall back to
`TermuxSharedProperties` (property file). Read sites to adjust (both in `:app`):
`TermuxTerminalSessionActivityClient.getTerminalCursorStyle()` and the blink-rate application in
`TermuxTerminalViewClient`.

### Apply path
Write files → `TermuxActivity.updateTermuxActivityStyling(context, false)` (existing
`ACTION_RELOAD_STYLE` → `reloadActivityStyling` → `checkForFontAndColors()`), then re-apply cursor
style and blink rate. Debounce only if a control needs it; Apply is a single commit point.

### Files
- Add `app/src/main/java/com/termux/app/ui/appearance/AppearanceActivity.kt` — Compose host.
- Add `app/src/main/java/com/termux/app/ui/appearance/AppearanceScreen.kt` — UI + preview.
- Add `app/src/main/java/com/termux/app/ui/appearance/AppearanceState.kt` — staged state + dirty flag.
- Add `app/src/main/java/com/termux/app/ui/appearance/ColorSchemes.kt` — preset data.
- Add `app/src/main/java/com/termux/app/ui/appearance/AppearanceStore.kt` — IO for
  `colors.properties` / `font.ttf` / prefs (pure-ish, unit-testable).
- Add `app/src/main/java/com/termux/app/ui/appearance/ColorPicker.kt` — dependency-free HSV picker.
- Modify `app/src/main/java/com/termux/app/TermuxActivity.java` — Style item → new activity;
  drop the external `showStylingDialog()` intent.
- Modify `app/src/main/res/xml/termux_terminal_view_preferences.xml` +
  `app/src/main/java/com/termux/app/fragments/settings/termux/TerminalViewPreferencesFragment.java`
  — Appearance row launching the activity.
- Add strings; add tests under `app/src/test/java/com/termux/app/ui/appearance/`.

---

## Tasks

Each task is gated and committed separately (Conventional Commits, capitalized type per AGENTS.md).
Gate after every task: `./gradlew :app:testDebugUnitTest` green (new tests), `:app` compiles, and
`git diff --stat` shows no `terminal-emulator/`, `terminal-view/`, `termux-shared/` changes.

### C1 — Appearance model + store (logic first, TDD)
**Files:** add `AppearanceState.kt`, `ColorSchemes.kt`, `AppearanceStore.kt`; tests.
**Deliverables:**
- `TerminalAppearance` value type: fg, bg, cursor, 16 ANSI; font path; font size; cursor style; blink.
- `ColorSchemes` presets (each defines all 18 colors, valid hex).
- `AppearanceStore`: read current appearance (parse `colors.properties` **preserving unmanaged keys**,
  read font path, font size, cursor prefs), and write it (serialize managed keys back, copy font,
  set font size, store cursor prefs).
**Tests:** parse→serialize round-trip incl. extra `color16..255` keys; preset validation; state
defaults/reset/dirty; cursor-style mapping.
**Commit:** `Added(app): Add terminal appearance model and store`

### C2 — Appearance screen UI + preview
**Files:** add `AppearanceActivity.kt`, `AppearanceScreen.kt`, `ColorPicker.kt`; strings.
**Deliverables:** all six controls, staged state, preview pane, Apply / Reset / discard-on-back.
**Tests:** Robolectric — activity opens and renders; staged edits do not touch files until Apply.
**Commit:** `Added(app): Add in-app terminal appearance screen`

### C3 — Wire entry points and apply
**Files:** modify `TermuxActivity.java` (context menu Style), prefs XML + fragment (Appearance row).
**Deliverables:** Style opens the screen (external app no longer launched); Apply writes files and
reloads the live terminal; Settings row opens it.
**Tests:** unit test that the reload is requested on Apply; device checklist below.
**Commit:** `Changed(app): Open the in-app appearance screen from Style and Settings`

### C4 — Cursor style/blink application
**Files:** modify `TermuxTerminalSessionActivityClient` / `TermuxTerminalViewClient` read sites to
prefer the app pref, fall back to properties; apply on reload.
**Tests:** precedence unit test (pref set vs unset).
**Commit:** `Added(app): Apply cursor style and blink rate from the appearance screen`

---

## Verification

- **JVM:** store round-trip and preset validity (C1), state transitions, cursor precedence (C4).
- **Robolectric:** activity launch + staged-vs-applied (C2/C3).
- **Device checklist:** both entry points; edit each control; preview accuracy vs applied result;
  Apply updates the live terminal without restart; Back discards on dirty; font picker (valid +
  invalid `.ttf`); Reset to default; rotation; dark/light shell theme with terminal colors unchanged.
- **Guard:** no diff under `terminal-emulator/`, `terminal-view/`, `termux-shared/`.

## Risks & mitigations
- **Dropping unmanaged `colors.properties` keys on write** → store preserves keys it does not manage.
- **Invalid/oversized font file** → validate and cap; keep the previous font on failure.
- **Typeface preview throws for a bad ttf** → guard and fall back to monospace in the preview.
- **Known pre-existing Robolectric failure** (`FileReceiverActivityTest`,
  `ShadowActivityThread.reset` NPE) → do not couple new tests to it.
- **`:termux-shared` drift** → cursor prefs stored app-locally, no shared change.

## Non-goals
- No `:terminal-emulator` / `:terminal-view` changes; no `:termux-shared` diff.
- No live-apply (explicit Apply was chosen).
- No new Gradle module and no new third-party dependency (picker is hand-written).

---

## Appendix — Direction B (queued, not designed yet)

**B — Session durability & tab ergonomics.** Grounding: sessions live in
`TermuxShellManager.mTermuxSessions` in memory; `TermuxService.onStartCommand` returns
`START_NOT_STICKY`; a running shell's PTY is in-process, so it **cannot** survive process death.
Realistic "durability" = persist a snapshot and **respawn** on next launch.

- **B-ergonomics (bounded):** drag-to-reorder / pin / per-session label in the sessions bar; needs an
  ordering operation on the service session list.
- **B-restore (architectural):** persist a session snapshot (name/title, command, cwd where
  capturable) and respawn on next launch, opt-in. Requires its own written spec → plan → execution.

Recommendation: do **C** first (bounded, self-contained), then **B-ergonomics**, then **B-restore**.
