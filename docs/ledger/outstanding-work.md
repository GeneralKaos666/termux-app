# Outstanding work — the single work ledger (NOT a plan)

**Date:** 2026-10-24 (consolidated)
**Status:** capture only. Nothing here is approved, scheduled, or designed. Each entry records what is
known and what the next step would cost. Items marked *needs spec* require the full cycle
(brainstorming → spec → plan) before any code.
**Origin:** the former six-file ledger (`outstanding-work`, `repo-audit`, `engine-upgrade`,
`float-port`, `upgrade-roadmap`, `manual-checks`) was folded into two files on 2026-10-24. This file
owns every decision and every piece of not-yet-done work; `manual-checks.md` owns every device/manual
check and is deliberately the **last** thing to do. The pre-fold text of the absorbed files is in git
history before this commit.
**Reconciled 2026-10-24** against `master` (`da327795`): the extra-key popup work of 2026-10-22 is
recorded as done, the stale "WS3 device checklist still outstanding" line is corrected, and the
`docs/ideas/` paths that survived the 2026-10-20 re-home are repointed here.

**Legend:** `[bounded]` small and self-contained · `[needs spec]` architectural, needs its own cycle ·
`[deferred]` postponed on purpose · `[check]` built but not verified on device · `[probed]`
verification attempted on device, with the outcome recorded · `[limitation]` accepted as-is for now ·
`[non-goal]` decided against.

---

## 0. Work order at a glance

1. **Diagnosed defects** — **done 2026-10-25** (3e0f1931, 0e37e230, d784313c, 980b29c6, ad380602):
   the five bounded items in §2.4 are resolved.
2. **Engine remainder (§2)** — WS6 compatibility audit **done 2026-10-25** (backward-compatible, no
   gaps); the rest of xterm conformance remains (unspecced, parked with WS5).
3. **Code and test gaps (§3)** — god-class splits and the missing test surface.
4. **Build and platform (§4)** — R8/release hardening, Java 17, CI, bootstrap caching, JitPack.
5. **Epics that need a spec cycle (§5)** — session restore, Termux:Float, 256-colour palette,
   palette-version signal.
6. **Dependency refresh (§4.3)** — one commit per dependency, ascending blast radius,
   `compose-bom` last and separate.
7. **Device and manual checks** — `manual-checks.md`. These are the last thing to do, after the
   code work above settles.

---

## 1. Sessions, shell and settings

### 1.1 Session durability / restore — `[needs spec]`

A running shell cannot outlive the app process. Sessions live in
`TermuxShellManager.mTermuxSessions` in memory and the PTY is in-process, so
`TermuxService.onStartCommand` returning `START_NOT_STICKY` means process death ends them. Realistic
"durability" = persist a session snapshot (name/title, command, cwd where capturable) and **respawn**
fresh sessions on the next launch, opt-in. Write it up separately before building it. (Grounding:
appearance plan, Appendix — Direction B.)

Ordering advice already recorded: "appearance first, then B-ergonomics, then B-restore". Appearance,
drag-to-reorder, rename and close have all since shipped.

### 1.2 Sessions bar — closed defects (record)

- **Positional fallback tab titles** — `[done 2026-10-16, 1d973de4]` the fallback number is derived
  from the session's stable id, so reordering a session with no name/terminal title keeps its label.
  The dead `number` parameter was removed from `sessionItem(...)` and the `i + 1` argument dropped at
  `TermuxActivity.java:769`; covered by `SessionItemMappingTest`.
- **Only the active tab exposes the ⋮ menu** — `[done 2026-10-16, e9434cf7]` every tab shows its own
  overflow, so rename / close / move are reachable without switching first. Long-press stays the drag
  handle; the ⋮ is the menu entry point on every tab.
- **Extra-key swipe-up popups** — `[done 2026-10-22, ba68e464 → da327795]` six commits fixed and then
  aligned the popup with the reference app (`Graywizard888/Terminal_EX`): the popup is a touchable,
  outside-touchable, non-focusable `PopupWindow` shown at `showAsDropDown(view, 0, -2 * height)`,
  painted with the active (pressed) key background, with no listener of its own. `ACTION_MOVE` shows
  it on swipe up and dismisses it when the finger slides back down; `ACTION_CANCEL` restores the
  background, stops the repeat executors and leaves the popup up; `ACTION_UP` fires it when still
  showing. The earlier `firePopupIfArmed` helper, `mPopupEngaged` flag and non-touchable-popup paths
  were removed. Verified on device with an event trace (swipe up fires popup key, swipe back down and
  plain tap fire the base key). No open work.

### 1.3 Settings and onboarding — shipped

- B1 first-run walkthrough, B2 extra-key presets and B3 font-size default shipped.
- Milestone C **preferences information-architecture rework** shipped 2026-10-16. The Termux hub is
  now Display / Keyboard / Advanced, all 13 leaf `app:key` values are frozen, and no migration is
  needed. Commits `de9ed98b`..`9077b5f6`; spec `specs/2026-10-16-settings-ia-design.md`, plan
  `plans/2026-10-16-settings-ia.md`; final review clean.
- The onboarding and settings checks in `manual-checks.md` D-7 and D-8 are closed (D-7 measured
  2026-11-02; D-8 on the maintainer's verdict, since exercising the edges needs a data wipe).
- `[fixed 2026-11-18]` **Settings search matches nothing in the Termux panel.** Typing any query
  ("font", "keyboard", "colour") showed "No settings match". `SettingsIndexBuilder` only indexes
  preferences that have an `app:key`, and the three rows in `res/xml/termux_preferences.xml`
  (Display / Keyboard / Advanced) had none — they only carried `app:fragment`. Fixed by giving the
  three rows keys (`display`, `keyboard`, `advanced`); they are navigation rows, so no persisted
  value and no migration. Clearing the query restored the full list, so nothing was lost. Covered by
  `SettingsIndexBuilderTest.the hub indexes its three navigation rows` and
  `.a query matching a hub row keeps it` (both red before the fix: an empty index). Device check
  D-43 passed 2026-11-18, and running it exposed two follow-on defects, both fixed in
  `TermuxPreferencesFragment`: tapping a result crashed with
  `PreferenceFragmentCompat.bindPreferences` on a null `RecyclerView` (a collapsing `SearchView`
  fired a last empty-query rebuild after the view was replaced, so the rebuild now ignores query
  changes once `view == null`), and the filtered list survived a return from a result while the
  search view came back empty (the real screen is re-inflated in `onViewCreated`). Neither has a
  unit test: the shape is fragment lifecycle, and `:app` has no fragment host test infrastructure.
- `[check]` **Settings search is scoped to the screen it is opened from.** The search index is built
  from the current preference hierarchy, so a query on the Termux hub can only match the hub's three
  rows. Leaf settings are unreachable by search: D-35 asks for "Backup" to be found, and typing
  `backup` on the hub reports "No settings match", while the Advanced screen has no search field at
  all. Making search global means indexing the leaf fragments' XML as well and navigating into them;
  that is a design decision, not a bug fix, so it is recorded here rather than changed.

### 1.4 Appearance — shipped, device passes outstanding

- Seven colour presets ship; Solarized Light, Gruvbox Dark and One Dark have had a machine contrast
  pass but still need the human aesthetic pass (`manual-checks.md` D-5).
- `[done 6c606187]` dead string resources removed (lint `UnusedResources` no longer lists any unused
  string). Other unused resources (7 session/current-session drawables, `preference_markdown_text.xml`,
  3 styles) were later deleted in `b99c79e1`.
- `[done 349cbd66]` cursor style and blink are nullable overrides (`null` = follow
  `termux.properties`), with Default chips and a Reset that stages `null`; a colour-only Apply no
  longer creates a cursor override.
- `[shipped 2026-11-14]` cursor visual polish in `:terminal-view`: the blink is a triangular fade
  envelope (`CursorFx.blinkAlpha`, driven at ~16 ms by the existing `TerminalCursorBlinkerRunnable`),
  the cursor rect carries a halo of its own colour, and bar/underline cursors glide to the new cell
  (`CursorFx.easeTowards`, ~0.35 per frame). A block cursor never eases, since its text inversion is
  baked into the cell. The engine and the public renderer API are unchanged (a new 13-argument
  `render(...)` overload does the effects; the existing overloads delegate with neutral values).
  `[corrected 2026-11-15]` the blink/ease loop now runs on `Choreographer` frame callbacks with
  time-based easing (a 26 ms half-life, so the glide speed is the same at any refresh rate), and the
  cursor is static (no fade, no glide) when the system animator scale is 0. Device pass:
  `manual-checks.md` D-14.
- `[shipped 2026-11-14]` shell UI motion in `:app` Compose: both chrome bars spring open and closed
  (`theme/Motion.kt#shellSpringSpec`), session tabs slide/fade on add, close and reorder
  (`Modifier.animateItem`), the active tab's colours fade in and a rounded pill glides between tabs,
  and the float bubble/window grows and fades in on first composition. Everything collapses to
  instant when `shellAnimationsEnabled()` is false. `[corrected 2026-11-15]` the chrome bars
  expand/collapse on a tween (no height or alpha overshoot), the float overlay enters from 92 %
  instead of a point, and `shellAnimationsEnabled()` follows the setting while the app runs (a
  `ContentObserver`). Device pass: `manual-checks.md` D-15.
- `[shipped 2026-11-14]` acrylic terminal background in `:app`: the wallpaper/custom-image picture
  behind the shell can be blurred (0–25 px; a cached `BlurLayer` with a `RenderEffect` on its
  `RenderNode` on API 31+, a load-time downscale with filtered upscale below it) and lightened
  independently of the dim (opacity 10–100 %). Both live in `TerminalBackgroundState` and draw from
  `ShellHost.drawBehind`; the two preferences are additive in `:termux-shared`. Defaults (0 px,
  100 %) reproduce the previous rendering exactly. Device pass: `manual-checks.md` D-16.
- `[limitation]` a live (animated) wallpaper is never captured: the system-wallpaper background falls
  back to a flat colour, the wallpaper's primary colour on API 27+, else the terminal background
  colour. Live wallpapers are detected with `WallpaperManager.getWallpaperInfo()` because some
  devices return a stale static frame through `getDrawable()` instead of none, which would freeze the
  previous wallpaper behind the terminal.
- `[fixed 2026-11-18]` float enter now reacts to "remove animations" mid-flight. `Motion.kt`'s
  `LaunchedEffect` was keyed on `progress` alone, so turning the setting off during the overlay's
  ~300 ms enter let that one pop finish instead of snapping. Raised as Finding 2 by the
  2026-11-15 motion-craft review. The effect is now keyed on `animationsEnabled` and calls
  `snapTo(1f)` when it turns false (an `animateTo(1f)` from `1f` is a no-op if it turns back on).
  No unit test: the module has no Compose composition test and adding one is a dependency change;
  device row added to `manual-checks.md` D-15.
- `[fixed 2026-11-18]` the 2026-11-15 motion-craft spec carried a stale "not yet approved / needs
  sign-off" status line although the three §9 reversals were confirmed and the work shipped
  (`docs/specs/2026-11-15-motion-craft-design.md`, head of file). Raised as Finding 3 by the same
  review. The status line now records the approval and the shipped state.
- `[fixed 2026-11-18]` the whole-repo animation review (second pass) found three blockers, all fixed.
  **Bar geometry:** the chrome bars animated their height, and both bars share a `Column` with the
  terminal, so every frame resized the terminal and ran `TerminalView.updateSize` →
  `TerminalSession.updateSize` → `JNI.setPtyWindowSize` plus a full repaint — a shell in vim/tmux
  reflowed several times per toggle. `ShellHost.kt` now fades the bars only (150/120 ms), so the
  height changes in one frame; `ExtraKeysBar.kt`'s pager height snaps for the same reason, keeping
  its alpha tween. **Tab scroll:** `SessionsBar.kt` called `animateScrollToItem(activeIndex)` on every
  switch, which aligns the tab to the leading edge, so the strip slid even when the tab was already
  on screen; it now scrolls only for an out-of-viewport tab and by the minimum delta
  (`animateScrollBy`). **Easing:** Compose's default `tween` easing is `FastOutSlowIn` (an ease-in
  lead); all shell tweens now use `theme/Motion.kt#ShellEaseOut`
  (`cubic-bezier(0.23, 1, 0.32, 1)`). No unit test: Compose specs and layout have no test host here;
  `./gradlew test` is green and `manual-checks.md` D-15 is rewritten. Spec correction:
  `docs/specs/2026-11-15-motion-craft-design.md` §11.
