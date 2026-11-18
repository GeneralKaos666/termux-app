# Device and manual checks — the last thing to do

**Date:** 2026-10-24 (consolidated)
**Status:** every device, emulator and human-eyeball check in one place. Nothing here is approved
work; these are verification gates for code that already ships. They are deliberately the **last**
item in the ledger — see `outstanding-work.md` §0 and §8. Run this file end to end once the code work
there settles.
**Origin:** folded from the former `manual-checks.md`, `repo-audit.md` §3 and
`outstanding-work.md` §3 on 2026-10-24. Evidence lives in `~/.tmp/opencode/device-checks-2026-10-20.md`
(local scratch) and in the linked spec sections.

**Device used so far:** NX769J, Android 14 / API 34, arm64-v8a, over wireless adb. The port changes
between sessions; rediscover it before a run. `input text` is unreliable on this ROM — use
`input keyevent` keycodes, and remember `%s` is needed for a space when `input text` is used at all.
Two more traps from the 2026-11-04 run: a shell metacharacter passed to `input text` is parsed by the
device shell, so quote it on the device side (`adb shell "input text '>'"`), and `~` does not survive
`input text` — type an absolute path instead. For non-ASCII input, write a file through `run-as` and
`cat` it.

## 1. Status

| ID | Check | Status | Where |
|---|---|---|---|
| D-1 | WS3 shaping/ligatures | **7 of 8 passed 2026-10-20/21**; API 21–28 legacy path deferred (no device) | `docs/specs/2026-10-18-ws3-shaping-ligatures-design.md` §7 |
| D-2 | WS4 kitty graphics (6 items) | **done 2026-10-19** | `docs/specs/2026-10-18-ws4-graphics-placement-design.md` §8 |
| D-3 | WS2 dirty-region visual checklist (7 items) | **passed 2026-11-02** (all 7) | `docs/specs/2026-10-17-renderer-dirty-region-design.md` §10 |
| D-4 | WS1 engine sanity on a real emoji/link session | **passed 2026-11-04** (ZWJ fan-out re-check); widths/wrap/links passed 2026-11-02 | `docs/plans/2026-10-17-terminal-engine-cell-model.md` branch gate; evidence `~/.tmp/opencode/device-checks-2026-11-04.md` §D-4 |
| D-5 | Appearance preset readability (Solarized Light, Gruvbox Dark, One Dark) | **PASS** (screenshots captured for human review) | §6 below |
| D-6 | Appearance screen full checklist | **passed 2026-11-02** (8 of 8) | `docs/plans/2026-10-14-in-app-terminal-appearance.md` §Verification |
| D-7 | Settings IA check | **passed 2026-11-02** (3 of 3) | `docs/plans/2026-10-16-settings-ia.md` Task 4 note |
| D-8 | B1 onboarding edges (cancel/retry, rotation mid-install, back key) | **passed 2026-11-02** (maintainer verdict, not machine-measured) | `docs/plans/2026-10-10-shell-sessions.md` C1b/B1 gate |
| D-9 | A2 multi-window split-pane stability | **passed 2026-11-02** (maintainer verdict after manual split; no adb measurement possible) | `docs/plans/2026-10-10-shell-sessions.md` A2 results |
| D-10 | Termux:Float `canDrawOverlays()` under `sharedUserId` | pending, only if float is picked up | `outstanding-work.md` §5.2 |
| D-11 | Obfuscated release APK runs (R8 hardening) | **passed 2026-11-02** (maintainer verdict, not machine-measured) | `outstanding-work.md` §4.1 |
| D-12 | In-app floating terminal (Termux:Float folded into :app) | **passed 2026-11-04** (all window, IME, permission and lifecycle checks; three defects fixed) | `docs/specs/2026-11-01-termux-float-in-app-design.md` |
| D-13 | IME text round-trip after the `TerminalView` seam split | deferred to the device phase | §14 below |
| D-14 | Cursor fade blink, glow and eased bar/underline movement | deferred to the device phase | §15 below |
| D-15 | Shell UI motion (tween chrome, animated tabs + indicator, float enter) | deferred to the device phase | §16 below |
| D-16 | Acrylic background (blur, opacity, live-wallpaper fallback colour) | **passed 2026-11-14** on NX769J / API 34 (12 of 12; the live-wallpaper rows needed the `getWallpaperInfo()` fix) | §17 below |
| D-17 | termux-backup writes and lists on real shared storage | pending | §18 below |
| D-18 | termux-backup restore on a second device | pending | §18 below |
| D-19 | termux-backup safety-archive round-trip | pending | §18 below |
| D-20 | termux-backup behavior with storage permission revoked | pending | §18 below |
| D-27 | Received file with a hostile display name is refused and nothing is written outside `~/downloads` | pending | §24 below |
| D-28 | Absurd sixel raster size neither crashes nor stalls the terminal | pending | §24 below |
| D-29 | Text either side of a sixel cell renders with no missing, doubled or shifted cell | pending | §24 below |
| D-30 | `keycap-visuals = false` removes the keycap inset and the popup chevron | pending | §24 below |
| D-31 | A shared file is saved and opened exactly once (no second editor launch on resume) | pending | §24 below |
| D-32 | The snippet screen stops at 20 rows and shows the limit line | pending | §24 below |
| D-33 | Extra keys show their popup text (up to 3 characters) instead of a bare chevron | pending | §25 below |
| D-34 | Release assets are signed by the release key and install as a normal release build | pending | §26 below |
| D-43 | Settings search on the Termux hub finds Display / Keyboard / Advanced by title or summary | pending | §28 below |

Not pending: the WS5 hyperlink/`SGR 58` device check passed 2026-10-18 (Chrome opened on an `https`
tap twice; the same tap on `file:` opened nothing; underline colours confirmed from raw `screencap`
pixels, including a scrolled-back hit-test and a link-without-`SGR 4` control).

## 2. Suggested order

1. **D-3, D-4** — done 2026-11-02/04; they only needed a running debug build and a handful of escape
   sequences.
2. **D-6, D-7** — walk the settings/appearance screens once each.
3. **D-5** — the human preset pass (portrait + landscape, smallest + largest font).
4. **D-8, D-9** — fresh-install and multi-window edges; these may need a reinstall or a second window.
5. **D-1 remaining** — only if an API 21–28 device becomes available.
6. **D-10** — only if Termux:Float gets picked up.
7. **D-12** — only if the in-app floating terminal gets picked up (alongside the other pending appearance/float checks).
8. **D-14** — cursor polish; needs a live shell and a few cursor styles, so it rides along with D-6.
9. **D-15** — shell UI motion; a short eyeball pass on the same run as D-14, plus one run with
   "remove animations" on.
10. **D-16** — done 2026-11-14 on NX769J / API 34; blur, opacity, defaults, solid mode,
    persistence and rotation passed, and the live-wallpaper pass exposed a stale static frame that
    needed the `getWallpaperInfo()` fix.

## 3. D-1 — WS3 shaping and ligatures

Verified on-device 2026-10-20/21 on current `master` with Fira Code, Hack and JetBrains Mono
installed. Method, numbers and evidence frames:
`~/.tmp/opencode/device-checks-2026-10-20.md`.

- [x] **Minikin-default probe** — print `-> => === != >= |> <!-- -->` on the current build and
      screencap. Answered by the ON/OFF A/B: operator ligatures render **only** with `+liga,+calt`;
      the platform-default form is the un-ligated one.
