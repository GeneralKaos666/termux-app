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
- The remaining onboarding and settings checks are device-only: `manual-checks.md` D-7 and D-8.

### 1.4 Appearance — shipped, one device pass outstanding

- Seven colour presets ship; Solarized Light, Gruvbox Dark and One Dark have had a machine contrast
  pass but still need the human aesthetic pass (`manual-checks.md` D-5).
- `[done 6c606187]` dead string resources removed (lint `UnusedResources` no longer lists any unused
  string). Other unused resources (7 session/current-session drawables, `preference_markdown_text.xml`,
  3 styles) were later deleted in `b99c79e1`.
- `[done 349cbd66]` cursor style and blink are nullable overrides (`null` = follow
  `termux.properties`), with Default chips and a Reset that stages `null`; a colour-only Apply no
  longer creates a cursor override.
- `[limitation]` with a live (animated) wallpaper, `WallpaperManager.getDrawable()` yields no usable
  frame (black), so the system-wallpaper background works only with a static image. Ruled a
  device/engine quirk seen on the ZTE live wallpaper, not app code.

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
- `[open]` inherited markers worth a decision, not necessarily a fix: `ITermImage.java:273`
  (name/size keys unsupported), `TerminalEmulator.java:3062` (iTerm2 file-saving to Downloads
  dropped), `:1837` (reverse wrap-around TODO), `:2299-2302` (ECH clears attributes?),
  `TerminalRow.java:296` (unassigned characters get width 0), `:3497` (cursor field threading), plus
  a long tail of `// Ignore.` branches on purpose-skipped controls. All predate this work.

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

- `[open]` **`TerminalSessionClient` query methods are abstract, not `default`** —
  `getTerminalCursorStyle()` (and the pre-existing `setTerminalShellPid`, `onPasteTextFromClipboard`)
  were added as abstract methods; plugins are safe only because the `*Base` classes supply the
  no-op/`null` defaults. A plugin implementing `TerminalSessionClient` directly would break. Not part
  of the engine program; a convention inconsistency against the "new callbacks must be `default`" rule.
- `[open]` **plugin-app `default`-method desugaring (minSdk 21)** — confirm the companion apps'
  AGP/D8/R8 toolchains desugar Java 8 `default` methods, not just this repo. External build check,
  not verifiable from this tree.

---

## 3. Code and test gaps

### 3.1 Code gaps

- `[resolved 2026-10-18]` **WS4 kitty graphics was ack-only** — it parsed and answered `OK` without
  decoding, storing, placing or drawing; `deleteTerminalBitmaps(...)` had no call site and chunk
  accumulation did not exist. Resolved by the WS4 completion plan (commits above). Kept here as the
  cautionary record: a recorded status can overstate the code.
- The bounded defects in §2.4.
- `[done 2026-10-25, a48ca5ae]` **`:termux-shared` has no `src/test` at all** — the `src/test` source set
  now exists (`testOptions.unitTests.returnDefaultValues` enabled, `junit` exercised) and covers the
  pure path helpers (`FileUtils` basename/dirname, `ShellUtils` argument setup). Android-touching
  logic stays instrumented (`androidTest`). More pure helpers can be added over time.
- `[open]` **no tests at all** for `:terminal-emulator` `TerminalBitmap`, `TerminalSixel`,
  `ITermImage`, `TerminalSession`; `:terminal-view` `TerminalView` (1,661 lines),
  text-selection controllers/handles; `:app` `TermuxService`,
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
  - `[open]` `TerminalBitmap` and `TerminalSession` remain untested: `TerminalBitmap`'s remaining
    surface is `Bitmap` pixel ops (native graphics) and `TerminalSession` is process/JNI-bound.
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

