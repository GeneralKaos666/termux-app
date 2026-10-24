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

## 1. Status

| ID | Check | Status | Where |
|---|---|---|---|
| D-1 | WS3 shaping/ligatures | **7 of 8 passed 2026-10-20/21**; API 21–28 legacy path deferred (no device) | `docs/specs/2026-10-18-ws3-shaping-ligatures-design.md` §7 |
| D-2 | WS4 kitty graphics (6 items) | **done 2026-10-19** | `docs/specs/2026-10-18-ws4-graphics-placement-design.md` §8 |
| D-3 | WS2 dirty-region visual checklist (7 items) | pending | `docs/specs/2026-10-17-renderer-dirty-region-design.md` §10 |
| D-4 | WS1 engine sanity on a real emoji/link session | pending (only inspection so far) | `docs/plans/2026-10-17-terminal-engine-cell-model.md` branch gate |
| D-5 | Appearance preset readability (Solarized Light, Gruvbox Dark, One Dark) | machine pass done; **human pass pending** | §6 below |
| D-6 | Appearance screen full checklist | pending | `docs/plans/2026-10-14-in-app-terminal-appearance.md` §Verification |
| D-7 | Settings IA check | pending | `docs/plans/2026-10-16-settings-ia.md` Task 4 note |
| D-8 | B1 onboarding edges (cancel/retry, rotation mid-install, back key) | pending | `docs/plans/2026-10-10-shell-sessions.md` C1b/B1 gate |
| D-9 | A2 multi-window split-pane stability | pending (system placement ran clean; a stable focused split pane was not automatable) | `docs/plans/2026-10-10-shell-sessions.md` A2 results |
| D-10 | Termux:Float `canDrawOverlays()` under `sharedUserId` | pending, only if float is picked up | `outstanding-work.md` §5.2 |

Not pending: the WS5 hyperlink/`SGR 58` device check passed 2026-10-18 (Chrome opened on an `https`
tap twice; the same tap on `file:` opened nothing; underline colours confirmed from raw `screencap`
pixels, including a scrolled-back hit-test and a link-without-`SGR 4` control).

## 2. Suggested order

1. **D-3, D-4** — cheapest; they only need a running debug build and a handful of escape sequences.
2. **D-6, D-7** — walk the settings/appearance screens once each.
3. **D-5** — the human preset pass (portrait + landscape, smallest + largest font).
4. **D-8, D-9** — fresh-install and multi-window edges; these may need a reinstall or a second window.
5. **D-1 remaining** — only if an API 21–28 device becomes available.
6. **D-10** — only if Termux:Float gets picked up.

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

All seven acceptance items from the renderer spec §10:

1. [ ] `yes | head -100000` — no stale/ghost pixels, no flicker bands.
2. [ ] Full-screen redraw app (`htop`-style) — rows repaint completely.
3. [ ] Idle scrollback drag — content shifts cleanly (viewport path), no smearing.
4. [ ] Text selection start/extend **while** output runs — highlight and output both correct.
5. [ ] One line each of `4:1`…`4:5` underlines — visibly distinct: solid, double, wave, dots, dashes.
6. [ ] Font-size change and rotation/resize — full repaint, nothing stale.
7. [ ] Cursor state with no output — move (`\033[10;20H`), reverse video (`\033[?5h` / `\033[?5l`),
       hide/show (`\033[?25l` / `\033[?25h`), shape change (`\033[5 q`) — no ghost cursor, stale
       shape or stale video state.

## 5. D-4 — WS1 engine sanity

- [ ] Run a real emoji/link session on a device and note anything that looks wrong. The renderer at
      WS1 still drew plain underlines and ignored link ids; that was expected then, and WS5 now
      consumes them — so re-check that emoji widths, wrapping and link underlines look right.

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

These numbers bound readability but do **not** answer the human half. Boxes stay unticked until that
pass happens.

- [ ] **Solarized Light** — switch to it from the Style menu, then from Settings. *(Both paths
      verified reachable; human visual pass pending.)*
- [ ] **Gruvbox Dark** — same. *(Same status.)*
- [ ] **One Dark** — same. *(Same status.)*

For each, check:

- [ ] The prompt and normal output are easy to read against the background (fg/bg and ANSI 0–7).
- [ ] The bright ANSI colours (8–15) stay distinguishable from their dim counterparts.
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

- [ ] Both entry points work (Style chip row and Settings → Display → Appearance).
- [ ] Edit each control; the preview matches the applied result.
- [ ] Apply updates the live terminal without a restart.
- [ ] Back discards when dirty.
- [ ] Font picker: valid `.ttf` and invalid `.ttf` (must keep the previous font on failure).
- [ ] Reset returns to defaults.
- [ ] Rotation does not lose staged state.
- [ ] Dark/light shell theme leaves terminal colours unchanged.

## 8. D-7 — Settings IA check

- [ ] `Settings → Termux` opens exactly three entries — Display / Keyboard / Advanced.
- [ ] Each opens the expected screen with the expected rows.
- [ ] Toggling a row persists across an app restart.

## 9. D-8 — B1 onboarding edges

Fresh install already passes the happy path (overlay, Install, first session, overlay dismissed,
relaunch shows nothing). Still unexercised:

- [ ] Cancel, then retry after a failed bootstrap install.
- [ ] Rotate mid-install.
- [ ] Back key during installation.

## 10. D-9 — A2 multi-window split pane

- [ ] One manual check: place the app in a real focused split pane and confirm the terminal keeps
      focus, IME and sizing. (The system placed the app in a `mode=3` multi-window task without a
      crash; a stable focused split pane was not automatable on this RedMagic/ZTE ROM.)

## 11. D-10 — Termux:Float overlay permission

Only if Termux:Float is picked up (`outstanding-work.md` §5.2).

- [ ] Verify `Settings.canDrawOverlays()` under `sharedUserId` on a device. The float service
      currently catches the failure instead of checking, with the comment that it "always returns
      false, perhaps due to sharedUserId". Do not "fix" this blindly.
