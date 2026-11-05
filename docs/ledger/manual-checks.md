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