- God classes: `TerminalEmulator.java` 3,876 lines, `TerminalView.java` 1,661, `FileUtils.java`
  2,044, `TermuxConstants.java` 1,338. Behavior-preserving splits only.
  - `[done 2026-10-27]` first `TerminalEmulator` slice: the sixel DCS-data parser
    (`processSixelDcs`, ~245 lines) moved to `SixelParser.process`, returning a
    `SixelParser.Result` carrying the buffer-capacity hint; covered by `SixelParserTest`.
    Remaining sequence in `docs/plans/2026-10-27-terminal-emulator-god-class-split.md`
    (`getSixelDcsSetupArgs`/`startIfSixelDcs`, then kitty APC, then OSC 1337).

---

## 4. Build, platform and dependency upgrades

### 4.1 Build and platform debt

- `app/build.gradle` carries a keystore password in plaintext next to the committed
  `testkey_untrusted.jks` (upstream inheritance; debug-only, still unappealing).
- `-dontobfuscate` in both ProGuard rule files makes release `minifyEnabled true` mostly inert.
  R8/release hardening needs keep rules for the `:termux-shared` public API, JNI entry points and
  Compose — a separate slice because it affects the published artifact.
- `targetSdkVersion=28` while `compileSdkVersion=36`. Raising `targetSdkVersion` is a **dropped**
  decision, not a todo (it was mis-numbered as roadmap item 15 before the consolidation).
- No lint baseline; suppressions are ad hoc, with `SharedPreferenceUtils` carrying eight
  `@SuppressLint("ApplySharedPref")` (synchronous `commit()` hidden on the main path).
- `:app`'s `javaCompile` depends on downloading ~115 MB of bootstrap zips; `./gradlew clean` deletes
  them (documented in `AGENTS.md`). Avoid the unnecessary re-download.
- Java source/target is 1.8 with core library desugaring; CI and JitPack pin Temurin 17 and this
  workspace runs Gradle on Java 21. Aligning the toolchain on 17 needs a desugaring check.
- `terminal-view/build.gradle` now uses the AndroidX instrumentation runner (`823542d7`) — the former
  audit entry about `android.support.test.runner.AndroidJUnitRunner` is closed.

### 4.2 Toolchain and CI

- CI hardening: lint, release build, dependabot for Gradle, dependency verification.
- JitPack versioning for the published library modules.

### 4.3 Upgrade roadmap

Items 1–4 are done in this slice. Remaining items are **to do**; dependency refresh is one commit per
dependency, ascending blast radius, with `compose-bom` last and separate. R8/release build remains a
separate architectural slice. Device checks are the final row and live in `manual-checks.md`.