- `[fixed 2026-11-18]` the rest of the animation review's findings, fixed the same day. **Springs:**
  `theme/Motion.kt#shellSpringSpec` is now `LowBouncy` (about 3 % overshoot instead of 16 %) and a new
  `shellSettleSpec` (critically damped, same stiffness) drives the active-tab pill's offset and width
  and the residual drag offset on drop, so neither overshoots its tab or slot. **Cursor scale and
  blink:** `TerminalView` caches the animator duration scale (refreshed by the existing
  `ContentObserver`) and multiplies it into the blink period and the 26 ms glide half-life, so a
  0.5x/2x setting changes cursor speed like the Compose chrome; `CursorFx.blinkAlpha` now passes its
  triangle through smoothstep, so the cursor holds near full opacity around the ends and fades fast
  through the middle instead of sitting half transparent. `CursorFxTest` covers the new envelope (13
  tests). **Typewriter:** `TypewriterText` types at a per-character cadence (32 ms, clamped 240–900 ms)
  instead of one fixed 420 ms for any string, so a long localized welcome no longer blurs past;
  `TypewriterTimingTest` covers the clamp. **Restore handle:** `ShellHost` fades the collapsed-bar
  handle in only after the bar's 120 ms exit, so a control no longer pops into the corner while the
  bar is still on screen. `./gradlew test` green (135 `:terminal-view`, 530 `:app`). **Not changed:**
  colour transitions still snap under "remove animations" — Compose itself scales every animation by
  `ANIMATOR_DURATION_SCALE` (`MotionDurationScaleImpl` in `compose-ui`), so a colour tween cannot be
  kept alive while the setting is 0 without bypassing the platform; the explicit `snap()` is the
  honest reading of "remove animations". Docs: `manual-checks.md` D-14/D-15 and the spec §11 items
  4–7.

- `[device checks pending]` **UI appeal program** (`docs/plans/2026-11-16-ui-appeal-program.md`,
  written 2026-11-16 from the chat brainstorm). Seven workstreams, 21 tasks: app UI preference store,
  session tab context + unseen-output badges, overflow fade + drag lift, starter prompt, command
  palette, snippets (store, editor, chips), installer stage reporting, branded splash, animated first
  run, keycap visuals + popup chevrons, key layout gallery, iTerm color import/export, theme gallery,
  real-output preview, link action sheet, visual bell, themed selection handles, settings search.
  **Implemented natively 2026-11-16** as 21 signed commits on `feature/ui-appeal-program`, each with
  its tests; `./gradlew test` is green. What remains is the device pass in
  `docs/ledger/manual-checks.md` §22 (D-22), which covers every feel/animation/picker check. The
  plan records the design decisions in place of a spec; per-task deviations and their rulings are in
  the git-ignored execution ledger. Deliberately excluded: wallpaper/blur controls (already owned by
  Display preferences) and a separate sound/haptic toggle (covered by `bell-behaviour`).

---

## 2. Terminal engine program

### 2.1 Decisions

- **Driving goal:** performance and rendering plus new terminal capabilities — not a general
  maintainability, Kotlin or packaging refactor.
- **Constraint:** companion-app interoperability (the 2026-10-17 `AGENTS.md` change made upstream
  acceptance a non-goal; `minSdk 21`, Java 1.8 and the published `:termux-shared` / client-base
  surfaces still bind).
- **Capability targets (all four):** kitty graphics; OSC 8 hyperlinks + underline styles; ligatures
  and grapheme shaping; more VT/xterm conformance.
- **Performance driver:** no concrete symptom; the goal is architectural headroom.
- **Approach B (substrate first) chosen:** evolve `TerminalBuffer` into the real cell model, keep the
  parser where it is, then add the frame/dirty-region renderer, then land features as small consumers.
- **Module shape:** `:terminal-emulator` ≈8.0k LOC, `:terminal-view` ≈2.9k LOC, `:termux-shared` ≈27k
  LOC, all Java 1.8. `TerminalEmulator` is a 3,876-line god class (parser + screen mutation + DECSET
  flags + colours + sixel/iTerm state); `TerminalView` is 1,661 lines doing input, gestures, selection
  and drawing. `:termux-shared` imports terminal internals in 7 files, so `TerminalBuffer` is
  effectively part of its API.

### 2.2 Workstreams

| # | Workstream | Status |
|---|---|---|
| 1 | Cell model: rich attributes, grapheme clusters, link id, dirty-row tracking | **done**; spec `specs/2026-10-17-terminal-engine-cell-model-design.md`; absorbed the parsing halves of `SGR 4:n` and `OSC 8` |
| 2 | Renderer: frame snapshot + dirty-region + image layer | **done**; spec `specs/2026-10-17-renderer-dirty-region-design.md`; one limitation in §2.4 |
| 3 | Text: run shaping / ligatures | **done**; spec `specs/2026-10-18-ws3-shaping-ligatures-design.md`; device evidence in `manual-checks.md` D-1 |
| 4 | Graphics: unify sixel + iTerm2 + kitty as one placement layer | **done**; spec `specs/2026-10-18-ws4-graphics-placement-design.md`; D-2 passed 2026-10-19 |
| 5 | Conformance + OSC 8 + underline styles | **done** (`SGR 58`, hyperlinks end-to-end); spec `specs/2026-10-17-osc8-hyperlinks-sgr58-underline-design.md` |
| 6 | `:termux-shared` compat shims (threaded through all of the above) | **done (audit 2026-10-25)** — public API is backward compatible and the delegating shims are real; two follow-ups in §2.5 |
| — | xterm-conformance remainder | **unspecced**, parked with WS5 |

### 2.3 WS3 / WS4 / WS5 delivery notes

- **WS3** implemented in `d5bb6baa`, `94d29f2c`, `30d9a9e6`: grid-snap policy and advance seam, the
  per-span glyph stage, and the ligature toggle (appearance screen, default on, `+liga,+calt` on /
  `-liga,-calt` off). Grid-snap verification is API 29+ (`Paint.getTextRunAdvances` is API 29); below
  29 a run draws as one shaped span and ligatures still form.
- **WS4** implemented by `1fdcb66e` (base64 + size arithmetic), `cdb86841` (chunked transfer),
  `fcb244c7` (real placement/deletion, offset-aware clamped `getSixelRect`) and `0709dd2f` (decoder
  seam and the `a=t|T|p|q|d` dispatch). A real defect found on device — a transfer accepted only two
  chunks — was fixed in `KittyGraphicsTransfer.accept()` (tests `threeChunksConcatenatePayload`,
  `middleChunkOverBudgetErrors`).
- **WS5** delivered on `feature/ws5-hyperlinks-sgr58` with `./gradlew test` 411/411 green, one commit
  per planned task, five recorded deviations (hit-test math; `reset()` initialises the underline
  colour; DECSC/DECRC saves it; app opens links through `ShareUtils.openUrl`; `SGR 0` clears it).
  Device check passed 2026-10-18 (Chrome opened twice; `file:` refused; underline colours confirmed
  by raw `screencap` pixels). Follow-ups: no pixel test on this host (Robolectric native graphics is
  unsupported on Linux aarch64) — replaced by a recording-`Canvas` call-sequence test; OSC 8 taps
  dispatch unconditionally by user decision; wide linked glyphs are in §2.4; the underline-colour
  erase/scroll/reflow plumbing has no direct test; the resolver warning is unreadable on this ROM
  (`shell` gets no logcat, `run-as` returned an empty buffer).

### 2.4 Known defects and bounded leftovers

- `[fixed 2026-11-04]` **unpaired surrogate from the IME crashed the session or typed a random
  character** — `sendTextToTerminal`/`ImeTextEncoder` sent every committed character straight to
  `inputCodePoint`, which forwards to `TerminalSession.writeCodePoint`:
  - a lone **low** surrogate was treated as an ordinary char and reached `writeCodePoint`, which
    rejects the surrogate range with `IllegalArgumentException` (a crash on malformed composition
    text that some keyboards emit);
  - a high surrogate followed by a **non-low** char was passed to `Character.toCodePoint`
    unvalidated, which silently produced an unrelated code point (`0xD800` + `a` → `0x10061`) and
    swallowed the second character.

  `ImeTextEncoder.encode` now emits `U+FFFD` for any unpaired surrogate (high without a following
  low, or a lone low) and leaves the character after a failed pair intact; valid pairs, ASCII, shift
  and the control remap are unchanged. Covered by `ImeTextEncoderTest.loneLowSurrogateIsReplacement`
  and `.highSurrogateFollowedByNonLowKeepsBothCharacters` (both red before the fix).