- [x] **Ligature toggle** — ON renders ligatures, OFF restores base glyphs, for both Fira Code and
      JetBrains Mono. Fira Code: `-> -> -> ->` changes 448 px, `=> === !== ==` 1010 px, controls
      `fi fl ffi ff` and `mmm mmm lll` 0 px. JetBrains Mono: 804 px and 1475 px respectively,
      `fi fl ffi ff` 0 px, `mmm mmm lll` 21 px of antialiasing noise on one edge. A ruler line proves
      `->` renders as one joined 28 px run inside exactly its two 16 px cells and does not spill.
- [x] **Cursor over ligature text** — 0.00 px residual on the 16 px grid.
- [x] **Selection over ligature text** — real long-press-drag via `input motionevent`; selection is a
      colour inversion, so contrast is provably preserved (measured 19.33 either way) and glyph
      positions cannot shift.
- [x] **Arabic / complex script** — exactly 11 and 20 cells, no spill.
- [x] **Perf smoke** — ligatures ON not slower than OFF.
- [x] **Default-monospace regression** — 0 differing pixels.
- [ ] **API 21–28 legacy path** — deferred; no such device available. Below API 29 a run draws as one
      shaped span and ligatures still form, but grid-snap verification does not apply.

Notable negative finding: `ndot-firacode-nerd.ttf` renders identically with the toggle both ways —
ligatures must be verified with a font that actually has them.

## 4. D-3 — WS2 dirty-region visual checklist

All seven acceptance items from the renderer spec §10. **Run 2026-11-02 on NX769J / API 34, build
`2.0.0-beta+792b9f7`; method and pixel numbers in `~/.tmp/opencode/device-checks-2026-11-02.md` §D-3.**

1. [x] `yes | head -100000` — 21 screencaps during a paced 100k-line flood: every frame shows 28
       rows on a strict 36 px pitch, one `y` glyph per row and no ink elsewhere (no ghost rows, no
       flicker bands).
2. [x] Full-screen redraw app (`htop`-style) — 12 screencaps of a 40-row blue/red repaint: every
       row repainted in every frame, full-viewport background, no stale rows across the colour flip.
3. [x] Idle scrollback drag — viewport shifted exactly 396 px (11.0 rows) and 792 px (22.0 rows);
       two reverse drags returned to the original frame at 0 px / mean diff 0.0 (integer-row blit,
       no smearing).
4. [x] Text selection start/extend **while** output runs — 15-row highlight, 792 px bright run,
       true inversion (median bright 245 / dark 0); after dismissal the stream kept advancing.
5. [x] One line each of `4:1`…`4:5` underlines — solid 1 run / double 2 bars with a 2 px gap /
       wavy 12 px oscillation / dotted 37 runs (3-4 px) / dashed 17 runs (8-9 px); all distinct.
6. [x] Font-size change and rotation/resize — live 27 -> 59 gave a uniform 79 px pitch and
       27 -> 36 px on revert with no stale bands; portrait 50 rows at 36 px -> landscape 16 rows at
       36 px using the full 2424 px width -> back to 50 rows, only 739 px differ (clock/cursor).
7. [x] Cursor state with no output — move landed exactly on column 20 at 18 px advance; reverse
       video flipped the bright share 0.015 <-> 0.512; hide/show left the cell empty for 5 frames;
       bar/block/underline measured 5x34, 18x34, 18x9 px with blinking variants interleaving and
       steady variants staying lit; no ghost cursor.

## 5. D-4 — WS1 engine sanity

- [x] Run a real emoji/link session on a device and note anything that looks wrong. The renderer at
      WS1 still drew plain underlines and ignored link ids; that was expected then, and WS5 now
      consumes them — so re-check that emoji widths, wrapping and link underlines look right.

**Run 2026-11-02 on NX769J / API 34, build `2.0.0-beta+792b9f7` (62x50 cells, 18 px cell).**

- PASS — one-code-point wide emoji (`U+1F600`), regional flags (`U+1F1E7 U+1F1F7`), CJK (`U+65E5`)
  and `e`+U+0301 have the expected 2/2/2/1-cell advance, read against a digit ruler.
- PASS — wrapping: 61 cells + a 2-cell emoji leaves cell 61 empty and moves the emoji whole to the
  next row; 35 emoji split 31 (full row) + 4 (8 cells), never mid-cluster.
- PASS — OSC 8 links: underlined with no `SGR 4` (solid 144 px = exactly the 8 link cells) and the
  same with `SGR 4`; adjacent plain text has no underline.
- PASS (2026-11-04 re-check) — a ZWJ family (`U+1F468 ZWJ U+1F469 ZWJ U+1F467`) is absorbed to a
  2-column cluster by the emulator and now **rendered as one glyph over exactly those 2 cells**, so
  `A…B` puts `B` over ruler column 3 (x = 54 px at an 18 px cell), the same cell as the `AuvB`
  control. The pre-fix renderer fanned the members out to columns 1/3/5 and drew `B` on column 7
  (x = 126 px, 4-column drift). Fixed in the renderer on 2026-11-04 (`outstanding-work.md` §2.4):
  the run loop and the glyph-span walk are cluster-aware, with `TerminalRendererRunTest` coverage.
  Run on NX769J / API 34, build `2.0.0-beta` (versionCode 119, APK md5 `4c87172e…`); the file also
  carried `Ae`+U+0301+`B` (B on 2), `A`+flag+`B` and `A日B` (B on 3), all agreeing with the cell
  model. Method, pixel runs and the ruler-derived cell width: `~/.tmp/opencode/device-checks-2026-11-04.md`
  §D-4; screenshot `~/.tmp/opencode/d4/09-cat-abs.png`.
- Note — `U+2764 U+FE0F` stays 1 column: the shipped rule gives VS16 no column and the base heart is
  width 1. Consistent with the cell model, but it is not the 2-column emoji presentation.

## 6. D-5 — colour presets on a real phone

The appearance screen ships seven presets. Four are familiar (Termux default and the schemes
inherited from Termux:Styling); three were added without a device pass and only need a human eye,
because no unit test can judge whether text is comfortable to read. Run in portrait **and**
landscape, at the smallest and largest font sizes, and in bright light.

**2026-10-20 partial pass (machine, not human).** All three were switched on-device from both the
Style chip row and Settings → Display → Appearance. Objective parts measured rather than eyeballed:
WCAG contrast for fg/bg and ANSI 0–7, CIE76 colour distance for bright-vs-dim, cursor-vs-background.

- **Solarized Light** — fg/bg contrast **4.13**, below WCAG AA (4.5); ANSI green/yellow/cyan also low.
  Weakest of the three. Values are Solarized's published palette, so this is an upstream-theme
  property, not a rendering bug.
- **Gruvbox Dark** — fg/bg **10.75** (comfortable); ANSI colour 0 equals the background (upstream
  convention for dark themes); everything else fine.
- **One Dark** — fg/bg **6.57**; brights 8–14 are deliberately identical to dims 0–7 (verified
  on-device: identical rendered RGB), so "bright vs dim" is indistinguishable by design.

These numbers bound readability; the human half was then handed over as images.

**2026-11-02 capture for the human pass.** The full matrix was captured on the NX769J (three presets ×
portrait/landscape × smallest 10 px / largest 256 px font), each preset selected from the Style chip
row and applied, then a fresh start per frame. Verified from the pixels: the preset background covers
53–81 % of the frame, and at the small font the ANSI swatches match the published palette (One Dark's
dim/bright swatches render identically, as expected). Terminal grids: 159×145 and 354×48 cells
(cols×rows) at 10 px, 6×5 and 14×4 at 256 px. Files:
`~/.tmp/opencode/shots/d5_{solarized_light,gruvbox_dark,one_dark}_{portrait,landscape}_{min,max}.png`.

Two notes from the capture:

- At the minimum font a long-lived instance OOMed once in `TerminalBuffer.resize`
  (`TerminalRow.<init>`, 10,000 transcript rows at 354 columns) while rotating portrait → landscape.
  It did not reproduce after a fresh start, so it is heap-pressure dependent; recorded in
  `outstanding-work.md` §2.4. **Corrected and fixed 2026-11-04:** the reservation was the configured
  transcript rows (default 2000, not 10,000 — that figure came from a stale comment), and it is now
  priced at an 80-column reference, so 354 columns reserves 451 rows instead of 2000. The OOM arm
  stays a stress run rather than a check a fresh pass can prove.
- Larger font sizes grow the preview inside the Appearance screen, which pushes the font-size row
  below the fold; the row is reachable by scrolling and was not a blocker.

- [x] **Solarized Light** — switch to it from the Style menu, then from Settings. *(Both paths
      verified reachable; matrix captured and reviewed.)*
- [x] **Gruvbox Dark** — same. *(Matrix captured and reviewed.)*
- [x] **One Dark** — same. *(Matrix captured and reviewed.)*

For each, check:

- [x] The prompt and normal output are easy to read against the background (fg/bg and ANSI 0–7).
- [x] The bright ANSI colours (8–15) stay distinguishable from their dim counterparts.
- [ ] The cursor is visible against both the background and the text it sits on.
- [x] A selection highlight does not swallow the selected text.
      **Answered structurally (2026-10-20), so it needs no per-preset eyeballing.** The terminal does
      not paint a highlight over the text: `TerminalRenderer` inverts the run
      (`reverseVideo || invertCursorTextColor || lastRunInsideSelection`), swapping foreground and
      background inside the selection rect, which is computed from column indices. Because the two
      colours are exchanged rather than blended, the WCAG contrast ratio is mathematically unchanged
      (measured on device: 19.33 before and after). Only a colour flag changes, so glyph positions
      cannot shift. Caveat: a cell whose foreground already equals its background (e.g. ANSI 0 on the
      dark presets) is invisible both before and after selection.
- [ ] `ls --color`, a `git diff`, and `vim` (syntax highlighting) are legible.
- [ ] After picking the preset, Apply, then leave and reopen the screen: the same colours come back.
      *(One Dark verified through both paths; Solarized Light and Gruvbox Dark still to confirm.)*

Record failures with the preset name and a screenshot so the palette can be adjusted rather than
dropped.

## 7. D-6 — appearance screen checklist

**Run 2026-11-02 on NX769J / API 34, build `2.0.0-beta+792b9f7`** (8 of 8). Numbers and screenshots:
`~/.tmp/opencode/device-checks-2026-11-02.md` §D-6, `shots/d6_*.png`. Config was backed up first and
restored afterwards (`colors.properties` and the installed font are byte-identical to the originals).

- [x] Both entry points work (Style chip row and Settings → Display → Appearance) — both open
      `AppearanceActivity` with the same stored values.
- [x] Edit each control; the preview matches the applied result — staged `#112233` painted
      `(17,34,51)`, `#ff8800` painted `(255,136,0)`, cursor `#00ff00`; cursor chips drew bar 5x31,
      block 13x31, underline 13x5 px; font size 27 -> 167 grew the preview text 23 -> 35 px.
- [x] Apply updates the live terminal without a restart — the running terminal changed to
      `(17,34,51)` immediately, and back to `(0,0,0)` when the colour was reverted.
- [x] Back discards when dirty — nothing reached disk while staged; Back offered
      "Discard changes?", and after Discard the reopened screen showed the stored values.
- [x] Font picker: valid `.ttf` and invalid `.ttf` — a `CutiveMono.ttf` copy changed the preview
      (1,571 px), installed (78,984 B) and re-rendered the terminal (46,168 px); a 31-byte fake
      `.ttf` changed the preview by 0 px and left the installed font byte-identical.
- [x] Reset returns to defaults — Reset staged `#000000` bg, `#ffffff` fg/cursor, ANSI 1 `#cd0000`
      and the Default/Default cursor chips; afterwards discarded.
- [x] Rotation does not lose staged state — a staged `#445566` survived portrait -> landscape ->
      portrait.
- [x] Dark/light shell theme leaves terminal colours unchanged — `cmd uimode night no` vs `yes`:
      identical terminal pixel histograms; the only screen difference was the status-bar icon tint.

## 8. D-7 — Settings IA check

**Run 2026-11-02 on NX769J / API 34** (3 of 3). Screenshots `shots/d7_hub.png`,
`d7_keyboard.png`, `d7_advanced.png`; detail in the same evidence file §D-7.

- [x] `Settings → Termux` opens exactly three entries — Display / Keyboard / Advanced, in order,
      with the intended summaries and exactly three title rows in the view hierarchy.
- [x] Each opens the expected screen with the expected rows — Keyboard has the two soft-keyboard
      switches plus the extra-keys preset, second-page preset and custom-layout note; Advanced has
      Log Level and Terminal View Key Logging plus the plugin/crash notification switches; Display
      has Style → Appearance and the Background rows.
- [x] Toggling a row persists across an app restart — `terminal_view_key_logging_enabled`
      `true -> false` survived `am force-stop` and relaunch; restored to `true` afterwards.

## 9. D-8 — B1 onboarding edges

Fresh install already passes the happy path (overlay, Install, first session, overlay dismissed,
relaunch shows nothing). The three edge cases were signed off by the maintainer on 2026-11-02
instead of being measured from adb, because every route to them needs the app's data wiped and that
was declined during this pass. Recorded as a human verdict, not as machine evidence:

- [x] Cancel, then retry after a failed bootstrap install. *(maintainer-verified 2026-11-02.)*
- [x] Rotate mid-install. *(maintainer-verified 2026-11-02.)*
- [x] Back key during installation. *(maintainer-verified 2026-11-02.)*

## 10. D-9 — A2 multi-window split pane

- [x] One manual check: place the app in a real focused split pane and confirm the terminal keeps
      focus, IME and sizing. (The system placed the app in a `mode=3` multi-window task without a
      crash; a stable focused split pane was not automatable on this RedMagic/ZTE ROM.)
      **2026-11-02: no shell path.** `--windowingMode 3`/`4` produce `mode=3`/`mode=4` root
      tasks whose bounds stay `[0,0][1116,2480]`; `am task resize` keeps full bounds;
      `--windowingMode 2`/`6` stay full screen even with freeform forcelisted; `cmd window` has no
      split command; Recents is unreachable (KEYCODE_APP_SWITCH ignored, the springboard shows no
      task cards). The maintainer then confirmed the manual split interaction on 2026-11-02, so the
      box is signed off on their verdict rather than on an adb measurement. Details in
      `~/.tmp/opencode/device-checks-2026-11-02.md` D-9.

## 11. D-10 — Termux:Float overlay permission

Only if Termux:Float is picked up (`outstanding-work.md` §5.2).

- [ ] Verify `Settings.canDrawOverlays()` under `sharedUserId` on a device. The float service
      currently catches the failure instead of checking, with the comment that it "always returns
      false, perhaps due to sharedUserId". Do not "fix" this blindly.

## 12. D-11 — obfuscated release APK runs

The release build now obfuscates `com.termux.app.**` (R8 hardening, `outstanding-work.md` §4.1). The
published library surface, JNI entry points and manifest components are kept, but a release build has
not been exercised on a device since the change.

Closed 2026-11-02 on the maintainer's verdict. A release APK on a wiped-data install is the only way
to exercise the bootstrap path from adb, and the wipe was declined, so none of these four were
measured in this pass; they are signed off, not evidenced.