**Dependency refresh — 2026-10-25.** `minSdk` raised 21 → 23 (`34cb3d9d`), which unblocked the
androidx bumps that only needed minSdk 23: `annotation` (`1dfb9203`), `window` (`e1264dea`),
`appcompat` (`e28e386a`), `material` (`f3050cbc`), `activity-compose` (`4a046f5f`). The rest are gated
on bigger changes: `core` 1.19.x, `lifecycle-runtime-compose` 2.11.x and `compose-bom` 2026.09.00 all
require **AGP 9.1.0+** (repo is on 8.13.2); `hiddenapibypass:6.2` needs **compileSdk 37** (which breaks
Robolectric 4.10's `ShadowActivity`, and Robolectric can't be bumped here — its `android-all` jar
can't be fetched); `desugar_jdk_libs` 1.x→2.x is a major desugaring change and the prerequisite for
`commons-io`/`guava` (and `commons-io` 2.22.0 contradicts the `AGENTS.md` "must remain 2.5" note).
`compileSdk` stays 36.

| # | Item | Status | Notes |
|---|------|--------|-------|
| 1 | Gradle 10 DSL syntax (`prop = value`) | done | `c9826aaa` |
| 2 | Move `FileUtilsTests` out of the published artifact (instrumented) | done | `c389a88d` |
| 3 | AndroidX instrumentation test runner (`terminal-view`) | done | `823542d7` |
| 4 | Docs re-homing (`docs/ideas/` → `docs/ledger/`) | done | `26388328`, consolidated 2026-10-24 |
| 5 | R8/release build hardening (keep rules for `:termux-shared` public API, JNI entry points, Compose) | to do | separate slice, affects published artifact |
| 6 | God-class splits (§3.2) | to do | behavior-preserving |
| 7 | `:termux-shared` unit tests (§3.1) | to do | expand beyond the instrumented harness |
| 8 | `LOG_ESCAPE_SEQUENCES = false` silent protocol errors | done | `3e0f1931` |
| 9 | Focus events (DECSET 1004) never emitted | done | `980b29c6` |
| 10 | Wallpaper-mode palette repaint | done | `ad380602` |
| 11 | Wide-glyph OSC 8 hit-test | done | `d784313c` |
| 12 | Java 1.8 → 17 | to do | align toolchain; check desugaring |
| 13 | CI hardening (lint, release build, dependabot for Gradle, dependency verification) | to do | workflows |
| 14 | CI bootstrap re-download | to do | avoid re-downloading cached zips unnecessarily |
| 15 | JitPack versioning | to do | library publishing/versioning |
| 16 | Session restore | to do | restore sessions across process death (§5.1) |
| 17 | Termux:Float (shape A vs B) | to do | not chosen yet (§5.2) |
| 18 | 256-colour palette | to do | full 256-colour support (§5.3) |
| 19 | xterm conformance + WS6 audit | to do | conformance/shim gaps (§2.2) |
| 20 | Dependency refresh — `desugar_jdk_libs` 1.1.5 → 2.1.5 | deferred | major desugaring change; prerequisite for `commons-io`/`guava` |
| 21 | Dependency refresh — `commons-io` 2.5 → 2.22.0 | deferred | contradicts `AGENTS.md` "must remain 2.5"; needs `desugar_jdk_libs` 2.x |
| 22 | Dependency refresh — `guava` 24.1-jre → 33.7.2-jre | deferred | keep `listenablefuture` shim; do after `desugar_jdk_libs` |
| 23 | Dependency refresh — `androidx.window` 1.1.0 → 1.5.1 | done | `e1264dea` |
| 24 | Dependency refresh — `appcompat` 1.6.1 → 1.8.0 | done | `e28e386a` |
| 25 | Dependency refresh — `material` 1.12.0 → 1.14.0 | done | `f3050cbc` |
| 26 | Dependency refresh — `core` 1.13.1 → 1.19.1 | blocked | needs AGP 9.1.0+ (repo on 8.13.2) |
| 27 | Dependency refresh — `annotation` 1.9.0 → 1.11.0 | done | `1dfb9203` |
| 28 | Dependency refresh — `activity-compose` 1.11.0 → 1.13.0 | done | `4a046f5f` |
| 29 | Dependency refresh — `lifecycle-runtime-compose` 2.9.4 → 2.11.0 | blocked | needs AGP 9.1.0+ |
| 30 | Dependency refresh — `robolectric` 4.10 → 4.17 | blocked | new `android-all-instrumented` jar cannot be fetched here |
| 31 | Dependency refresh — `hiddenapibypass` 6.1 → 6.2 | blocked | needs compileSdk 37 (breaks Robolectric 4.10) |
| 32 | Dependency refresh — `compose-bom` 2025.11.01 → 2026.09.00 | blocked | **last and separate**; Compose 1.12.1 needs AGP 9.1.0+ |
| 33 | Device and manual checks | to do | **last overall** — `manual-checks.md` |

---

## 5. Epics and open decisions

### 5.1 Session restore

See §1.1 — needs its own cycle (snapshot + opt-in respawn).

### 5.2 Termux:Float — `[deferred]`, shape not chosen

**Probed 2026-10-16** against a shallow clone of `https://github.com/termux/termux-float` at `master`
(`75352bd`). The probe clone was kept outside the repo and has since been deleted; re-clone if this
is picked up. Nothing in this repo was modified.

Why a plain clone does not work: it is a whole separate Gradle build (`include ':app'`, AGP 8.7.3,
its own wrapper and `gradle.properties`), so a nested project is never built by this repo's Gradle
and the nested `.git` becomes an embedded repo. It links published Maven artifacts
(`termux-shared`/`terminal-view` `8aca6dbbf4`), so a real port must rewrite those two lines to
`project(':termux-shared')` / `project(':terminal-view')`. It pins Java 11 + desugaring against this
repo's Java 1.8 / AGP 8.13.2. Licensing is **not** a blocker (GPLv3 on both sides).

What already ships here: `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `VIBRATE` and `WAKE_LOCK` in
the manifest; matching debug signing (`testkey_untrusted.jks`, alias `alias`) so `sharedUserId` works
for debug; the whole float contract in `TermuxConstants` (`TERMUX_FLOAT_PACKAGE_NAME`
`com.termux.window`, activity/service names, `ACTION_SHOW/HIDE/STOP_SERVICE`, properties paths,
notification channel/id `1339`) plus `TermuxFloatAppSharedPreferences`; the matching
`TermuxSession.execute(...)` signature; both float client base classes; and the hidden
`termux_float` Settings entry, which starts working as soon as `com.termux.window` is installed.

What is genuinely missing: `TermuxFloatActivity`, `TermuxFloatService`, `TermuxFloatView`, the float
view client, any `WindowManager` overlay usage, `include ':float'`, and a counterpart to
`TermuxFloatAppSharedProperties`.

Move inventory (9 Java files, ~750 LOC): `TermuxFloatView` 359, `TermuxFloatViewClient` 263,
`TermuxFloatService` 255, `TermuxFloatSessionClient` 191, `FloatingBubbleManager` 149,
`TermuxFloatApplication` 39, `TermuxFloatAppSharedProperties` 36, `TermuxFloatPermissionActivity` 34,
`TermuxFloatActivity` 21; plus 3 layouts, ~9 drawables, `values/{dimens,strings,styles}.xml`, 4
notification PNGs and `testkey_untrusted.jks`; manifest adds 2 activities + 1 service.

Shapes (neither chosen):

- **A — new `:float` module, faithful port.** `include ':float'`, move the sources, point the two
  deps at the local projects, per-module `minSdkVersion 24`, carry over `manifestPlaceholders`, keep
  `applicationId com.termux.window` + `sharedUserId`. A plugin-style second APK, cheapest (order of a
  day), still re-syncable with upstream; costs a second APK in CI and a copy to maintain.
- **B — fold float into `:app`** as a first-class floating terminal over the existing
  `TermuxService` sessions. No second APK or `sharedUserId`, better fit for the Compose shell host,
  but real design work (overlay chrome, which session, IME/insets) and it drifts from upstream.

Recommendation recorded at the time: A if the goal is "have Termux:Float again without maintaining a
separate repo"; B only if floating terminals become part of this fork's UX.

Gotchas to verify during design: per-module minSdk override; `TermuxFloatView.initFloatView()` calls
`TermuxFloatAppSharedPreferences.build(getContext(), true)`, which exits the app when
`applicationId != com.termux.window` (B must swap it); `TermuxFloatService.initializeFloatView()`
catches the overlay failure instead of checking `Settings.canDrawOverlays()` ("always returns false,
perhaps due to sharedUserId") — do not "fix" blindly, verify on device (`manual-checks.md` D-10);
port `outputFileName`, `packagingOptions` exclusion, desugaring and the
`TERMUX_FLOAT_APP__BUILD_APP_VERSION_NAME` / `_APK_VERSION_TAG` env overrides with their semver
validation; release signing must match too, not just debug; CI must decide what to attach to releases;
adding a module is a deliberate deviation from the `AGENTS.md` "concentrate changes in `:app`" rule.

To resume: confirm the goal and pick A or B (A recommended), then run the normal brainstorming →
spec → plan cycle.

### 5.3 256-colour palette

Undecided. The appearance screen exposes only `color0`..`color15` today; unmanaged keys are preserved
untouched. The question is whether to surface the extended palette.

### 5.4 Palette-version signal

Only needed if the robust fix for the wallpaper-mode repaint (§2.4) is chosen; then it needs its own
spec cycle.

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

### 6.2 Non-goals — decided against, recorded so they are not mistaken for gaps

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