- `[fixed 2026-11-04, D-12]` **the in-app floating terminal crashed on creation** — three defects,
  all fixed and retested on NX769J / API 34:
  1. `Service`-added overlay `ComposeView`s had no `ViewTreeLifecycleOwner`/
     `ViewTreeSavedStateRegistryOwner`, so `AbstractComposeView.onAttachedToWindow` threw
     `IllegalStateException: ViewTreeLifecycleOwner not found`. `FloatOverlayView` now owns both
     (installed before `WindowManager.addView`, cleared and destroyed after removal).
  2. `FloatController` never applied a font size to its own `TerminalView`, so the first Compose
     layout threw `NullPointerException` from `TerminalView.updateSize()` (`mRenderer` null). The
     controller now applies the user's font size before layout, with a 14 sp fallback.
  3. The float `TerminalView` was constructed without `focusableInTouchMode` (the activity's view
     gets it from `view_terminal.xml`), so it rejected `requestFocus()` and the IME never served an
     input connection: the window was focused, the keyboard showed, but typing reached nothing. The
     controller now sets `isFocusableInTouchMode = true`.
  Retest also found and fixed a leak: `stop()` used `TermuxSession.finish()`, a no-op while the
  shell runs, so closing the window left its shell alive. `FloatSessionSource.finishFloatSession`
  now calls `TerminalSession.finishIfRunning()`. All D-12 device checks (create, drag/resize,
  collapse/expand, IME, overlay permission revoke while up, main-activity close, shell-exit
  collapse, tab-bar absence, close kills the shell) now pass; see `manual-checks.md` D-12. Evidence
  in `~/.tmp/opencode/device-checks-2026-11-04.md`.
- `[fixed 2026-11-04]` **renderer fanned out weight-2 grapheme continuations** — the emulator
  absorbs a ZWJ sequence into one 2-column cluster (`GraphemeClusters`, `TerminalRow.setChar`,
  `TerminalBuffer.getWidthForWrite`), but `TerminalRenderer.onDraw` advanced its `column` cursor by
  `WcWidth.width()` for every code point and only ate zero-width continuations (`charsForColumns`), so
  the width-2 continuations (ZWJ members) counted as fresh cells: on device `A👨‍👩‍👧B` drew the three
  members at columns 1/3/5 and `B` over ruler column 7 instead of 3 (4-column drift; evidence
  `~/.tmp/opencode/device-checks-2026-11-04.md` D-4). Fixed in `:terminal-view`: the run loop now
  walks `GraphemeClusters.clusterEnd/clusterWidth`, advances `column` by the cluster's width, and
  measures/draws the whole cluster as one span, and `charsForColumns` is cluster-aware. A shaped-run
  policy cut on a cell boundary can land inside a wide cluster, so the new `columnsForChars` widens
  that span to the cluster's real cell count instead of shifting every later glyph. `GraphemeClusters`
  and its two walk methods are now **public** (visibility only, no behavior change) because
  `:terminal-view` is a different package; that is the only `:terminal-emulator` change. Covered by
  `TerminalRendererRunTest.testZwjClusterOccupiesOneWideCell` and `testZwjClusterIsDrawnAsOneSpan`,
  both confirmed red against the pre-fix renderer. `./gradlew test` green. The **D-4 device re-check
  passed on 2026-11-04** on NX769J / API 34: `A👨‍👩‍👧B` puts `B` over ruler column 3 and the family
  draws as one contiguous glyph over cells 1–2 (pre-fix: `B` on column 7); the same run re-confirmed
  the combining-mark, flag and CJK lines. Evidence in `~/.tmp/opencode/device-checks-2026-11-04.md`
  §D-4 and `manual-checks.md` D-4.
- `[fixed 2026-11-04]` **selection end clamped at the first zero-width continuation** —
  `TextSelectionCursorController.getValidCurX` mapped a column to the selected text by walking `WcWidth` per
  code point and returned the current column as soon as a code point had width 0 (`cend == col`), so a
  right-hand selection could not extend past a ZWJ cluster or a combining mark; width-2 continuations
  (a second regional indicator, a skin tone modifier) inflated the column instead and could snap wrong.
  Pre-existing (not introduced by the renderer cluster fix) and `getSelectedText` itself was already
  cluster-aware (`TerminalRow.findStartOfColumn`). Fixed in `:terminal-view` on 2026-11-04:
  `getValidCurX` now delegates to a package-private static `validCursorColumn`, which walks the returned
  text with `GraphemeClusters.clusterEnd/clusterWidth` — the same rules as the write and render paths — so
  only a cluster's base width advances the column. A column inside a wide cell still snaps to that cell's
  end, a NUL still ends the walk, and a column past the line is still kept. Covered by the new
  `TextSelectionCursorControllerTest` (9 cases): `testColumnPastAZwjClusterIsKept`,
  `testColumnPastACombiningMarkIsKept` and `testColumnPastASkinToneClusterIsKept` were red against the
  pre-fix walk (returned 3/2/5 for 4/3/4), while the inside-a-cell snap, flag, ASCII, past-the-line and
  empty cases passed before and after. `./gradlew test` green (644 test methods, run for both the debug
  and release variants: 1288 executions, 0 failures). No new device check: this is a
  pure column mapping and plain/ligature selection columns are unchanged, so the existing selection device
  checks stay valid.
- `[fixed 2026-11-04, D-5 capture]` **OOM on rotate at the minimum font size** — rotating a long-lived
  instance portrait → landscape at font 10 px (354 columns) threw
  `java.lang.OutOfMemoryError` at `TerminalRow.<init>` ← `TerminalBuffer.resize` ←
  `TerminalEmulator.resizeScreen`. `TerminalBuffer.resize` eagerly allocated a `TerminalRow` for
  every reserved transcript row at the new column width (the constructor materializes only the screen
  rows), so a wide grid multiplied the transcript footprint. The default reservation is 2000 rows —
  the D-5 note's "10,000" came from a stale `TRANSCRIPT_ROWS` comment in `resize`, and the
  reservation is whatever `terminal-transcript-rows` says (2000 by default) — which is ~7 MB of cell
  arrays at 354 columns, and a raised
  `terminal-transcript-rows` multiplied it further (the 50000-row maximum would be ~177 MB). One
  `data_app_crash` record in dropbox at 2026-11-02 02:44:26 (PID 19366, uptime 705 s, build
  `2.0.0-beta+792b9f7`); it did **not** reproduce after a fresh start, so it is heap-pressure
  dependent (the capture run had produced a dozen screenshots in the same process lifetime). Fixed in
  `:terminal-emulator` on 2026-11-04: `TerminalBuffer.boundedTotalRows` prices the configured rows at
  an 80-column reference, so a wider grid reserves proportionally fewer rows and a width change never
  allocates more cells than the configured count would at 80 columns; the reservation never drops
  below the visible screen, and `TerminalEmulator` now remembers the configured count so rotating back
  to a narrow grid restores the full history. At 354 columns the default 2000 rows reserves 451 rows
  (~1.6 MB instead of ~7 MB). Covered by `TranscriptMemoryTest` (4 cases; the wide-resize and
  screen-floor cases were red against the pre-fix code), full `./gradlew test` green (648 test
  methods, 1296 executions, 0 failures). The D-5 arm stays a device stress run: the OOM was
  heap-pressure dependent and did not reproduce on a fresh start, so a repeat run could not prove it.
  Evidence in `~/.tmp/opencode/device-checks-2026-11-02.md` D-5.
- `[done 2026-10-25, ad380602]` **palette changes with no other output** — the simple fix was chosen:
  `updateBackgroundColor()` now invalidates the terminal view even in wallpaper/custom-image mode, so
  a palette-only change repaints. The robust palette-version signal remains deferred (§5.4).
- `[done 2026-10-25, d784313c]` **wide linked glyph** — `getLinkIdAt` now resolves the trailing half
  of a wide cell to its base column's link (`TerminalRow.getLinkIdResolvingWide`); covered by
  `LinkRegistryTest.testWideGlyphLinkResolvesOnBothColumns`.
- `[done 2026-10-25, 980b29c6]` **focus events (DECSET 1004)** — `TerminalEmulator.sendFocusEvent()`
  writes `CSI I`/`CSI O` when the mode is set, wired from `TerminalView.onWindowFocusChanged()`.
- `[done 2026-10-25, 3e0f1931]` **escape-sequence errors silently swallowed** — the
  `LOG_ESCAPE_SEQUENCES` flag was removed; `logError`/`finishSequenceAndLogError` and the three
  inline diagnostics now log unconditionally, matching the rest of the emulator.
- `[done 2026-10-25, 0e37e230]` **underline-colour plumbing without a direct test** —
  `TerminalBufferUnderlineColorTest` now covers erase/scroll/resize of the array; the plumbing was
  already correct.
- `[decided]` **OSC 8 taps dispatch unconditionally** — the existing word-URL tap stays gated by
  `open terminal transcript URL on click`; an explicit program-stamped link is not a transcript URL
  guess. Recorded in the WS5 spec §4.5.
- `[closed]` the "24-bit white collides with the foreground sentinel" edge cannot occur (a 24-bit
  ARGB value always has `0xff` in its high byte, so it never equals `COLOR_INDEX_FOREGROUND`), and
  `58;5;256` means "default foreground".
- `[declined]` a `CellAttributes` value type for the travelling `style`/`linkId`/`underlineColor`
  triple, and moving the link lookup out of the pure static resolver — both wider than WS5.
- `[decided 2026-10-27]` inherited markers, triaged (all predate this work):
  - `ITermImage` `name`/`size` keys — **intentional ignore**: optional iTerm2 labels Termux doesn't
    need (no file save, size hint redundant with the image data).
  - iTerm2 file-saving to Downloads — **intentional limitation**: a non-inline (`inline=0`) image is
    dropped; saving to disk isn't supported. Removed the empty `else {}` (cosmetic).
  - Reverse wrap-around (`DECSET 45`) — **not implementing**: niche mode, grouped with the other
    ignored modes (80→132); xterm itself deprecated it.
  - `ECH` clears attributes — **open (conformance)**: ECH fills with the current style but the spec
    says it should reset attributes; parks with the xterm-conformance remainder (WS5).
  - `TerminalRow` "unassigned get width 0" — **defensive note**: unassigned code points are already
    replaced with `U+FFFD` in `processByte` before reaching `setChar`, so the note is effectively
    unreachable; left as a guard comment.
  - Cursor-field threading — **decided**: the emulator runs on the main thread (documented in
    `TerminalSession`); the defensive `if (column < 0)` guards a past
    `ArrayIndexOutOfBoundsException` and stays.
  - The long tail of `// Ignore.` branches — **intentional**: purpose-skipped control characters.

### 2.5 Compatibility crux (why this is a program, not one spec)

