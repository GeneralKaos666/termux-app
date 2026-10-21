# Outstanding work — the not-yet-done ledger (NOT a plan)

**Date:** 2026-10-16
**Status:** capture only. Nothing here is approved, scheduled, or designed. Each entry records what is
known and what the next step would cost. Items marked *needs spec* require the full cycle
(brainstorming → spec → plan) before any code.
**Origin:** user instruction — "remove what's up next in readme and add what we havent done in
docs/superpowers/ideas". The public README now carries a compact version of this list as a table; this
file is the detailed record, with the grounding for each item, plus the gaps found while building the
sessions reorder and the appearance screen.
**Path classification:** mixed — see the legend. This file is not a spec and authorizes nothing.

**Related:** `specs/2026-10-10-shell-and-sessions-ux-design.md` (§7 milestones),
`plans/2026-10-10-shell-sessions.md`, `plans/2026-10-14-in-app-terminal-appearance.md`,
`ideas/2026-10-14-terminal-engine-upgrade-idea.md`,
`ideas/2026-10-16-termux-float-port-idea.md`, `ideas/2026-10-18-repo-audit.md` (snapshot of the code
and docs; this ledger still owns the decisions).

**Legend:** `[bounded]` small and self-contained · `[needs spec]` architectural, needs its own cycle ·
`[deferred]` postponed on purpose · `[check]` built but not verified on device ·
`[probed]` verification attempted on device, with the outcome recorded ·
`[limitation]` accepted as-is for now · `[non-goal]` decided against.

---

## 1. Sessions — durability

Moved here verbatim in spirit from the README's former "What's next" section.

- **Session durability / restore** — `[needs spec]` a running shell cannot outlive the app process.
  Sessions live in `TermuxShellManager.mTermuxSessions` in memory and the PTY is in-process, so
  `TermuxService.onStartCommand` returning `START_NOT_STICKY` means process death ends them. Realistic
  "durability" = persist a session snapshot (name/title, command, cwd where capturable) and **respawn**
  fresh sessions on the next launch, opt-in. Write it up separately before building it.
  (Grounding: appearance plan, Appendix — Direction B.)
- **Ordering advice already recorded** — the appendix recommended "appearance first, then
  B-ergonomics, then B-restore". Appearance, drag-to-reorder, rename and close have all since shipped.

## 2. Sessions bar — two defects found and deliberately left unfixed

Both were reported when the tap/reorder bug was fixed and consciously left out of that commit's scope.

- **Positional fallback tab titles** — `[done 2026-10-16, commit 1d973de4]` the fallback number is now derived
  from the session's stable id, so reordering a session with no name/terminal title keeps its label. The
  dead `number` parameter was removed from `sessionItem(...)` and the `i + 1` argument dropped at
  `TermuxActivity.java:769`; covered by `SessionItemMappingTest`.
- **Only the active tab exposes the ⋮ menu** — `[done 2026-10-16, commit e9434cf7]` every tab now shows
  its own overflow, so rename / close / move are reachable without switching first. The long-press
  divergence noted below was resolved by keeping long-press as the drag handle and the ⋮ as the menu
  entry point on every tab.

## 3. Device checks not yet run

- **B1 onboarding** — `[check]` `plans/2026-10-10-shell-sessions.md:440` records: "Not exercised:
  cancel/retry after failure, rotation mid-install, back key (left for a later pass)."
- **Appearance presets** — `[check]` the device acceptance run exercised four presets (Termux Default,
  Nord, Dracula, Solarized Dark). `ColorSchemes.kt` now ships seven, so **Solarized Light, Gruvbox Dark
  and One Dark have not been individually checked on device**. A short manual checklist now lives at
  `docs/ideas/ideas.md` (tracked).
- **WS3 deferred checks** — `[check]` workstream 3 (run shaping + ligatures) has its spec at
  `specs/2026-10-18-ws3-shaping-ligatures-design.md` §7, which lists what must run on a phone once one is
  available: the Minikin-default probe, ligature toggle on/off confirmation, cursor/selection accuracy over
  ligature text, an Arabic drift check, a perf smoke and a default-font regression. The device was
  disconnected on 2026-10-18, so all of it is deferred.

## 4. Appearance — deferred minors from review