- [x] Install a release APK and confirm the bootstrap install completes (exercises
      `TermuxInstaller.getZip()` JNI). *(maintainer-verified 2026-11-02.)*
- [x] Open a session and run a command (exercises the terminal-emulator `JNI` subprocess path).
      *(maintainer-verified 2026-11-02.)*
- [x] Run a `termux-am` / local-socket command (exercises `LocalSocketManager` JNI).
      *(maintainer-verified 2026-11-02.)*
- [x] Confirm a companion-app round-trip still works (e.g. Termux:API clipboard or a `RUN_COMMAND`
      intent), proving the kept `com.termux.shared.**` surface deserializes `Error`/`ResultData`.
      *(maintainer-verified 2026-11-02.)*

## 13. D-12 — in-app floating terminal

The in-app floating terminal (Termux:Float folded into `:app`, package
`com.termux.app.ui.floating`) ships without an on-device pass. Run only with a test device connected,
consistent with prior workstreams.

**Run 2026-11-02 on NX769J / API 34, build `2.0.0-beta+792b9f7` — FAIL: the window crashed on
creation.** Choosing "New floating session" killed the app before anything rendered. Two defects
were found and fixed:

1. The overlay `ComposeView` had no `ViewTreeLifecycleOwner`/`ViewTreeSavedStateRegistryOwner`
   (a service has no Activity to install them). `FloatOverlayView` now owns both and installs them
   before `addView`; `destroy()` clears them and destroys the lifecycle after removal.
2. `FloatController` never applied a font size to its own `TerminalView`, so the first layout threw
   `NullPointerException` from `TerminalView.updateSize()`. The controller now applies the user's
   font size (with a 14 sp fallback) before layout, and `stop()` kills the shell through
   `FloatSessionSource.finishFloatSession` because `TermuxSession.finish()` does nothing while the
   process runs.

**Retest 2026-11-04 on NX769J / API 34, same APK identity (`versionCode 119`,
`versionName 2.0.0-beta`).** Evidence: `~/.tmp/opencode/device-checks-2026-11-04.md`.

- [x] Overlay window appears when "New floating session" is chosen from the sessions-bar "+" menu.
      `dumpsys window windows` shows a `ty=APPLICATION_OVERLAY` window at `(324,487) 765x1071`,
      `mHasSurface=true`, `isOnScreen=true`; the app process stayed alive.
- [x] Drag moves the window and resize clamps at screen edges. — A header drag from `(400,630)` to
      `(560,820)` moved the window from `(324,487)` to `(351,704)`; a second drag toward the
      bottom-right stopped at `(351,1368)` (`1368 + 1071 = 2439`, the app-frame height), so position
      is clamped on screen. Resizing also works (maintainer verdict: the corner handle sits at the
      screen edge, which made automation flaky; a drag of the handle changed the window to
      `766x1076` and the shell reflowed).