`ShellUtils` reaches into `TerminalBuffer` / `TerminalEmulator` for transcript dumping, so the
terminal grid model is effectively part of `termux-shared`'s published API; any restructure must keep
those accessors as delegating shims. `TermuxTerminalSessionClientBase` /
`TermuxTerminalViewClientBase` are the base classes plugins extend, so new callbacks must be Java 8
`default` no-op methods on `TerminalSessionClient` / `TerminalViewClient` (desugared on minSdk 21),
then wired through the `*Base` classes. Risk to verify in design: confirm Java 8 `default` desugaring
covers the plugin apps' toolchains, not just this repo. `:termux-shared` also carries UI (`ExtraKeysView`,
`ReportActivity`, `TextIOActivity`) and drags `:terminal-view` upward — noted, out of scope, don't
regress it. Graphics must keep today's `TerminalSixel` / `ITermImage` behavior while the kitty path
is unified.

**Audit 2026-10-25 (WS6).** Verified the WS1–WS5 engine work kept the published surface intact:
`ShellUtils`'s transcript accessors (`getEmulator` → `getScreen` →
`getTranscriptTextWithFullLinesJoined`/`getTranscriptTextWithoutJoinedLines`) and
`TermuxPropertyConstants`'s cursor/transcript constants are all still public and real, and the only
interface callback the engine workstreams added (`TerminalViewClient.onLinkClicked`) is a `default`
no-op. No code change was needed. Two follow-ups, neither a defect in this repo:

- `[fixed 2026-11-04]` **late `TerminalSessionClient` methods were abstract, not `default`** —
  `onTerminalCursorStateChange` (2021-05-15), `getTerminalCursorStyle` (2021-06-10),
  `onPasteTextFromClipboard` (2021-07-19) and `setTerminalShellPid` (2022-01-22) were added to the
  published interface after the plugin apps already implemented it, so a plugin implementing
  `TerminalSessionClient` directly would break; they were safe only because the `*Base` classes supply
  the no-op/`null` defaults. Not part of the engine program; a convention inconsistency against the
  "new callbacks must be `default`" rule. All four are now `default`: the three callbacks do nothing
  and `getTerminalCursorStyle()` returns `null`, which `TerminalEmulator.setCursorStyle()` already
  treats as "use the default". Javadoc on each says the default exists so older implementors keep
  compiling. The setters and getter are binary-compatible changes; `RecordingClient` in
  `TerminalEmulatorLoggingTest` no longer overrides them, so the test source proves the defaults
  compile and behave the same, and the new `TerminalSessionClientTest` asserts all four are `default`
  with `Method.isDefault()`. `./gradlew test` green (644 test methods, run for both the debug and
  release variants: 1288 executions, 0 failures).
- `[open]` **plugin-app `default`-method desugaring (minSdk 21)** — confirm the companion apps'
  AGP/D8/R8 toolchains desugar Java 8 `default` methods, not just this repo. External build check,
  not verifiable from this tree.

---

## 3. Code and test gaps

### 3.1 Code gaps

- `[done 2026-11-17]` **Kotlin conversion of `:terminal-view` and the remaining `:app` Java** —
  spec `docs/specs/2026-11-16-kotlin-conversion-design.md`, plan
  `docs/plans/2026-11-16-kotlin-conversion.md`. Both modules are Kotlin-only now; no `.java` file is
  left under `terminal-view/src/main` or `app/src/main`, the `src/main/java` layout is kept so the
  published `sourceJar` still carries sources, and no behavior, class name or Java-visible signature
  changed (the JNI `getZip`, the fragment/`ContentProvider` nested names, and the
  `getInstance(Context)` statics were checked with `javap`). Batches, one revertable commit each:
  `133e8c90` (Kotlin enabled, `TerminalViewClient`), `ba44efcf` (leaf classes), `ab00c9c2` (text
  selection), `e6b6c3e2` (renderer, gesture recognizer), `4ae6905b` (`TerminalView`), `fe4a1010`
  (app `terminal/` package, `TermuxActivity`, `TermuxService`), `9d0aac6d` (settings fragments),
  `735ecb62` (the app tail). Tests stayed Java and were not touched; the suite is 905 tests, 0
  failures. Two modules were deliberately left out: `:terminal-emulator` (the emulation hot loop —
  a rewrite there would be judged on throughput, so it needs its own spec) and `:termux-shared`
  (published to JitPack and consumed by the plugin apps, so its public statics are a compatibility
  surface).

- `[resolved 2026-10-18]` **WS4 kitty graphics was ack-only** — it parsed and answered `OK` without
  decoding, storing, placing or drawing; `deleteTerminalBitmaps(...)` had no call site and chunk
  accumulation did not exist. Resolved by the WS4 completion plan (commits above). Kept here as the
  cautionary record: a recorded status can overstate the code.
- The bounded defects in §2.4.
- `[done 2026-10-25, a48ca5ae]` **`:termux-shared` has no `src/test` at all** — the `src/test` source set
  now exists (`testOptions.unitTests.returnDefaultValues` enabled, `junit` exercised) and covers the
  pure path helpers (`FileUtils` basename/dirname, `ShellUtils` argument setup). Android-touching
  logic stays instrumented (`androidTest`). More pure helpers can be added over time.
- `[partly done 2026-11-04]` **no tests at all** for `:terminal-emulator` `TerminalBitmap`,
  `TerminalSession`; `:terminal-view` `TerminalView` (1,585 lines),
  text-selection controllers/handles (only the column mapping of `TextSelectionCursorController` is
  covered, by `TextSelectionCursorControllerTest`); `:app` `TermuxService`,
  `TermuxTerminalSessionActivityClient`, `TermuxInstaller`, `RunCommandService`,
  `TermuxDocumentsProvider`, and every Compose screen. (`TerminalColors` covered by
  `TerminalColorsTest` `c7ec9c33`; `TerminalColorScheme` and `getPerceivedBrightnessOfColor` covered
  by `TerminalColorSchemeTest` `3b4cc11e`.)
  - `[done 2026-10-27]` `ITermImage` now covered by `ITermImageTest` (15 tests: the OSC 1337 state
    machine, `readArguments` key parsing incl. `inline`/`preserveAspectRatio`/`width`/`height`
    units, singlepart + multipart read flow, and base64 decode/`FAILED` transitions).
  - `[done 2026-10-27]` `TerminalSixel` colour logic covered by `TerminalSixelTest` (initial
    palette, `setColor` bounds, `setRGBColor` 0-100→ARGB scaling + clamping). `readData` stays
    device-only (native graphics unsupported on this aarch64 host).
  - `[done 2026-11-04]` `TerminalSession` now covered where a real pty is not needed, by
    `TerminalSessionTest` (11 tests): the UTF-8 encoding of `writeCodePoint` at every length boundary
    (1/2/3/4-byte, `ESC` prefix), rejection of the surrogate range and of values above `U+10FFFF`,
    writes dropped when no pty is live, and the pre-start getters (`getEmulator`/`getTitle`/`getCwd`
    null, pid and exit status 0, unique handle). The negative-code-point case was red: `-1` passed
    the guard and was queued as a stray `0xFF` byte, so the guard now rejects `codePoint < 0`. Still
    device-only: `initializeEmulator`, the reader/writer/waiter threads, `MainThreadHandler`, and
    `finishIfRunning` (JNI `createSubprocess`, `Os.kill`).
  - `[open]` `TerminalBitmap` remains untested: its surface is `Bitmap`/`Canvas` pixel ops (native
    graphics), so it shares the Sixel `readData` limitation.
  - `[limitation]` `:terminal-view` `TerminalView` + selection controllers: the pure seams are
    already covered (`LinkUrisTest`, `ShapedRunPolicyTest`, `TerminalRendererRunTest`,
    `TerminalViewLinkHitTest`, plus `RenderBandTest`, `KeyCodePointsTest`, `ImeTextEncoderTest` and
    `TerminalGeometryTest` from the §3.2 split); the rest is drawing/gesture/selection coordination
    that needs Robolectric View infra, not JVM unit tests.
  - `[done 2026-11-04]` `:app` `TermuxDocumentsProvider` covered by `TermuxDocumentsProviderTest`
    (13 Robolectric tests): child listing, query case-insensitivity, recursive search, directory MIME
    type, unknown-extension fallback, collision-free `createDocument` (file and directory), and the
    document-id checks. Four red cases were real defects: `isChildDocument` accepted a sibling that
    only shared the parent's string prefix (`.../home2/x` inside `.../home`), `queryChildDocuments`
    and the search walk NPE'd on any unlistable path (a file or a permission-denied directory —
    `File.listFiles()` returns null), and search compared the raw query against a lower-cased name,
    so an upper-case query matched nothing. MIME extension mapping cannot be asserted here: the
    Robolectric `MimeTypeMap` returns null for every extension, so only the directory and
    octet-stream fallbacks are covered.
  - `[limitation]` the remaining `:app` classes (`TermuxService`,
    `TermuxTerminalSessionActivityClient`, `TermuxInstaller`, `RunCommandService`, Compose screens):
    inspected 2026-10-27 — most of these are Intent/Service/Activity/Compose glue that delegates to
    `:termux-shared` (`IntentUtils`/`DataUtils`/`FileUtils`/`TermuxFileUtils`). **Corrected
    2026-11-15:** the "no JVM-testable pure logic" claim was wrong. Four pieces are pure and are now
    tracked in the app-layering work (`docs/specs/2026-11-15-app-layering-design.md`):
    `TermuxActivity.refreshSessionsBar` stable-id allocation, `TermuxActivity.downscaleForBlur`,
    `TermuxService.processShellCreateMode`, and `TermuxActivity.moveSessionById` index maths.
- `[done 2026-10-25]` commented-out assertions in live test files: removed the stale ones that
  referenced removed APIs (`getLine(0)`, `line.mText`) across `TerminalRowTest`, `TerminalTest`
  and `ScrollRegionTest`; restored the two that still held (`TerminalRowTest` unassigned-code-point
  width, `ResizeTest` background colour). Two stay disabled because they encode stale behaviour —
  one of them is the UTF-8 regression below.
- `[done 2026-10-27]` **UTF-8 decoder deviation** — `0xe0 0x80` (an overlong 3-byte lead whose
  successor is out of range) decoded to a single `U+FFFD` instead of the two the "Best Practices"
  note requires. Fixed by validating the first successor byte's range for the restricted leads
  `0xE0`/`0xF0`/`0xF4` (Unicode Table 3-7); an out-of-range successor is now emitted as its own
  replacement rather than being consumed. Re-enabled the disabled assertion and added
  `testOutOfRangeSuccessorByteNotConsumed` + `testInRangeSuccessorByteStillDecodes`. Surrogate
  collapse (`0xED`) is deliberately unchanged.