- `[done 2026-10-16, commit 6c606187]` Dead string resources removed: `title_create_named_session`,
  `action_create_named_session_confirm` (orphaned by the sessions-bar commit), `action_paste` (orphaned by
  the Compose extra-keys bar), and `error_styling_not_installed` + `action_styling_install` (orphaned by the
  appearance-screen commit). Found via lint `UnusedResources`; the report no longer lists any unused
  string. Other unused resources (7 session/current-session drawables, `preference_markdown_text.xml`, 3
  styles) remain — not part of this item.
- `[done 2026-10-16, commit 349cbd66]` The cursor-override-cannot-be-cleared precedence. Cursor style
  and blink are now stored as nullable overrides (`null` = follow `termux.properties`), the screen has
  Default chips for both, Reset stages `null`, and Apply clears the stored override instead of pinning
  a value. A colour-only Apply no longer creates a cursor override.
- (Grounding: `.superpowers/sdd/2026-10-14-in-app-terminal-appearance/progress.md:235–238`.)

## 5. Terminal background — live wallpaper

- `[limitation]` With a live (animated) wallpaper, `WallpaperManager.getDrawable()` yields no usable
  frame (black), so the system-wallpaper background works only with a static image. Ruled a
  device/engine quirk seen on the ZTE live wallpaper, not app code.

## 6. Settings & first-run onboarding (spec §7, milestone C) — IA rework shipped

- Shipped: the B1 first-run walkthrough, B2 extra-key presets, B3 font-size default.
- Shipped (2026-10-16): the milestone C **preferences information-architecture rework**. The Termux hub is
  now Display / Keyboard / Advanced, all 13 leaf `app:key` values are frozen, and no migration is needed.
  Stream 3 commits `de9ed98b`..`9077b5f6`; spec `specs/2026-10-16-settings-ia-design.md`, plan
  `plans/2026-10-16-settings-ia.md`; final review clean (one Important fix pass).

## 7. Terminal engine upgrade

- Captured separately in `ideas/2026-10-14-terminal-engine-upgrade-idea.md` (brainstorming stopped
  before approach selection; resume at its "Open decision" section). Not repeated here.
  `[updated 2026-10-17]` Approach **B (substrate first)** chosen; workstream 1 (cell model) has a spec at
  `specs/2026-10-17-terminal-engine-cell-model-design.md`. It is now implemented and consumed by WS2, WS3
  and WS5. It also absorbed the parsing halves
  of `SGR 4:n` (underline styles) and `OSC 8` (hyperlinks) so the new storage ships live rather than unused;
  workstream 5 keeps xterm conformance, link click handling and the remaining OSC 8 work. Workstreams 2–6 remain
  unspecced.
  `[updated 2026-10-17]` Workstream 2 (renderer dirty-region drawing) is implemented on `master`
  (`feature`-style commits `e8e165a8`..`67ec4de3` once the branch gate passes): renderer row-range entry point,
  tested `RowInvalidation` decision logic + viewport mapping, and the view's counter/dirty/overlay protocol.
  Its spec (`specs/2026-10-17-renderer-dirty-region-design.md`) records one limitation found during review:
  - `[bounded]` **palette changes with no other output** — OSC 4/10/11/12 mutate the palette without marking a
    row dirty or ticking the change counter, so the view's overlay comparison (cursor cell/visibility/shape and
    reverse video) cannot see them. Normally `onColorsChanged` → `updateBackgroundColor()` repaints the view in
    full, but that early-returns in wallpaper/custom-image mode. Fixes: either compare a palette version per
    update, or make the wallpaper-mode path still invalidate. Rare in practice (a palette OSC is usually
    accompanied by text output, which dirties rows); needs a spec cycle if the robust signal is chosen.