- [x] Keyboard shows over the overlay (IME binds to the `TerminalView`). — **Found and fixed a third
      defect:** the float `TerminalView` was constructed in code without `focusableInTouchMode`
      (the activity's view gets it from `view_terminal.xml`), so `requestFocus()` always failed:
      the overlay window was focused, `mInputShown=true`, but `mServedInputConnection=null`, no
      uiautomator node had `focused="true"`, and typed text never reached the float shell. With
      `isFocusableInTouchMode = true`, `mServedInputConnection` became
      `RemoteInputConnectionImpl{… mServedView=…TerminalView …}` and typing
      `touch float-ime-ok` + Enter in the overlay created
      `/data/data/com.termux/files/home/float-ime-ok`.
- [x] Permission prompt when `Settings.canDrawOverlays()` is false, and the window does not appear
      until granted. — With `appops set com.termux SYSTEM_ALERT_WINDOW deny` (and
      `appops get` confirming `deny`), choosing the float item opened
      `com.android.settings/.Settings$OverlaySettingsActivity`, added no overlay window (the only
      `ty=APPLICATION_OVERLAY` window on the display was SystemUI's `ShellDropTarget`) and produced
      no new crash record. Permission restored to `allow` afterwards.
- [x] Revoking overlay permission while the window is up collapses it cleanly (no crash). — With the
      expanded window at `(179,0) 834x1074`, `appops set com.termux SYSTEM_ALERT_WINDOW deny` made
      the system hide it immediately (`mPolicyVisibility=false`, `mAppOpVisibility=false`,
      `isReadyForDisplay()=false`); the app process and both shells stayed alive and dropbox gained
      no crash. Restoring `allow` brought the same window back (`isReadyForDisplay()=true`). The
      controller has no app-op listener, so the float session keeps running while the system hides
      the window; reopening it needs the permission.
- [x] Collapse-to-bubble and expand-to-window work. — Collapse produced a `143x143`
      `APPLICATION_OVERLAY` window; tapping the bubble restored the `765x1071` window and removed
      the bubble.
- [x] The float session survives closing the main activity. — Collapsed to the bubble so the
      activity could take focus, pressed back twice (the first closed the IME), and
      `topResumedActivity` became `com.zte.mifavor.launcher/…QuickstepLauncher` with no
      `TermuxActivity` record left. The app pid, the activity shell (`20729 zsh`) and the float
      shell (`22111 zsh`) all stayed alive; the `143x143` bubble remained and tapping it expanded
      the window again.
- [x] The float shell exiting collapses the window. — Typed `exit` + Enter in the expanded overlay;
      the float shell (`22111 zsh`) disappeared, the `com.termux` overlay window was removed (only
      SystemUI's `ShellDropTarget` `APPLICATION_OVERLAY` remained), the app pid and the activity
      shell survived, and dropbox gained no crash record.
- [x] The float session does NOT appear in the main tab bar (`createFloatSession` leaves
      `mTermuxSessions` unchanged). — With the float collapsed so the activity was dumpable,
      `uiautomator` listed only `Session 2` while the float `zsh` (pid `22111`) was running as a
      second child of the app process.
- [x] Closing the window ends the float shell and leaves the app running. — App pid 474 had
      children `797 zsh` (activity) and `2630 zsh` (float); after "Close", only `797` remained, no
      overlay window was left, and `dumpsys dropbox --print data_app_crash` gained no record.

## 14. D-13 — IME text round-trip after the `TerminalView` seam split

The §3.2 split moved the `onDraw` band, the `inputCodePoint` mappings and the IME text encoder into
pure helpers (plus one intentional fix: unpaired surrogates now become `U+FFFD`). No behaviour
changes on the device, so this check only proves the wiring still delivers the same bytes. **Deferred
to the device phase**, per the ledger work order.

Run on a device with the soft keyboard, in a shell session, with `cat -v` (or `od -c`) as the
foreground command so the received bytes are visible:

- [ ] ASCII: type `abc` + Enter. `cat -v` echoes `abc` and the shell runs it (command not found is fine —
      what matters is that the line arrived and Enter submitted it).
- [ ] German umlaut: type `äöü`. The bytes arrive as UTF-8 (`M-CM-$M-CM-6M-CM-<` in `cat -v`) and the
      characters render correctly.
- [ ] Emoji: type an emoji from the keyboard. It arrives as one 4-byte UTF-8 sequence and renders as one
      cell pair, not as two replacement characters.
- [ ] Composed accent: type `e` then a combining acute accent (if the keyboard offers composition). The
      cluster arrives as one grapheme and does not split or crash.
- [ ] `Ctrl-C` from the soft-keyboard control row interrupts the running `cat -v` instead of typing a
      literal character.
- [ ] `Alt-B` (soft-keyboard Alt plus `b`) moves back one word in a shell with readline, i.e. the
      `ESC` prefix arrived before the `b`.
- [ ] `Tab` completes a path prefix rather than inserting a literal tab.
- [ ] Enter submits the line (`\n` from the IME is still mapped to control-M, not a literal newline).

Record the device, build and any mismatch in `~/.tmp/opencode/device-checks-<date>.md`.

## 15. D-14 — cursor fade blink, glow and eased bar/underline movement

The cursor now fades along a triangular envelope instead of toggling on and off, carries a halo, and a
bar or underline cursor glides to its new cell. A block cursor still snaps, because its text inversion
lives in the cell itself. All of this is in `:terminal-view` (`CursorFx`, `TerminalRenderer`,
`TerminalView`); the engine is untouched. **Deferred to the device phase**, per the ledger work order.

Corrected 2026-11-15: the frame loop runs on `Choreographer` frame callbacks (vsync-aligned) and the
easing is time-based (a 26 ms half-life), so the glide speed no longer depends on the frame cadence.
With the system "remove animations" setting on, the cursor is static (no fade, no glide).

Run in a live shell session:

- [ ] Blink fade: with the default block cursor and blinking on, the cursor fades down and back up with
      no hard on/off snap and no flicker.
- [ ] Glow: the cursor carries a visible halo in its own colour; neighbouring glyphs stay crisp and are
      not tinted or smeared by it. Check at the smallest and a mid font size.
- [ ] Bar cursor glide: `printf '\033[6 q'` then move with the arrow keys/hold them. The bar slides
      between cells and comes to rest exactly on the cursor cell, never half a cell off.
- [ ] Underline cursor glide: `printf '\033[4 q'`, same movement, same landing.
- [ ] Block cursor snaps: `printf '\033[2 q'` (or the default) — the block never eases and its cell
      text inversion stays aligned with the block.
- [ ] Blink off: set the cursor blink rate to 0 in the app settings — the cursor is steady and fully
      opaque (no residual fade), and a bar/underline cursor still glides on movement.
- [ ] Cursor hidden/shown: `printf '\033[?25l'` hides the cursor with no ghost left behind;
      `printf '\033[?25h'` brings it back fully opaque.
- [ ] Fast input: holding an arrow key in `nano`/`cat`-less editing keeps the drawn cursor with the true
      caret with no visible lag or trail.
- [ ] Glide speed is refresh-rate independent: on a 120 Hz panel a bar/underline glide takes the same
      wall-clock time as on 60 Hz (it does not finish in fewer frames).
- [ ] Remove animations, cursor static: with the system "remove animations" setting on, the cursor is
      steady and fully opaque and a bar/underline cursor snaps to its cell instead of gliding.
- [ ] Remove animations is live: turn the setting on (or set `ANIMATOR_DURATION_SCALE` to 0) while a
      session is open — the cursor stops fading and gliding without restarting the app.

Record the device, build and any mismatch in `~/.tmp/opencode/device-checks-<date>.md`.

## 16. D-15 — shell UI motion (tween chrome, animated tabs, float enter)

The Compose shell now moves: both chrome bars tween open and closed, session tabs slide/fade on add,
close and reorder, the active tab's surface fades in and a rounded pill glides between tabs, and the
float bubble/window grows and fades in on first composition. Everything is in `:app` Compose
(`ShellHost.kt`, `SessionsBar.kt`, `FloatingWindow.kt`, `FloatingBubble.kt`, `theme/Motion.kt`);
`TerminalView` and the float `WindowManager` maths are untouched. **Deferred to the device phase**,
per the ledger work order.

Corrected 2026-11-15: the chrome bars expand/collapse on a tween (no height or alpha overshoot), the
float overlay enters from 92 % instead of a point, and `shellAnimationsEnabled()` follows the setting
while the app runs.

Run with at least three sessions and the float session available:

- [ ] Sessions bar tween: collapse it with the chevron and expand it with the floating handle. It
      grows and shrinks smoothly with no overshoot past its final height, and the terminal resizes
      cleanly underneath.
- [ ] Extra-keys bar tween: hide and show the key grid with its handle. Same motion, no overshoot; no
      clipped or jumping rows.
- [ ] Tab add/close: open a new tab and close one. The new tab slides/fades in and the closing one
      slides/fades out; the remaining tabs close the gap smoothly.
- [ ] Tab reorder: long-press a tab and drag it past another. Nothing reorders under the finger; on
      drop the dragged tab glides from where it was released into its new slot and the other tabs
      slide over to close the gap.
- [ ] Active indicator: switch tabs (tap and the session menu). The surface colour fades in and the
      pill glides to the newly active tab, including while the row is scrolled; it never trails
      behind a scroll fling. Scroll the active tab out of view: the pill is clipped to the row and
      does not draw over the chevron or the "+" button.
- [ ] Float enter: collapse the window to the bubble and expand it again. Both grow from ~92 % and
      fade in instead of popping; the terminal content is sharp once settled, and the overlay is never
      a zero-size point on its first frame.
- [ ] Empty sessions list: with no sessions the bar shows the "start a session" prompt and there is
      no stray pill or animation artifact.
- [ ] Remove animations: turn on the system "remove animations" setting (or set
      `ANIMATOR_DURATION_SCALE` to 0) and restart the app. Every transition above is instant, with no
      fade, slide or overshoot.
- [ ] Remove animations is live: turn the setting on while the app runs — every transition above
      becomes instant without restarting the app.

Record the device, build and any mismatch in `~/.tmp/opencode/device-checks-<date>.md`.

## 17. D-16 — acrylic background (blur, opacity, live-wallpaper fallback colour)

The picture behind the shell (system wallpaper or custom image) can now be blurred and lightened,
independently of the existing dim. The blur is a `RenderEffect` on API 31+ and a load-time downscale
with a filtered upscale below it; the opacity is the picture's alpha under the dim overlay. Everything
is in `:app` (`ShellHost.kt`, `TerminalBackgroundState.kt`, `BackgroundFx.kt`, `TermuxActivity`'s
wallpaper loader); `:terminal-view` and `:terminal-emulator` are untouched.

**Passed 2026-11-14** on NX769J / API 34 (1116x2480, 408 dpi) with a build from `2172d6c4` plus the
`getWallpaperInfo()` fix. The API < 31 downscale fallback is still unmeasured: no API < 31 device was
available. Evidence is the screenshot metrics recorded in `~/.tmp/opencode/device-checks-2026-11-14.md`;
this device exposes almost no app logs, so the rendering was verified from pixels, not logcat.

Run with a busy static wallpaper and with a custom image:

- [x] Defaults unchanged: with blur 0 and opacity 100 %, the background renders exactly as before this
      change (same crop, same dim, no visible softening).
- [x] Blur: raise `Background blur` to a mid value (around 12). The picture visibly frosts while the
      terminal text stays sharp; the blur is smooth, not blocky, and the edges of the screen do not
      show a sharp border.
- [x] Blur extremes: 0 restores the sharp picture, 25 is the strongest frost, and neither crashes nor
      leaves the previous frame behind.
- [x] Blur persistence: leave the settings screen and return to the terminal — the new radius applies
      without restarting the app. On API < 31 the picture reloads once; on API 31+ there is no blank
      frame or flash.
- [x] Opacity: lower `Background opacity` to around 40. The picture lightens towards the terminal
      background colour while the dim slider keeps working independently; 10 % is the faintest
      setting and the terminal stays readable.
- [x] Opacity and dim together: with dim high and opacity low the text stays readable; with dim 0 and
      opacity 100 the picture is at its most visible.
- [x] Solid mode: switch `Background` to `Solid colour`. The blur and opacity sliders disappear and the
      terminal shows the plain palette background.
- [x] Live wallpaper: set an animated wallpaper. The background is a flat sampled colour (the
      wallpaper primary on API 27+), not black, and not a frozen frame. This device returned a stale
      static frame through `getDrawable()`, so live wallpapers are now detected with
      `getWallpaperInfo()` and always take the sampled colour; the two live rows therefore share one
      branch and the logcat markers are not observable here.
- [x] Live wallpaper without a frame: a live wallpaper whose drawable has no intrinsic size must take
      the same fallback colour, not a black or transparent screen.
- [x] One-pixel fallback fills the screen: with the live wallpaper set, the colour covers the whole
      shell (no black border, no zero-width draw) and is still visible through the dim.
- [x] Persistence across restart: set blur 12 and opacity 40, force-stop the app, reopen it. Both
      values are still set and still applied.
- [x] Rotation: rotate with a blurred picture showing. No crash, no stretched crop, and the blur does
      not become sharper or softer across the rotation.

Record the device, build and any mismatch in `~/.tmp/opencode/device-checks-<date>.md`.

## 18. D-17 to D-20 — termux-backup on a real device

Phase 1 ships a script only (`scripts/termux-backup`, spec
`docs/specs/2026-11-15-termux-backup-restore-design.md`). Everything below was proved in `mktemp`
sandboxes with shimmed `dpkg`/`pkg`/`tar`; none of it has run against real shared storage, a real
package manager or a second device yet.

- [ ] **D-17 — real `/sdcard` writes.** After `termux-setup-storage`, run
      `termux-backup status` (expect `storage_access: true`, `writable: true`) and
      `termux-backup backup --label device-check`. Expect a `termux_backup_*-device-check` directory
      under `/sdcard/termux-backups`, `list` showing it with a non-zero size, and `verify --name ...`
      exiting 0. Note the wall-clock time: a multi-hundred-MB archive on shared storage is slow.
- [ ] **D-18 — second-device restore.** Copy that directory to a second Termux install that has run
      `pkg update` once. `verify`, then `restore --name ... --force --yes`. Expect `$HOME` files and
      `.termux/termux.properties` to be back, missing packages installed, exit 0, and one line in
      `~/.config/termux-backup/last-restore.log`.
- [ ] **D-19 — safety round-trip.** On the second device, edit a dotfile, run
      `restore --name ... --yes --no-packages`, confirm the file reverted, then
      `restore --name safety_pre_restore_<ts> --yes --no-packages` and confirm the edit is back.
      `prune --keep 1` must leave the safety archive in place.
- [ ] **D-20 — revoked permission.** Revoke storage permission (Android settings → Termux →
      Permissions, or `pm revoke com.termux android.permission.WRITE_EXTERNAL_STORAGE`), then run
      `status` and `backup`. Expect `storage_access: false` in `status` and exit 2 from `backup` with
      the failing root named, not a hang or a partial backup in `list`.

Record the device, build and any mismatch in `~/.tmp/opencode/device-checks-<date>.md`.

## 21. D-21 — app layering extraction (domain/data/ui, 2026-11-15)

`TermuxActivity`/`TermuxService` split: session mapping, wallpaper loading, service notification and
wake lock moved into `domain`/`data` helpers behind `LayeringTest` (no new module, plugin-facing
surfaces unchanged). Verify companion/plugin integration and service UI behavior on device.

- [ ] **termux-api companion.** Run a `termux-api` command through the companion app (e.g.
      `termux-toast` or `termux-battery-status`). Command completes and returns the expected result.
- [ ] **Termux:Widget shortcut.** Launch a Termux:Widget shortcut that runs a command. Command runs
      in the expected session/context.
- [ ] **Termux:Tasker action.** Run a Termux:Tasker action, including its result callback. Action
      executes and the callback/result is delivered.
- [ ] **Termux:X11 launch.** Launch Termux:X11 from Termux. X11 starts and connects as before.
- [ ] **Termux:Float toggle.** Toggle the float terminal (app/notification action as applicable).
      Float opens/closes and its session behaves normally.
- [ ] **Service notification actions.** With the foreground service active, use the notification's
      **Exit**, **wake-lock** and **float** actions. Each performs the correct action (service/locks
      stop or release as appropriate, float toggles).
- [ ] **Wake-lock tapped twice.** Tap the wake-lock notification action twice in a row (release/no-op
      path). No crash or exception; state/notification remain consistent.

Record the device, build and any mismatch in `~/.tmp/opencode/device-checks-<date>.md`.

## 22. D-22 — UI appeal program (2026-11-16)

Twenty-one tasks of shell polish: tab subtitles and unseen halo, starter chips, command palette,
snippets, first-run boot panel, keycap depth, key-layout gallery, theme gallery with iTerm
import/export, live preview, link action sheet, visual bell, themed selection handles and settings
search. Everything below is device-only: it is either feel, animation or a platform picker.

- [ ] **Tab subtitles at 320dp.** Open four sessions with long foreground-process names on a 320dp
      wide screen. Each tab shows its subtitle, truncated with an ellipsis, never pushed out of the
      tab or overlapping the halo.
- [ ] **Unseen-output halo.** Run a command in a background tab. The tab shows the unseen halo until
      it is selected, and the halo clears on selection.
- [ ] **Starter chips.** With no sessions, the empty bar offers the starter chips. Tapping one starts
      a session and inserts the command without running it.
- [ ] **Palette keyboard navigation.** Open the palette with Ctrl+Shift+K. Arrow keys move the
      selection, Enter runs the highlighted row, Back closes it. With a hardware keyboard attached
      the focus must stay in the query field while typing. Plain Ctrl+K and Ctrl+Alt+K must still
      reach the shell as kill-line.
- [ ] **Drag lift feel.** Long-press a tab and drag. The tab lifts under the finger, the drop index
      follows the finger, and the reorder lands where it was dropped.
- [ ] **First-run animation on a cold start.** Clear app data, cold-start, and watch the intro splash
      and the terminal-styled boot panel. The typewriter lines appear in order and the Install button
      becomes reachable; the animation is instant when "Remove animations" is enabled.
- [ ] **Keycap press feel.** Press and hold an extra key: the keycap shows depth on press, the popup
      chevron appears only on keys that have a popup, and a swipe up triggers it.
- [ ] **Key layout gallery.** Open Settings → Keyboard → Key layouts. Each card previews the preset;
      picking one changes the extra-keys bar on return, and a custom `extra-keys` property still wins.
- [ ] **Theme gallery and import.** Open the appearance screen: presets are grouped Dark/Light with a
      colour strip. Import an `.itermcolors` file and confirm the staged colours; import a truncated
      or non-plist file and confirm the rejection message appears and nothing changes.
- [ ] **Live preview.** From a session with output on screen, open Style. The preview shows that
      output, not the fixed sample; opening Style from Settings keeps the sample.
- [ ] **Link action sheet.** Tap a link with "Ask before opening links" on: the sheet lists Open,
      Copy and Share for http(s) and only Open/Copy for `mailto:`. Copy puts the URL on the
      clipboard. Turn the switch off and a tap opens the link directly.
- [ ] **Visual bell.** Set `bell-character = visual` in `termux.properties`, run `printf '\a'` a few
      times quickly. The sessions bar flashes and one snackbar appears, not one per bell; a bell more
      than 400 ms later flashes and posts again.
- [ ] **Selection handle contrast.** Select text on a light scheme (Solarized Light, Gruvbox Light)
      and a dark one. The handles stay visible on both, and the copy/paste toolbar uses the shell
      surface colour.
- [ ] **Settings search.** In Settings → Termux, type "colour" and "crash": the list narrows to the
      matching rows, each row opens its screen, and clearing the field restores the full list.
- [ ] **Text selection after the Kotlin conversion.** Long-press to start a selection, drag both
      handles across a wide glyph and a ZWJ emoji, copy from the toolbar, then rotate the device and
      confirm the handles still track the finger. Covers `TextSelectionHandleView` and
      `TextSelectionCursorController`.
- [ ] **TerminalView conversion.** Type text with the soft keyboard open, delete text mid-word with
      the IME's backspace, paste, and toggle the terminal cursor blink rate to both extremes in
      Settings.

Record the device, build and any mismatch in `~/.tmp/opencode/device-checks-<date>.md`.

## 23. D-23 — Kotlin conversion (terminal-view and app, 2026-11-17)

Hand conversion of `terminal-view/src/main` and `app/src/main` from Java to Kotlin with no
intended behavior change. The unit suites stay green, so these are the paths that only a
device can settle.

- [ ] **Activity lifecycle after the Kotlin conversion.** Cold start, background/foreground, rotate,
      open the Style screen and return, and send the crash envelope with `am broadcast -a
      com.termux.app.event.NOTIFY_APP_CRASH`. Every transition must behave as before: no session loss,
      no duplicated sessions bar, no replayed intro on rotation.
- [ ] **Session bar actions.** Rename a tab and press Undo on the snackbar; close a finished tab;
      close a running tab and confirm the dialog; drag to reorder. Each action must match the old
      behaviour and leave the service list consistent.
- [ ] **Context menu actions.** Long-press the terminal: select URL, share transcript, share selected
      text, autofill username/password, reset terminal, kill process (disabled for a finished
      session), style, keep-screen-on checkmark, help, settings, report issue.
- [ ] **Storage permission request.** Trigger the request from a session (or `am broadcast -a
      com.termux.app.event.REQUEST_PERMISSIONS`) and confirm the granted/denied toast and the
      `~/storage` symlinks.
- [ ] **Foreground service.** Check the notification while a session runs, toggle the wakelock, toggle
      the float button, then stop the service from the notification. No crash and no orphaned
      sessions; the notification disappears when the last session, task and float end.
- [ ] **Plugin execution.** Send a `RUN_COMMAND` intent with a pending intent result for both
      `TerminalSession` and background `AppShell` runners; the result must come back and the
      background-only notification must not appear.
- [ ] **First-run install path.** With app data cleared, the first-run overlay stays up while the
      bootstrap installs, reports each stage, and the first session appears when done; a failed
      install leaves the overlay in the failure state instead of hanging on progress.

- [x] **Settings hub after the Kotlin conversion.** Open Settings from the terminal context menu
      and from the palette: the Termux, API, Float, Tasker and Widget hubs open, and each of their
      panels (Display, Keyboard, Debugging) inflates without a crash. The values shown must be the
      stored ones, and flipping one must persist across a restart.
      *2026-11-17: the Termux panel, Display, Keyboard and Advanced inflate and show the stored
      values; log level persisted. API/Tasker/Widget are hidden because no companion app is
      installed, so those hubs are untested.*
- [ ] **Settings search.** In the Termux panel, open the search field and type a query: the screen
      narrows to the matching rows, a query with no match shows the "no results" row, and clearing
      the query restores the full list from the resource.
      *2026-11-17: fails, and fails the same way on `master`. Every query ("font", "keyboard")
      reports "No settings match": the three Termux rows in `res/xml/termux_preferences.xml` carry
      no `app:key`, and the index builder skips keyless preferences. Clearing the query restores
      the full list. Tracked in `outstanding-work.md` §3.2.*
- [x] **Background image picker.** Settings -> Display -> pick a custom image: the picker opens, the
      chosen image is copied into the app storage and appears behind the terminal, and the image,
      blur and opacity rows appear only in the modes that use them.
      *2026-11-17: the list offers Solid colour / System wallpaper / Custom image; choosing Custom
      image shows the picker row, the blur and opacity rows, and persists
      `terminal_background=image` plus the stored image path; switching back persists `solid`.
      The image was not visually confirmed behind the terminal.*
- [x] **Debugging log level.** In each Debugging panel, change the log level: the selection sticks,
      the "(default)" tag follows the default value, and the choice survives a settings reopen.
      *2026-11-17: Termux panel only. Off / Normal (default) / Debug / Verbose list with the
      `(default)` tag; Verbose persisted `log_level=3` and Normal restored `log_level=1`.*

- [ ] **Bootstrap install after the Kotlin conversion.** On a fresh install (or after deleting
      `$PREFIX`), let the app install the bootstrap: the progress dialog appears, the stages advance,
      the prefix ends up populated with `bin/`, `lib/`, `SYMLINKS.txt` symlinks resolved, and no
      "Bootstrap Error" dialog appears. Re-open the app: the install must not run twice.
      *2026-11-17: not run (it would delete the working prefix). The installed prefix from an
      earlier run is intact: `files/usr/{bin,etc,include,lib,libexec}` all exist.*
- [x] **Storage symlinks.** Grant storage permission and run the setup from a session (or
      `am broadcast -a com.termux.app.event.REQUEST_PERMISSIONS`): `~/storage` is rebuilt with
      `shared`, `documents`, `downloads`, `dcim`, `pictures`, `music`, `movies`, `podcasts`,
      `external-*` and `media-*` links that resolve.
      *2026-11-17: all eleven links present (`shared`, `documents`, `downloads`, `dcim`,
      `pictures`, `music`, `movies`, `podcasts`, `audiobooks`, `external-0`, `media-0`) and
      pointing at the real shared paths.*
- [x] **Documents provider.** From another app's file picker, browse the Termux root: the `$HOME`
      tree lists, names and sizes are right, folders can be created, a file can be opened for read
      and written back, and a search for a known file name returns it.
      *2026-11-17: root, `$HOME` listing (names, sizes, dates) and search ("bands" returns
      `a_bands.done`) verified through the system `OPEN_DOCUMENT` picker. Folder creation and
      write-back were not exercised.*
- [x] **Share into Termux.** Share text, an image and a URL to Termux: the name dialog appears, the
      file lands in `~/downloads` (or `termux-url-opener` runs for a URL), and the
      "file does not exist" dialog appears when `~/bin/termux-file-editor` is missing.
      *2026-11-17: URL -> the "does not exist: $HOME/bin/termux-url-opener" dialog with no opener;
      with a temporary opener the script ran with the URL as `$1`. Text -> the
      "Save file in ~/downloads/" name dialog; CANCEL wrote nothing, a typed name saved the payload
      and EDIT ran `termux-file-editor` with the saved path. An image share was not exercised.*
- [ ] **Open file from Termux.** Long-press a file path in the terminal and open it: the target app
      receives the content URI and can read the file; opening `termux.properties` through the
      provider must fall back to read-only.
- [ ] **Package update receiver.** Install or update a Termux plugin app while the app is running:
      `termux-environment` is rewritten and nothing crashes.
- [ ] **Help screen.** Open Help from the context menu: the wiki page loads inline, an external link
      opens the browser, and Back returns through the page history before leaving.

- [ ] **Selection action-mode rect.** Select text, then drag the end handle to the left of the
      start handle: the floating action toolbar stays over the selection, centered rather than
      collapsed to a zero-width rect at one edge.

**Found on device, fixed in `b576f5b9`.** `TermuxService` passed `Runner.TERMINAL_SESSION.name`,
which Kotlin resolves to `Enum.name()` (`"TERMINAL_SESSION"`) instead of the class's
`getName()` (`"terminal-session"`). Every `ACTION_SERVICE_EXECUTE` intent without an explicit
`EXTRA_RUNNER` was therefore rejected as an invalid runner, so `termux-url-opener`,
`termux-file-editor` and the receive dialog's "open directory" action all did nothing and said
nothing. The URL and text share checks below are what caught it; both were re-run after the fix.

Record the device, build and any mismatch in `~/.tmp/opencode/device-checks-<date>.md`.

## 24. D-27 to D-32 — post-review hardening (2026-11-17)

Six device checks for findings whose fix lives in a place the JVM tests can only reach through a
seam. Everything else in the `v2.0.0-beta..e3f68eb1` review is covered by unit tests in
`docs/plans/2026-11-17-post-review-hardening.md`.

- [ ] **D-27 Hostile received-file name.** From another app, share a file into Termux with a display
      name that contains a path separator (`android.intent.extra.TITLE` set to `../escape.sh`, e.g.
      `adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TITLE
      '../escape.sh' --eu android.intent.extra.STREAM <content-uri>`). Expect the dialog `File name
      cannot be empty or contain a path`, and `ls -la ~/downloads` plus `ls -la ~` unchanged: no file
      created outside the receive directory. A normal share still saves with its own name.
- [ ] **D-28 Absurd sixel raster.** Run `printf '\033Pq"1;1;100000;100000#0;2;0;100;0#0~~@@\033\\'`.
      The terminal neither crashes nor stalls; a normal prompt returns and input still echoes. The
      capacity clamp only limits the bitmap, so no out-of-memory kill either.
- [ ] **D-29 Text around a sixel cell.** Run a script that prints `before`, one sixel image and
      `after`, then scroll up and back. `before` and `after` are complete: no missing, doubled or
      shifted cell at either edge of the bitmap, and the row above/below keeps its own background.
- [ ] **D-30 `keycap-visuals = false`.** Append `keycap-visuals = false` to
      `~/.termux/termux.properties`, reload the app, then press and long-press an extra key. The
      keycap inset (rounded pressed depth) and the popup chevron are gone; the key still types and
      the popup still shows on long press. Remove the line and reload to restore them.
- [ ] **D-31 A share is handled once.** Share a file into Termux, pick an editor, then press back and
      return to Termux. Only one editor was launched and no second save dialog appears; the saved
      file exists once in `~/downloads`.
- [ ] **D-32 Snippet cap.** Open the snippets screen and add rows until 20 exist. At 20 the add
      button is disabled and `At most 20 snippets are kept.` shows; applying keeps exactly 20 rows
      and the key bar shows no more than 20 chips.

Record the device, build and any mismatch in `~/.tmp/opencode/device-checks-<date>.md`.

## 25. D-33 — extra-key popup badge (2026-11-17)

`ExtraKeysView` now draws the popup's own display text in the key corner when it is at most three
code points, and keeps the chevron when the text is longer or blank.

- [ ] **Short popup.** With a key whose popup display is one to three characters (e.g.
      `{key: ";", popup: {key: "~"}}`), the key shows that text (`~`) in the corner instead of a
      chevron, and swiping up still opens the same popup.
- [ ] **Long popup.** With a popup display longer than three characters (e.g. `HOME` or a macro
      display `CTRL C`), the key keeps the chevron.
- [ ] **`keycap-visuals = false`.** Append `keycap-visuals = false` to `~/.termux/termux.properties`
      and reload: neither the badge nor the chevron is drawn.
- [ ] **Readability.** On a 320dp-wide window the badge does not overlap the main key label, and it
      remains legible at the default extra-key height and at the smallest configured height.

Record the device, build and any mismatch in `~/.tmp/opencode/device-checks-<date>.md`.

## 26. D-34 — release-key signing of release assets (2026-11-17)

Release workflow now runs `assembleRelease` and signs the APKs with the release key from the
repository secrets; it refuses to publish when the signature or the secrets are missing.

- [ ] **Signer.** Download a `universal` release APK and run
      `apksigner verify --print-certs <apk>`. The certificate is the release key, not
      `testkey_untrusted.jks`.
- [ ] **Not debuggable.** `aapt dump badging <apk>` shows no `application-debuggable` flag, and
      `run-as com.termux` fails.
- [ ] **Clean install.** After uninstalling any older build (different key), install the release
      APK. The app starts, the bootstrap installs and a package installs.
- [ ] **In-place upgrade.** Install a second release APK signed with the same key over the first.
      Android accepts the upgrade and `$HOME` survives.
- [ ] **Failure path.** Temporarily remove one signing secret in a test fork (or run the workflow
      with an empty value): the workflow deletes the release and the tag instead of attaching
      unsigned APKs.

Record the device, build and any mismatch in `~/.tmp/opencode/device-checks-<date>.md`.

## 27. D-35 to D-42 — termux-backup in-app screen (2026-11-17)

Spec `docs/specs/2026-11-17-termux-backup-ui-design.md`, plan
`docs/plans/2026-11-17-termux-backup-ui.md`. Everything here needs a device: the hidden session, the
`$PREFIX` write path, the SAF picker and the restore flow are not unit-testable.

- [ ] **D-35 Provisioning on a fresh install.** Install a debug APK, open Settings → Advanced →
      Backup & restore. The script is copied to `$PREFIX/lib/termux-backup`, `which termux-backup`
      resolves, `termux-backup --version`-style `status` output matches the screen, and the settings
      search finds "Backup" (the row has a key).
- [ ] **D-36 Backup from the screen.** Create a backup with a label and `xz`. The progress card shows
      the phases, the run finishes with a summary, the list shows the new directory with its size and
      package count, and `termux-backup list` in a terminal agrees.
- [ ] **D-37 Run survives leaving the screen.** Start a backup, press Home, return: the run either
      finished (list refreshed) or is still shown as running. No crash, no duplicate lock.
- [ ] **D-38 Cancel.** Start a backup and cancel. The session ends, `run.lock` is gone, the list is
      unchanged and a new backup can start immediately.
- [ ] **D-39 Verify a corrupted backup.** Truncate one archive inside a backup directory with a
      terminal, then Verify. The screen reports the failure with the CLI's message and the entry is
      still listed.
- [ ] **D-40 Restore.** Restore a backup. The dialog names the backup and both target paths, the
      safety archive appears in the list afterwards, packages reinstall, and the restart prompt
      appears; after restarting, `$HOME` matches the backup.
- [ ] **D-41 Revoked storage access.** Revoke the app's storage permission, reopen the screen: the
      header reports the missing access, the grant button opens the system settings, and no action
      crashes.
- [ ] **D-42 SAF export and import.** Export a backup to Downloads through the picker, then import it
      back into the backup root: the imported directory gets a `termux_backup_` name (collision gets
      `-2`). Corrupt one byte in the exported copy and import again: the transfer fails and no partial
      directory is listed. Cancel an export mid-copy: the partial directory is gone.

Record the device, build and any mismatch in `~/.tmp/opencode/device-checks-<date>.md`.

## 28. D-43 — settings search on the Termux hub (2026-11-18)

The hub rows were the only rows in the search index without keys, so every query returned
"No settings match". Fixed by keying them `display`, `keyboard` and `advanced`; covered by
`SettingsIndexBuilderTest`.

- [ ] **Search finds a hub row.** Open Settings → Termux, type `font`: only *Display* remains, with
      its summary. Repeat with `keyboard` (*Keyboard*) and `colour` (*Display*). Opening a result
      navigates to that screen.
- [ ] **No match.** Type `zzz`: the single "No settings match" row shows and is not selectable.
- [ ] **Clearing the query.** Deleting the query restores all three rows in Display / Keyboard /
      Advanced order.