### 3.2 Code-shape debt

- God classes: `TerminalEmulator.java` 3,376 lines, `TerminalView.kt` 1,857, `FileUtils.java` 2,113,
  `TermuxConstants.java` 1,338 (re-measured 2026-11-18; figures inside the dated entries below are as
  measured on their own dates). Behavior-preserving splits only.
  - `[done 2026-10-27]` sixel parsing extracted from `TerminalEmulator` into `SixelParser`:
    `processSixelDcs` → `SixelParser.process` (returns a `Result` carrying the buffer-capacity
    hint) and `getSixelDcsSetupArgs` → `SixelParser.parseSetupArgs` (returns a `Setup`
    distinguishing "sixel" from "definitively not sixel"). Covered by `SixelParserTest`.
    Remaining sequence in `docs/plans/2026-10-27-terminal-emulator-god-class-split.md` (kitty APC,
    then OSC 1337).
  - `[done 2026-10-27]` kitty graphics APC block extracted into `KittyGraphicsHandler` (owns the
    transfer state, decoder and image store; emulator keeps the APC dispatch + decoder test seam).
    `mSession`/`setCursorCol`/`doLinefeed` relaxed to package-private for the handler. The
    21-test `TerminalEmulatorKittyTest` suite stays green.
  - `[deferred 2026-10-27]` OSC 1337 (`File=`/`MultipartFile=` inline block) left in `doOsc`. It is
    untested end-to-end (the inline-placement path needs native graphics, unavailable on this
    host) and its `case 1337` control flow (break/fall-through/`return` plus the cross-command
    `mITermImage` state) makes a faithful extraction risky for marginal gain — `ITermImage` already
    holds the image state machine.
  - `[done 2026-10-27]` `FileUtils` path/name helpers extracted into a package-private `FilePaths`
    (`getCanonicalPath`, `normalizePath`, `sanitizeFileName`, `isPathInDirPath(s)`,
    `getFileDirname`/`getFileBasename`/`getFileBasenameWithoutExtension`); `FileUtils` keeps the
    public methods as delegating shims. Covered by `FilePathsTest`.
  - `[deferred]` rest of `FileUtils` (create/copy/move/delete/read-write/permissions, ~1,800 lines)
    left in place — filesystem-side-effect-heavy and in the published `:termux-shared` surface;
    split it only after those paths get unit coverage.
  - `[non-goal]` `TermuxConstants` is a deliberately-centralized constants registry (the
    companion-app integration surface resolves against it, per `AGENTS.md`), not a behavior god
    class; splitting it fragments that surface for no behavior gain.
  - `[done 2026-11-04]` `TerminalView` pure seams extracted, 1,661 → 1,585 lines. Four package-private
    stateless helpers in `com.termux.view`, importing no `android.*` type, each with a plain-JUnit test
    class in `:terminal-view`:
    - `RenderBand.forClip(...)` owns the dirty-band row range from `onDraw`: one row of clip slack, the
      clamp to the visible band and the empty-band fallback, with `ceilDivInt` moved in —
      `RenderBandTest`, 8 cases.
    - `KeyCodePoints.applyControl` / `.normalizeExternalKeyboard` own the `inputCodePoint` control table
      and the bluetooth modifier-letter substitution; the `eventSource` gate stays in the view because
      it is a property of the event — `KeyCodePointsTest`, 5 cases.
    - `ImeTextEncoder.encode(CharSequence, boolean)` owns the `sendTextToTerminal` loop: surrogate
      pairing, shift uppercasing, `\n` → `\r` and the `+ 96` control remap — `ImeTextEncoderTest`,
      11 cases, two of which pin the unpaired-surrogate fix in §2.4.
    - `TerminalGeometry.cursorColumn/.cursorRow/.pointX/.pointY` own `getCursorX`/`getCursorY` and
      `getPointX`/`getPointY`; the unexplained `- 40` in `getCursorY` is preserved verbatim and pinned
      by a test rather than derived or corrected — `TerminalGeometryTest`, 5 cases.
    `TerminalView` keeps every public method and delegates, so the `:app` and companion-app surface is
    unchanged; `:app:assembleDebug` passes after the split.
  - `[open]` `TerminalView` View-bound half — the gesture listener, scroll/viewport
    (`onTouchEvent`, `computeVerticalScroll*`), selection delegation, autofill, cursor blinker
    (`Handler`) and session binding (`attachSession`/`updateSize`/`setTopRow`) stay in the class: they
    need Robolectric `View`/`Handler` infra, which `:terminal-view` still does not have. Add that test
    infrastructure to the module before attempting them. The same applies to the `eventSource` gate
    around `KeyCodePoints.normalizeExternalKeyboard` and to the shift-then-control combination, which
    spans `ImeTextEncoder` and `inputCodePoint`: both are only reachable through a real `TerminalView`.
- `[deferred]` two settings stacks: the legacy Java `PreferenceFragment` screens under
  `app/src/main/java/com/termux/app/fragments/settings/` (13 files) and the Compose appearance
  screen under `ui/appearance/`. Both write preferences, so the settings surface has two owners.
  Consolidation is its own spec cycle; the direction is recorded in
  `docs/plans/2026-10-16-settings-ia.md`. Excluded from the 2026-11-15 app-layering work.
- `[non-goal]` `:termux-shared` depends on `:terminal-view`, and 7 files in `:termux-shared` import
  terminal internals. Inverted relative to a clean layering, but `:termux-shared` is published to
  JitPack and consumed by the plugin apps, so the dependency cannot be removed without breaking
  them. Accepted 2026-11-15.
- `[done 2026-11-15]` app layering: `docs/specs/2026-11-15-app-layering-design.md` and
  `docs/plans/2026-11-15-app-layering.md`. Layer packages inside `:app` (`domain`/`data`/`ui`)
  enforced by `LayeringTest`, plus extraction of session mapping and wallpaper loading from
  `TermuxActivity` and the notification and wake-lock split of `TermuxService`. No new Gradle
  module. Plugin-facing surfaces frozen and checked mechanically. Commits: Task 2 `ea1464ad`,
  Task 3 `6b3e7e4e`, Task 4 `40a7d8e7`, Task 5 `640e11d0`, Task 6 `33506f57`, Task 7 `f07167dd`,
  Tasks 8–9 `a08922af` (notification and wake-lock extractions folded into one commit on this
  branch; Task 8's earlier `1718f3c6` is not on the current history). `wc -l` before →
  after: `TermuxActivity.java` 1593 → 1477, `TermuxService.java` 1057 → 961. Left in
  `TermuxActivity`: composition/wiring, terminal/session UI orchestration, settings/property
  reactions and remaining activity-level behavior. Left in `TermuxService`: service lifecycle and
  foreground/start-stop flow, shell/session/task and plugin intent execution, and float/session
  orchestration glue. Task 9 test adapted for Termux Robolectric conscrypt env (see SDD ledger
  ruling). `:app:testDebugUnitTest` passed; `:app:assembleDebug` failed in this aarch64 Termux env
  at `:app:configureNdkBuildDebug` (`ndk-build` host CPU `aarch64` unsupported), unrelated to these
  Java/Kotlin changes.

- `[done 2026-11-18]` **Compose god-composables** (audit 2026-11-18):
  `app/src/main/java/com/termux/app/ui/sessions/SessionsBar.kt` is 895 lines with two oversized
  composables — `SessionsBar(...)` at `SessionsBar.kt:105` is 352 lines and `SessionTab(...)` at
  `SessionsBar.kt:490` is 223. Extract the tab row, its drag/long-press handle and
  `SessionActionsMenu` (`SessionsBar.kt:713`, 50 lines) into `ui/sessions/components/`,
  leaving `SessionsBar` as the state and `LazyRow` orchestrator. Behavior pinning is partial:
  `SessionsBarLayoutTest` and `SessionsStateTest` cover layout maths and state, not the composables.
  Split before adding anything else to the tab. Done in `5754d157`: the tab, its menu, the state
  dot, the empty-state row and the previews each have a file, `SessionsBar.kt` is 455 lines and no
  file in `ui/sessions/` exceeds 500.
- `[open]` `TerminalShellHost(...)` at `app/src/main/java/com/termux/app/ui/ShellHost.kt:185` is one
  278-line private composable (file total 462). Split the host body from the chrome wiring. Pure
  `:app` extraction, no plugin surface moves.
- `[open]` **Kotlin-conversion line growth** in two files the 2026-11-04/2026-11-15 splits had just
  reduced: `terminal-view/src/main/java/com/termux/view/TerminalView.kt` 1,585 → 1,857
  (`4ae6905b`) and `app/src/main/java/com/termux/app/TermuxActivity.kt` 1,477 → 1,681
  (`fe4a1010`, with `TermuxService.kt` at 976 from 961). The extracted seams are untouched; the
  growth is the Java → Kotlin conversion plus later cursor/render work. Re-split on the same terms
  as the earlier passes; the `TerminalView` View-bound half still needs Robolectric `View`/`Handler`
  infrastructure (`[open]` above).
- `[open]` **two UI state conventions in `:app`**: `ui/backup/` is `BackupViewModel` plus an
  immutable `BackupUiState`; `ui/appearance/` and `ui/snippets/` are Compose-observable `*State.kt`
  classes (`AppearanceState`, `SnippetsState`) with no ViewModel. Both work; choose one before the
  next screen copies whichever it lands next to. Naming note: no `*Contract.kt` exists in the repo
  and none is required — `*State.kt` is this repo's name for the same thing (an external audit
  flagged the absence; it was a false positive).
- `[record 2026-11-18]` file-size audit re-measure (500-line threshold): `TerminalEmulator.java`
  3,376, `FileUtils.java` 2,113, `TerminalView.kt` 1,857, `TermuxActivity.kt` 1,681,
  `TermuxConstants.java` 1,338, `TerminalBuffer.java` 1,215, `TermuxService.kt` 976,
  `ExtraKeysView.java` 909, `SessionsBar.kt` 895, `PackageUtils.java` 830,
  `TermuxTerminalViewClient.kt` 761, plus 14 more between 501 and 750. Only `SessionsBar.kt` is new
  relative to the god classes tracked above. The audit's other two findings need no work: the 13
  comment blocks longer than 5 lines carry security or scroll rationale (e.g.
  `RunCommandService.kt:146`), and no UI module lacks a state/contract file.

### 3.3 Post-review hardening — closed 2026-11-17