- **WS5 — OSC 8 hyperlinks end-to-end + `SGR 58` underline color: done.** Branch
  `feature/ws5-hyperlinks-sgr58`; spec `specs/2026-10-17-osc8-hyperlinks-sgr58-underline-design.md`
  (`Approved — implemented`), plan `plans/2026-10-17-osc8-hyperlinks-sgr58-underline.md`, one commit per
  planned task, `./gradlew test` **411/411 green**. Five deviations, all now written into the spec:
  1. hit-test uses `getColumnAndRow(event, true)` (descent + scroll aware, mirrors selection/mouse math)
     instead of the draft's bare `y / mFontLineSpacing`;
  2. `reset()` initialises the underline color;
  3. `SavedScreenState.mSavedUnderlineColor` saves/restores it for DECSC/DECRC;
  4. the app dispatches through `ShareUtils.openUrl` (the existing URL-opening path, with the system
     chooser fallback) instead of a bespoke `ACTION_VIEW` + `ActivityNotFoundException` catch;
  5. `SGR 0` also clears the underline color (the pre-existing `SGR 58` parse was never reset).
  Leftovers / follow-ups:
  - `[bounded]` the plan's renderer **pixel** test is impossible on this host: Robolectric's native graphics
    runtime reports *"not supported on Linux (aarch64)"* and its legacy mode rasterizes nothing (proved with a
    control asserting two different contents render differently — both came back blank). Replaced with a
    recording-`Canvas` call-sequence test (draw calls + paint underline flag/color); pixel-exact appearance
    remains the manual device check.
  - `[decided 2026-10-18]` OSC 8 taps dispatch **unconditionally**, while the existing word-URL tap
    is gated by the `open terminal transcript URL on click` property — confirmed as final by user
    decision: an explicit program-stamped link is not a transcript URL guess, so the property does
    not gate it. No code change pending; recorded in spec §4.5.
  - `[bounded]` a **wide linked glyph** (emoji, CJK) resolves only on its base column: WS1 stamps the link id
    on the base cell and leaves the continuation column at `0`, and the hit-test reads the tapped cell. ASCII
    links are unaffected. The "reuse the previous base column" fallback needs a continuation test before it is
    safe to add.
  - `[closed]` the plan's "24-bit white `0xffffffff` collides with the foreground sentinel" edge cannot occur:
    a 24-bit ARGB value always has `0xff` in its high byte, so it never equals `COLOR_INDEX_FOREGROUND`
    (`256`) — and `58;5;256` means "default foreground", i.e. unset.
  - **Review pass (two axes, `master..HEAD`):** no standards breach, no scope creep. Two real defects
    found and fixed with RED-first tests (`414/414` green after):
    - `[fixed]` an underline bit with no `SGR 4:n` style plus an `SGR 58` color drew **nothing**:
      `drawUnderline` received `UNDERLINE_STYLE_NONE` and its `default:` broke instead of stroking.
      Untyped and out-of-range styles now stroke a single line (the plan required this `default:`), and
      an uncolored link still uses the text paint's own underline.
    - `[closed]` the hit-test rejected `row < 0`, so a link scrolled into history never resolved even
      though the tap coordinate is scroll-relative by design; the bound is now
      `-getActiveTranscriptRows()`.
  - `[closed]` the resolver no longer logs "disallowed scheme" when the id was garbage-collected before
    the tap (nothing to sanitize).
  - `[declined]` review judgement calls on the pre-existing cell model: a `CellAttributes` value type
    for the travelling `style`/`linkId`/`underlineColor` triple, and moving the link lookup out of the
    pure static resolver; both are wider refactors than WS5.
  - `[open]` the erase/scroll/reflow plumbing for the underline-color array has no direct test (the
    plan named none); see spec §8 "Not covered by tests".
  - **Device check (manual, spec §8): passed** (2026-10-18) on the debug build of this tree installed
    in place on a physical NX769J / Android 14 over wireless adb. An `OSC 8` `https` link tap opened
    Chrome (twice); the *same* tap point on a `file:` fill opened nothing; the underline colours were
    confirmed by scanning raw `screencap` pixels — `SGR 4` + `58;5;196` stroked a 2-row pure-red band,
    `SGR 4:3` + `58;2;0;255;0` a 12-row wavy pure-green band, and an `OSC 8` link + `58;2;255;0;255`
    with no `SGR 4` a 2-row pure-magenta band, while `58;5;196` alone drew nothing (the control frame
    contains only the theme's red cursor). Method and measurements: spec §8.
    A second pass (same session) closed what the first left implicit:
    - `[done]` **scrolled-back hit-test**: 200 logical rows, each a full-width `OSC 8` link to
      `https://example.com/probe/rowNNN` whose row number *also* selects the row's palette colour, so a
      decoded raw screencap says which rows are visible and where their glyphs are. Dragging the
      transcript back and tapping a decoded glyph resolved `row186` (1 page back), `row163` (3),
      `row128` (6) and `row005` (~40, the top of the scrollback) — the row displayed at the tap point
      every time, read out of Chrome's own UI hierarchy.
    - `[done]` **link underline without `SGR 4`**: the same 12 full-width coloured rows printed twice,
      once with `OSC 8` and once without. Per-scanline count of the row's own colour: the linked frame
      shows full-width 1081-px runs (one underline per wrapped grid row), the control frame 112-429 px
      glyph strokes and no run at all - the on-device form of the pixel test this host cannot rasterize.
    - `[done]` **`file:` refusal with the link proven to exist**: the `file:` rows are underlined exactly
      like the `https` rows (so the cells *are* links), yet the tap fell through to the client — the
      soft keyboard came back after `BACK` had hidden it — and started nothing, while the identical tap
      on the `https` rows opened Chrome with `example.com/probe/row04`.
    - `[bounded]` the resolver's own warning is still unreadable here: the `shell` user gets no logcat
      on this ROM, and `run-as com.termux logcat` returned an empty/unrelated buffer (a self-written
      `log -t WS5TEST` line was invisible too). The underline plus the client-tap fall-through pin the
      same behaviour down without it.

- **WS3 — run shaping + ligatures: implemented (2026-10-18).** Spec at
  `specs/2026-10-18-ws3-shaping-ligatures-design.md`; decisions taken: grid-snap verification with per-cell
  fallback (Approach 1 over a cluster-aware restructure), **grid-snap verification is API 29+**
  (`Paint.getTextRunAdvances` is API 29, not 21 — found during planning; below 29 a run draws as one shaped span
  and ligatures still form), deterministic ligature toggle **default ON**
  (`+liga,+calt` on / `-liga,-calt` off) because the device was disconnected and the platform default could
  not be probed, device checks deferred to that spec's §7 (mirrored above), delivery straight onto `master`
  with one commit per task. Implemented in three commits (`d5bb6baa`, `94d29f2c`, `30d9a9e6`): the grid-snap
  policy and advance seam, the per-span glyph stage, and the ligature toggle (appearance screen, default on).
  The §3 device checklist is still outstanding. Workstream 4 (graphics placement) is now implemented
  and host-verified (spec `specs/2026-10-18-ws4-graphics-placement-design.md`, completion plan
  `plans/2026-10-18-ws4-kitty-decode-render.md`): `a=t`/`a=T` decode, store and place, `a=p`/`a=d`
  resolve against the placement registry, `a=q` honours the quiet levels, and chunked transmissions
  are reassembled. The earlier ack-only state (which acknowledged images it never drew) is gone; its
  §8 device checks (D-2) were **completed 2026-10-19** on the NX769J target — see
  `specs/2026-10-18-ws4-graphics-placement-design.md` §8 (all six items ticked, with the measured run
  record). They found and fixed one real defect: a transfer accepted only two chunks, so any image whose
  transmission needed a third chunk silently rendered nothing. `KittyGraphicsTransfer.accept()` now
  accepts `m=1` continuations, verified on device at 2, 3, 14 and 53 chunks (tests
  `threeChunksConcatenatePayload`, `middleChunkOverBudgetErrors`). Workstream 6 (compat shims)
  and the xterm-conformance remainder stay unspecced.

## 8. Doc hygiene debt (this directory)

- `docs/superpowers/` is gitignored (`.gitignore:49`), so none of these records are in the repo — they
  are local-only.
- `[done 2026-10-16]` `plans/2026-10-14-in-app-terminal-appearance.md` now has a `## Status` section
  recording C1–C4 shipped/merged (PR #1, merge `4b3a0143`), and its header line is updated.

## 9. Non-goals — decided against, recorded so they are not mistaken for gaps

From the shell/sessions spec (§ Non-goals): no rewrite of terminal emulation, input or scrollback; no
change to the package or applicationId; no `:termux-shared` public API change; `:terminal-emulator` and
`:terminal-view` compile and test unchanged.

From the appearance plan (Non-goals): no `:terminal-emulator` / `:terminal-view` changes; no
`:termux-shared` diff; no live-apply (explicit Apply was chosen); no new Gradle module and no new
third-party dependency (the font picker is hand-written).

## 10. Scrollback depth — diagnosed 2026-10-19, probe run 2026-10-20, config-level, no code change

- **Report:** "terminal doesn't scroll back far enough."
- **Diagnosis (host-side, complete):** the depth is the `terminal-transcript-rows` property, clamped by
  `TerminalEmulator.java:238–240` (min 100, **max 50000**, **default 2000**), read once per new session at
  `TermuxService.java:586` and sized into the `TerminalBuffer` ring (capacity = screen rows + transcript
  rows; rows allocate lazily at ~0.5 KB each, so a full 50k transcript ≈ 25 MB). No runtime trim exists
  and `TerminalView` scrolls to `getActiveTranscriptRows()`, so nothing else caps the depth.
- **Why it felt broken:** the device's `~/.termux/termux.properties` already sets
  `terminal-transcript-rows = 50000` (the max), but that file was **created after the app process had
  started** — properties load at startup, so every live session was created with the 2000-line default.
  `termux-reload-settings` reloads the shared properties singleton but does not touch sessions that
  already exist (the ring is fixed at emulator creation): only a **new session** (Ctrl+Alt+C) or an app
  restart picks up the configured value.
- `[decided 2026-10-19]` 50000 is enough — no change to `DEFAULT_TERMINAL_TRANSCRIPT_ROWS`,
  `TERMINAL_TRANSCRIPT_ROWS_MAX` or the reload semantics (user decision; the property is the fix).
- `[probed 2026-10-20]` The deferred on-device probe **was run** (target NX769J/API 34, reached at a new
  wireless-debugging port `10.0.0.169:33723`). Result: the config-level diagnosis is **confirmed**; the
  numeric pixel measurement is **not reproducible enough on this device to quote as a fact**.
  - **Confirmed by source (deterministic).** `TerminalEmulator.getTerminalTranscriptRows()`
    (`terminal-emulator/.../TerminalEmulator.java:578–583`) passes any value in
    `[100, 50000]` straight through and falls back to `DEFAULT_TERMINAL_TRANSCRIPT_ROWS = 2000`
    only for `null`/out-of-range, so `terminal-transcript-rows = 50000` really does yield 50000 and
    `= 2000` really does yield 2000. `TermuxService.java:586` copies `mProperties.getTerminalTranscriptRows()`
    into the session at creation, and `mProperties` is loaded once at process start — which is exactly the
    load-timing explanation above: a session that predates the property keeps 2000, and only a **new
    session (Ctrl+Alt+C) or an app restart** picks up the configured value.
  - **Why the pixel walk failed on this device** (all reproducible, all worth knowing):
    - The cell geometry in the old probe was stale. Measured precisely from three independent
      full-width colour bars emitting at known lines: **cell height = 34 px exactly** and the terminal
      view top **y = 250** (the old `CELL_H = 43`, `TERM_TOP_Y = 251` implied ~27 rows for a 28-row view).
    - "Drag until the screencap stops changing" cannot detect the scroll end: the **blinking cursor alone
      produces ~350–480 k pixel diff between two captures**, which is *larger* than the diff a real scroll
      produces. A "stable screencap" therefore measured the cursor, not the buffer top.
    - `adb shell input swipe` has **non-deterministic fling momentum** (`TerminalView` scrolls via
      `Scroller.fling`, `terminal-view/.../TerminalView.java:237`). Repeated runs with identical drags
      reported oldest-retained lines scattered between ~400 and ~3000, so the method cannot pin a number.
    - Passing the shell command needs `input text` with `%s` for a space (`%20`/`%7E` silently type
      nothing), and `input text` only reaches the terminal when the terminal — not the writing page — holds
      the IME (`dumpsys input_method` → `mServedView`).
  - **No user-visible change follows.** 50000 was already decided as sufficient, and the code path is
    already correct; the device's `~/.termux/termux.properties` is back at
    `terminal-transcript-rows = 50000` (it was temporarily set to 2000 for the A/B and restored from
    `termux.properties.probe-bak`). The corrected bar probe (`probe2.sh`, colour-encodes its own line
    index; `probe3.py`, exact-RGB reader) lives in local scratch `~/.tmp/opencode/` if the measurement is
    ever worth redoing with a deterministic scroll primitive — e.g. driving
    `TerminalView.setTopRow()` directly through an instrumentation test instead of synthetic swipes.