- `[done 2026-11-17]` the 22 findings (F1–F22) of the `v2.0.0-beta..e3f68eb1` review are fixed in
  `plans/2026-11-17-post-review-hardening.md`, one commit per finding: received-file name
  containment and one-shot intent handling, report-info read containment, documents-provider
  containment, broadcast export flags, sixel capacity clamp, bitmap row walking and resize sizing,
  renderer run flush and row snapshots, palette paste without an emulator, key-bar focus handback,
  snippet cap, bounded iTerm import, pending-font cleanup, symlink-safe old-file walk, handle tint
  clearing, wallpaper error containment, foreground-command cache, and `keycap-visuals` wiring.
- `[non-goal]` rewriting the seven historical commits whose messages do not use the capitalized
  conventional types: the messages are in the past and rewriting `master` would break every clone;
  the rule is enforced from here on.
- The settings-search defect (fixed 2026-11-18, §1.3) was independent of this review.
- `[done 2026-11-17]` the extra-key corner affordance shows the popup's own display text when it is
  at most three code points, and keeps the chevron otherwise (`ExtraKeysAffordance.popupBadge`);
  device check D-33 in `manual-checks.md`.
- `[done 2026-11-17]` release assets are release-signed: the workflow builds `assembleRelease` and
  reads the release key from the four `TERMUX_RELEASE_*` secrets; `TERMUX_RELEASE_SIGNING_REQUIRED=1`
  fails the build when any is missing, and `apksigner verify` gates the upload. Device check D-34.

---

## 4. Build, platform and dependency upgrades

### 4.1 Build and platform debt

- `app/build.gradle` carries a keystore password in plaintext next to the committed
  `testkey_untrusted.jks` (upstream inheritance; debug-only, still unappealing).
- `[done 2026-10-27]` R8/release hardening: removed `-dontobfuscate` so release `minifyEnabled true`
  now actually obfuscates. The three published library modules ship `-keep class com.termux.{shared,view,terminal}.**`
  as `consumerProguardFiles` (the old `proguardFiles` in the release build type did not get packaged
  as consumer rules), so the companion-app integration surface and Java-serialized models keep their
  names. The app keeps JNI entry points; manifest components and the default native-method rule cover
  the rest. Verified `:app:assembleRelease` builds and the mapping keeps library source classes
  (`Error -> Error`) while obfuscating `com.termux.app.**` (359 classes). Compose needed no explicit
  rules (the Compose compiler + AGP handle it). Device verification of the obfuscated APK is
  `manual-checks.md` D-11, closed 2026-11-02 on the maintainer's verdict (the bootstrap path needs a
  wiped-data install, which was declined).
- `targetSdkVersion=28` while `compileSdkVersion=36`. Raising `targetSdkVersion` is a **dropped**
  decision, not a todo (it was mis-numbered as roadmap item 15 before the consolidation).
- No lint baseline; suppressions are ad hoc, with `SharedPreferenceUtils` carrying eight
  `@SuppressLint("ApplySharedPref")` (synchronous `commit()` hidden on the main path).
- `[done 2026-10-27]` `:app`'s `javaCompile` depended on downloading ~115 MB of bootstrap zips, and
  `./gradlew clean` deleted them, forcing a full re-download. The `clean` block that removed
  `src/main/cpp/bootstrap-*.zip` is gone; the zips are cached downloads whose SHA-256 is checked by
  `downloadBootstrap()`, so they self-invalidate on a version bump. Verified the four zips survive
  `./gradlew :app:clean`.
- `[done 2026-10-27]` Java source/target raised 1.8 → 17 (and Kotlin `jvmTarget` 1.8 → 17) across
  all four modules, aligning the bytecode with the Temurin-17 CI/JitPack toolchain. Core-library
  desugaring stays enabled (it backports `java.*` APIs on minSdk 21–23 and is independent of the
  source/target level). `./gradlew test` and `:app:assembleRelease` both pass at 17. The published
  `:termux-shared` AAR now carries Java-17 bytecode, so companion apps need a D8/R8 that desugars it
  (see the §2.5 companion-app desugaring note).
- `terminal-view/build.gradle` now uses the AndroidX instrumentation runner (`823542d7`) — the former
  audit entry about `android.support.test.runner.AndroidJUnitRunner` is closed.
- `[open]` Migrate the APK file-name hook off the legacy `applicationVariants` API before AGP 10.
  AGP 9.1's new `VariantOutput`/`ApkOutput` DSL has no file-name property, so the build currently
  relies on `android.newDsl=false`. Options: a post-`assemble` copy task that writes the expected
  names next to AGP's defaults, or renaming the released assets and updating both release workflows.
- `[open]` Re-evaluate `-dontoptimize` in `app/proguard-rules.pro` when R8 hardening is taken up.
  AGP 9.1 no longer ships `proguard-android.txt`; the build now uses
  `proguard-android-optimize.txt` plus the explicit `-dontoptimize` flag, which keeps today's
  non-optimized release behavior. Enabling optimization is a deliberate follow-up, not this
  migration.

### 4.2 Toolchain and CI

- `[done 2026-11-05]` AGP 9.1.1 migration (`1ff791f8` wrapper + this commit): Gradle wrapper
  9.2.1 → 9.3.1, AGP 8.13.2 → 9.1.1, and `:app` moved to AGP's built-in Kotlin (the
  `org.jetbrains.kotlin.android` plugin is gone; KGP/Compose 2.4.21 stay on the buildscript
  classpath, and the Compose plugin is unchanged). `kotlinOptions` was removed because the Kotlin
  target now follows `compileOptions.targetCompatibility` (verified: Kotlin classes are major
  version 61). The three Java-only libraries set `enableKotlin = false`, which removes their Kotlin
  tasks and keeps `kotlin-stdlib` out of the published POM (verified: 0 occurrences). Each library
  pins `defaultConfig.aarMetadata.minCompileSdk = 1` because AGP 9 would otherwise require consumers
  to use compileSdk 36; `minAndroidGradlePluginVersion` stays `1.0.0`. `android.newDsl=false` keeps
  the `applicationVariants` APK-rename hook that both release workflows depend on (five
  `termux-app_<tag>_<abi>.apk` names verified with and without `TERMUX_APK_VERSION_TAG`).
  `getDefaultProguardFile('proguard-android.txt')` is disallowed in AGP 9, so all four modules use
  `proguard-android-optimize.txt` and `app/proguard-rules.pro` adds `-dontoptimize` to preserve the
  previous non-optimized release behavior; `packagingOptions` was renamed to `packaging`. AGP
  auto-installed build-tools 36.0.0. Verified: `help`, `test` (701 methods, 0 failures), `lint`,
  `:app:assembleDebug`/`assembleRelease` (five debug splits, release mapping keeps the three
  library packages), both `assembleDebugAndroidTest` tasks, the three release AARs, and
  `publishToMavenLocal` for all three libraries (Dokka javadoc path). `gradle/verification-metadata.xml`
  regenerated.
  - `android.onlyEnableUnitTestForTheTestedBuildType` now defaults to `true`, so `./gradlew test`
    runs each unit test once (debug only): 701 methods instead of the historical 1,402 executions,
    and `testReleaseUnitTest` no longer exists. Historical counts in earlier entries are left as
    they were recorded.
- `[done 2026-10-27]` CI hardening (mostly): added a `gradle` dependabot ecosystem, a `lint` step to
  the unit-tests workflow, and a release-build job (`assembleRelease`, exercises R8) to the build
  workflow. Fixed the three pre-existing lint errors that would have failed CI (two
  `onBackPressed` `MissingSuperCall` overrides now `@SuppressLint`-annotated; the
  `CoreLibDesugaringV1` error was suppressed at the time, and that suppression was removed on
  2026-11-05 when `desugar_jdk_libs` moved to 2.1.5).
- `[done 2026-11-04]` Dependabot remediation for the 67 open Maven alerts: `guava` 24.1-jre →
  33.8.0-android in `app` and `termux-shared`; `commons-io` removed from `:termux-shared` (its
  `FileUtils`/`TermuxUtils`/`TermuxShellUtils` uses replaced with local `java.io` helpers, and
  `deleteFilesOlderThanXDays` now takes a `java.io.FileFilter`, so a caller passing
  `TrueFileFilter.INSTANCE` or `null` still compiles); Kotlin and Compose plugin 2.2.21 → 2.4.21;
  and root `build.gradle` forces the patched build-time transitive versions (netty 4.1.137.Final,
  Bouncy Castle 1.86, jose4j 0.9.6, jdom2 2.0.6.1, commons-compress 1.28.0, protobuf 3.25.5,
  Guava 33.8.0-jre, commons-io 2.22.0). Verified with `test`, `:app:assembleDebug`,
  `buildEnvironment`, a per-configuration resolved-version report, and the `:termux-shared`
  instrumented `FileUtilsTests` on NX769J / API 34. GitHub must rescan before the alerts clear.
- `[done 2026-11-05]` dependency verification (Gradle `verification-metadata.xml`) — generated
  `gradle/verification-metadata.xml` with 570 components and SHA-256 checksums for every artifact the
  CI task set resolves. Produced by
  `./gradlew --write-verification-metadata sha256 help test lint :app:assembleDebug :app:assembleRelease -PndkVersion=30.0.16248370`
  plus `:termux-shared:assembleDebugAndroidTest` and `:terminal-view:assembleDebugAndroidTest` for the
  test-APK dependencies. (`-PndkVersion=30.0.16248370` is only this Termux workspace's NDK 29
  workaround; CI uses the pinned 29 and needs no flag.) Verified strict, with no write flag:
  `test`, `:app:assembleDebug`, `:app:assembleRelease` and both `assembleDebugAndroidTest` tasks are
  green. Enforcement was proven by corrupting one `.module` checksum, which failed the build with
  `Dependency verification failed for configuration 'classpath'`, then restoring it.
  - Maintenance: any new or bumped dependency (including Dependabot PRs) fails the build until the
    metadata is regenerated with the same task list, so a dependency change must carry that
    regenerated file in the same commit.
  - `[limitation]` checksums only (`verify-signatures=false`), so the file protects against a
    changed artifact on a mirror or in the cache, not against a malicious edit of the metadata
    itself. PGP signature verification would need a managed keyring and is deferred.
- `[done 2026-10-27]` JitPack versioning: the three library modules now read their publication
  version from the `VERSION` env var (`System.getenv("VERSION") ?: '0.118.0'`), so a JitPack build
  publishes with the requested tag/commit instead of the hardcoded default. Verified by generating
  the POM with `VERSION` set.
- `[done 2026-11-05]` CI caching: added `actions/cache@v4` for `~/.gradle/caches` + `~/.gradle/wrapper`
  to `run_tests.yml` and both `debug_build.yml` jobs, plus an `actions/cache@v4` for
  `~/.m2/repository/org/robolectric` (where Robolectric resolves its 249 MB `android-all` jar,
  outside the Gradle cache). This closes the follow-up flagged in the compileSdk 37 spec §7.
- `[done 2026-11-05]` Dependabot remediation (2 open alerts): the two remaining Maven alerts were
  `com.google.guava:guava` (33.6.0-jre, from Robolectric 4.17's transitives) and
  `org.apache.httpcomponents:httpclient` (4.5.6, from AGP's Unified Test Platform `ddmlib`
  transitive). Root `build.gradle` now force-pins `httpclient`/`httpmime` 4.5.14 and `httpcore`
  4.4.16 globally, and the guava `33.8.0-jre` force now also covers `*LintChecksClasspath` (the
  Robolectric config the old predicate missed). Verified no configuration resolves an un-patched
  guava or httpclient.

### 4.3 Upgrade roadmap

Items 1–15 are done; dependency refresh is one commit per dependency, ascending blast radius, with
`compose-bom` last and separate. Rows 20–22 and the AGP rows that were gated on the toolchain are
closed, and the compileSdk 37 slice closed rows 26, 29, 30, 31 and 32. R8/release build is a
separate architectural slice. Device checks are the final row and live in `manual-checks.md`.

**Dependency refresh — 2026-10-25.** `minSdk` raised 21 → 23 (`34cb3d9d`), which unblocked the
androidx bumps that only needed minSdk 23: `annotation` (`1dfb9203`), `window` (`e1264dea`),
`appcompat` (`e28e386a`), `material` (`f3050cbc`), `activity-compose` (`4a046f5f`). The rest are gated
on bigger changes: `core` 1.19.x, `lifecycle-runtime-compose` 2.11.x and `compose-bom` 2026.09.00 all
require **AGP 9.1.0+** (repo is on 8.13.2); `hiddenapibypass:6.2` needs **compileSdk 37** (which breaks
Robolectric 4.10's `ShadowActivity`, and Robolectric can't be bumped here — its `android-all` jar
can't be fetched). `commons-io` no longer needs `desugar_jdk_libs` 2.x: `:termux-shared` dropped the
dependency (2026-11-04) and its remaining uses are implemented with `java.io` helpers, and `guava` is
on the Android flavor (`33.8.0-android`), which does not reference `java.nio.file`. `desugar_jdk_libs` was raised 1.1.5 → 2.1.5
on 2026-11-05 (see below), so the `CoreLibDesugaringV1` lint suppression is gone and the remaining Java
APIs in app code can be backported. `compileSdk` stays 36.

**Dependency refresh — 2026-11-05.** `desugar_jdk_libs` 1.1.5 → 2.1.5 in `:app` and `:termux-shared`,
and the `disable 'CoreLibDesugaringV1'` lint suppressions were deleted from both modules (the check no
longer fires, so lint is no longer hiding it). Verified with
`./gradlew lint test :app:assembleDebug :app:assembleRelease` (0 lint errors) and both
`assembleDebugAndroidTest` tasks; `gradle/verification-metadata.xml` regenerated in the same commit.
2.x no longer needs the 1.x workaround and backports `java.nio.file` under minSdk 23. The
`commons-io` and `guava` rows below are closed too: `commons-io` is no longer a dependency of any
module (only the root `force` pin remains as a transitive guard) and `guava` is already on
`33.8.0-android` from the 2026-11-04 Dependabot remediation.

**Dependency refresh — 2026-11-05 (AGP 9 landed, refresh still blocked).** The AGP 9.1.1 migration
(`d582f402`) closed the AGP gate, but rows 26, 29 and 32 do not move: their artifacts now require
**compileSdk 37**. Measured from the published AAR metadata: `androidx.core:core:1.19.1` and
`core-ktx:1.19.1` report `minCompileSdk=37`; `lifecycle-runtime-compose:2.11.0` reports
`minCompileSdk=37` and `minAndroidGradlePluginVersion=9.1.0`; `compose-bom:2026.09.00` maps `ui` and
`foundation` to 1.12.1, which also report `minCompileSdk=37` (`material3` 1.4.0 is 35 and `runtime`
1.12.1 is 34, so the BOM is gated by `ui`/`foundation`). The build fails at
`:app:checkDebugAarMetadata` with `Dependency 'androidx.core:core:1.19.1' requires libraries and
applications that depend on it to compile against version 37 or later of the Android APIs.` The
attempted `core` bump was reverted (both modules stay on 1.13.1) and
`gradle/verification-metadata.xml` was restored, so the tree is the AGP 9.1.1 state. Raising
`compileSdk` to 37 is its own slice: it breaks Robolectric 4.10 (`ShadowActivity`), and the
Robolectric bump (row 30) is itself blocked because its `android-all-instrumented` jar cannot be
fetched here, so row 31's `hiddenapibypass:6.2` stays behind the same wall.

**Dependency refresh — 2026-11-05 (compileSdk 37 raised).** `compileSdkVersion` 36 → 37 in
`gradle.properties` (one of the six `compileSdk 37` commits). `targetSdkVersion` and `minSdkVersion`
are unchanged, and the three library modules keep `aarMetadata.minCompileSdk = 1`, so the published
AAR floor does not move. A probe before this slice corrected the blocker story: the `android-37`
platform's package name is `platforms;android-37.0`, and the Robolectric break was compile-time
(`android.hardware.fingerprint.FingerprintManager` removed from `android-37`'s `android.jar`,
referenced transitively), fixed by the Robolectric 4.17 bump in row 30 — no source change. Verified
with `test` (701 methods), `lint`, `:app:assembleDebug`/`assembleRelease` (five APKs, release
mapping present), and the release AARs still reporting `minCompileSdk=1`. CI needs no workflow
change: AGP auto-installs the `android-37.0` platform.

| # | Item | Status | Notes |
|---|------|--------|-------|
| 1 | Gradle 10 DSL syntax (`prop = value`) | done | `c9826aaa` |
| 2 | Move `FileUtilsTests` out of the published artifact (instrumented) | done | `c389a88d` |
| 3 | AndroidX instrumentation test runner (`terminal-view`) | done | `823542d7` |
| 4 | Docs re-homing (`docs/ideas/` → `docs/ledger/`) | done | `26388328`, consolidated 2026-10-24 |
| 5 | R8/release build hardening (keep rules for `:termux-shared` public API, JNI entry points, Compose) | done | separate slice, affects published artifact |
| 6 | God-class splits (§3.2) | done 2026-11-04 | `891efd6c`, `890fb560`, `efce70a2`, `ba133ab8`; `TerminalView` View-bound half open in §3.2 |
| 7 | `:termux-shared` unit tests (§3.1) | done 2026-11-04 | `de273500`, `967aa522` (13 Robolectric cases, 4 defects) |
| 8 | `LOG_ESCAPE_SEQUENCES = false` silent protocol errors | done | `3e0f1931` |
| 9 | Focus events (DECSET 1004) never emitted | done | `980b29c6` |
| 10 | Wallpaper-mode palette repaint | done | `ad380602` |
| 11 | Wide-glyph OSC 8 hit-test | done | `d784313c` |
| 12 | Java 1.8 → 17 | done | align toolchain; check desugaring |
| 13 | CI hardening (lint, release build, dependabot for Gradle, dependency verification) | done | workflows; dependency verification generated 2026-11-05 (§4.2) |
| 14 | CI bootstrap re-download | done | avoid re-downloading cached zips unnecessarily |
| 15 | JitPack versioning | done | library publishing/versioning |
| 16 | Session restore | deferred | assessed 2026-11-01; own cycle if revisited (§5.1) |
| 17 | Termux:Float (shape A vs B) | done | in-app float via `:app` (§5.2) |
| 18 | 256-colour palette | done | full 256-colour support (§5.3) |
| 19 | xterm conformance + WS6 audit | to do | conformance/shim gaps (§2.2) |
| 20 | Dependency refresh — `desugar_jdk_libs` 1.1.5 → 2.1.5 | done | 2026-11-05; `CoreLibDesugaringV1` suppressions removed |
| 21 | Dependency refresh — `commons-io` 2.5 → 2.22.0 | closed | no longer a module dependency (2026-11-04); root `force` pin 2.22.0 kept as transitive guard |
| 22 | Dependency refresh — `guava` 24.1-jre → 33.7.2-jre | done | already `33.8.0-android` (2026-11-04); `listenablefuture` shim kept |
| 23 | Dependency refresh — `androidx.window` 1.1.0 → 1.5.1 | done | `e1264dea` |
| 24 | Dependency refresh — `appcompat` 1.6.1 → 1.8.0 | done | `e28e386a` |
| 25 | Dependency refresh — `material` 1.12.0 → 1.14.0 | done | `f3050cbc` |
| 26 | Dependency refresh — `core` 1.13.1 → 1.19.1 | done 2026-11-05 | landed after compileSdk 37; `:app` + `:termux-shared` |
| 27 | Dependency refresh — `annotation` 1.9.0 → 1.11.0 | done | `1dfb9203` |
| 28 | Dependency refresh — `activity-compose` 1.11.0 → 1.13.0 | done | `4a046f5f` |
| 29 | Dependency refresh — `lifecycle-runtime-compose` 2.9.4 → 2.11.0 | done 2026-11-05 | landed after compileSdk 37; `:app` only |
| 30 | Dependency refresh — `robolectric` 4.10 → 4.17 | done 2026-11-05 | real blocker was compile-time `FingerprintManager` removal from android-37 `android.jar` (transitive), not `ShadowActivity`; fixed by the bump, no source change |
| 31 | Dependency refresh — `hiddenapibypass` 6.1 → 6.2 | done 2026-11-05 | landed after compileSdk 37; runtime hidden-API access change |
| 32 | Dependency refresh — `compose-bom` 2025.11.01 → 2026.09.00 | done 2026-11-05 | **last and separate**; `ui`/`foundation` 1.12.1, `material3` 1.4.0, `runtime` 1.12.1 |
| 33 | Device and manual checks | to do | **last overall** — `manual-checks.md` |

---

## 5. Epics and open decisions

### 5.1 Session restore — `[deferred]`

See §1.1 — would need its own cycle (snapshot + opt-in respawn). **Deferred 2026-11-01**: assessed
as more complicated than the value it provides right now (persistence layer + respawn wiring across
`TermuxService`/`TermuxShellManager` + a setting, with no real process-state recovery possible). Not
dropped permanently — revisit only if a simpler shape (e.g. reopen the same commands without a new
persistence model) becomes worth it.

### 5.2 Termux:Float — `[done]`

Shape B chosen: fold float into `:app` as a first-class floating terminal — no `com.termux.window`
APK, no `sharedUserId`, no new Gradle module. Ships under package `com.termux.app.ui.floating` (spec
`specs/2026-11-01-termux-float-in-app-design.md`, plan `plans/2026-11-01-termux-float-in-app.md`): a
draggable bubble that expands into a movable/resizable window over the fork's `TerminalView`, running
a dedicated float session created by `TermuxService.createFloatSession(...)` and held by a
`FloatController` (not added to the tab list). Entered from the sessions-bar "+" menu ("New floating
session"), and toggled via a "Float" action on the foreground notification plus the hidden
`termux_float` Settings entry. Overlay permission is checked before showing
(`ACTION_MANAGE_OVERLAY_PERMISSION` when missing) rather than swallowed, as upstream did. Device
checks are deferred to `manual-checks.md` D-12.

### 5.3 256-colour palette — `[done]`

Reversed from the earlier `[non-goal]`: the appearance screen now exposes all 256 indexed colours.
`TerminalColorSet.ansi` holds `color0`..`color255`; presets author only the 16 ANSI colours and take
the engine-default extended palette (6×6×6 cube + grayscale ramp). The screen adds a collapsible
"256-colour palette" section — a 16×16 grid of swatches whose last-tapped swatch is described verbosely
(`ANSI n · name`, `RGB cube · red=… green=… blue=…`, `Grayscale · step …`) via `colorLabel(index)`, and
the colour picker title repeats that label plus the hex. The store reads/writes the full 256 and keeps
round-tripping keys it does not manage. `colorLabel`, the extended palette, and the store round-trip
are unit-tested.

### 5.4 Palette-version signal — `[done]`

Reversed from the earlier `[non-goal]`. `TerminalColors` now carries a monotonic change counter,
bumped only when a colour actually changes value (`tryParseColor`, `reset(int)`, `reset()`, all
guarded against no-ops). `TerminalView` records the counter after each draw and folds it into its
`overlayChanged` check, and a new `onPaletteChanged()` invalidates only when the counter differs
from the last drawn value. `updateBackgroundColor()` now calls `onPaletteChanged()` instead of a
blanket `invalidate()`, so a palette-only change still repaints but a no-op colour write (a theme
re-applying the same 256-colour palette every prompt) does not. The counter behaviour is unit-tested
in `TerminalColorsTest`.

### 5.5 termux-backup phase 2 (in-app UI) — `[done 2026-11-17]`

Phase 1 shipped the environment backup/restore tool as a script only (`scripts/termux-backup`, spec
`docs/specs/2026-11-15-termux-backup-restore-design.md`, plan
`docs/plans/2026-11-15-termux-backup-restore.md`). Phase 2 is now in the app, from spec
`docs/specs/2026-11-17-termux-backup-ui-design.md` and plan
`docs/plans/2026-11-17-termux-backup-ui.md`: a **Settings → Advanced → Backup & restore** row opens
`BackupActivity`, which provisions `scripts/termux-backup` from APK assets into
`$PREFIX/lib/termux-backup`, runs the CLI in a hidden `TermuxService` session (`run.lock` keeps one
run at a time), polls a JSONL progress file (`--progress-file`), and exposes backup, verify, restore,
delete, prune and status. Restore is confirmed in-app, keeps a safety archive and reinstalls packages
by default, and asks for a restart afterwards. SAF export and import copy the same five-file backup
directory through `ACTION_OPEN_DOCUMENT_TREE`, verifying `SHA256SUMS` in both directions and deleting
partial output. Commits: scripts/Task 1 `6f5c40e7`, packaging `fb693503`, command model `aa82e8ca`,
progress parser `524783c4`, JSON results `f1de385b`, provisioner `15c6f214` + `d9c2719d`, settings row
`2e51eb9d`, hidden runner `ff4ef5f6`, screen + list `2e134192`, restore/verify/delete/prune `1bf4dd4b`,
SAF validation `0baf950b`, SAF transfer `9f6a9d1c`, coordinator + pickers `1bee3f54`. Unit tests:
`:app:testDebugUnitTest` green; `scripts/termux-backup/tests/run.sh` green.

Still deferred, each a separate decision later:

- **GitHub / Google Drive backends** — remote storage needs credentials, retention rules and a sync
  story; local-only until then.
- **Scheduling** — no timer, no `termux-job-scheduler` wiring, no boot hook.
- **Encryption** — archives with credentials are only as safe as the storage root; `--exclude-secrets`
  is the only mitigation today.
- **SAF is directory-based** — the app exports and imports the unarchived backup directory through the
  system picker; a single-file archive for sharing is still open.

Device checks for phase 1 live in `manual-checks.md` D-17 to D-20; phase 2 checks are D-35 to D-42.

---

## 6. Decisions and non-goals

### 6.1 Scrollback depth — diagnosed 2026-10-19, probed 2026-10-20, no code change

- **Report:** "terminal doesn't scroll back far enough."
- **Diagnosis:** depth is the `terminal-transcript-rows` property, clamped by
  `TerminalEmulator.java:238–240` (min 100, **max 50000**, **default 2000**), read once per new
  session at `TermuxService.java:586` and sized into the `TerminalBuffer` ring (capacity = screen rows
  + transcript rows; rows allocate lazily at ~0.5 KB each, so a full 50k transcript ≈ 25 MB). No
  runtime trim exists and `TerminalView` scrolls to `getActiveTranscriptRows()`.
- **Why it felt broken:** the device's `~/.termux/termux.properties` set
  `terminal-transcript-rows = 50000`, but that file was created **after** the app process had
  started. Properties load at startup, so live sessions were created with the 2000-line default.
  `termux-reload-settings` reloads the shared properties singleton but cannot resize an existing
  ring: only a **new session** (Ctrl+Alt+C) or an app restart picks up the value.
- `[decided 2026-10-19]` 50000 is enough — no change to `DEFAULT_TERMINAL_TRANSCRIPT_ROWS`,
  `TERMINAL_TRANSCRIPT_ROWS_MAX` or the reload semantics.
- `[probed 2026-10-20]` the config-level diagnosis is confirmed; the numeric pixel measurement is
  **not reproducible enough on this device to quote as a fact**. Measured cell height 34 px, view
  top y = 250; the blinking cursor alone produces ~350–480k pixel diff between captures (larger than
  a real scroll), and `adb shell input swipe` has non-deterministic fling momentum
  (`Scroller.fling`), so repeated identical drags reported the oldest line anywhere between ~400 and
  ~3000. Also: `input text` needs `%s` for a space and only reaches the terminal when it holds the
  IME. A deterministic redo would drive `TerminalView.setTopRow()` through an instrumentation test
  instead of synthetic swipes. No user-visible change follows.

### 6.2 GitHub code scanning — 33 alerts, resolved 2026-11-17

The GitHub security tab held 33 open alerts (24 `java/path-injection`, 6 `actions/missing-workflow-permissions`,
2 `java/android/unsafe-content-uri-resolution`, 1 `java/zipslip`).

Fixed in code:
- The four workflows that lacked a token scope now declare `permissions: contents: read`
  (`debug_build`, `gradle-wrapper-validation`, `run_tests`, `trigger_library_builds_on_jitpack`).
- `TermuxInstaller.getZipEntryTarget` rejects empty, absolute and escaping zip entry names and checks
  canonical containment in the staging directory (covered by `TermuxInstallerTest`).
- `TermuxOpenReceiver.openFile` canonicalizes the URI path, checks it against resolved Termux-files
  and external-storage roots, and opens a file built from that canonical path.
- `FileReceiverActivity` resolves a `file://` share to its canonical path and rejects anything under
  `/data/`, so an external app cannot make Termux read its own private files.

Final state: 12 alerts fixed, 24 dismissed, 0 open. Quirk worth remembering: CodeQL rejects a
`startsWith` guard whose argument is a compile-time constant containing `/data` (it treats that as a
blocklist), so the file-provider check only sanitizes when the allowed root is resolved at runtime
(`File(...).getCanonicalPath()`).

Dismissed as false positives:
- 22 `java/path-injection` in `FileUtils`' permission helpers. They are generic library functions that
  must accept a caller-chosen path; CodeQL only accepts a sanitizer when the path is proven under a
  fixed trusted root, which such an API cannot have. The only user-controlled callers are
  `RunCommandService` (working directories are restricted to `TERMUX_FILES_DIR` paths, and a client
  holding `RUN_COMMAND` plus `allow-external-apps` already has arbitrary command execution).
- 2 `java/android/unsafe-content-uri-resolution`: the system picker URI in
  `DisplayPreferencesFragment` is produced by this app's own intent, and the `ACTION_SEND` URI in
  `FileReceiverActivity` is the documented share target — the sending app granted the read.

### 6.3 Non-goals — decided against, recorded so they are not mistaken for gaps

From the shell/sessions spec: no rewrite of terminal emulation, input or scrollback; no change to the
package or applicationId; no `:termux-shared` public API change; `:terminal-emulator` and
`:terminal-view` compile and test unchanged.

From the appearance plan: no `:terminal-emulator` / `:terminal-view` changes; no `:termux-shared`
diff; no live-apply (explicit Apply was chosen); no new Gradle module and no new third-party
dependency (the font picker is hand-written).

Also dropped: raising `targetSdkVersion`; tab pinning; the Android-integration milestone; A3 step 2;
the settings root-screen tidy/`termux.properties` surfacing; the setup wizard.

---

## 7. Doc hygiene state

- `docs/plans/2026-10-14-in-app-terminal-appearance.md` has a `## Status` section recording C1–C4
  shipped/merged (PR #1, merge `4b3a0143`).
- The 2026-10-20 hygiene pass replaced the four false "awaiting review" status lines, added status
  lines to the three plans that lacked one, and corrected the WS1 line. Plans keep their task lists
  as the original execution record.
- 2026-10-24 consolidation: `repo-audit.md`, `engine-upgrade.md`, `float-port.md` and
  `upgrade-roadmap.md` were absorbed here and deleted; `manual-checks.md` is the only other ledger
  file. Remaining `docs/ideas/` links in specs and plans were repointed.
- `docs/superpowers/` is gitignored (`.gitignore:49`) and stays local-only; `docs/ideas/` holds only
  a `.gitkeep` now.
- `docs/en/index.md` is upstream-inherited scaffolding (from the termux.github.io i18n commit), not
  this fork's debt.

---

## 8. Device and manual checks — the last thing to do

Every device, emulator or human-eyeball check lives in
[`manual-checks.md`](manual-checks.md), with its status and evidence. Nothing in this file should be
scheduled after them; when the code work above settles, run that file end to end.
