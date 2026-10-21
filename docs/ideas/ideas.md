# Ideas and manual checks

## Colour-preset checks on real devices

The appearance screen ships seven presets. Four are already familiar (Termux default, and the
schemes inherited from Termux:Styling); three were added without a device pass and only ever need a
human eye, because no unit test can judge whether text is comfortable to read. Run this on a phone
in both portrait and landscape, at the smallest and largest font sizes, and in bright light.

**2026-10-20 partial pass (machine, not human).** The three presets below were switched to on-device
(NX769J / API 34) from both the Style menu and Settings, and the objective parts of the checklist
were measured rather than eyeballed: WCAG contrast for fg/bg and for ANSI 0-7, CIE76 colour distance
for bright-vs-dim, and cursor-vs-background. Palette source: `ColorSchemes.kt`. Numbers and method:
the device-check log at `~/.tmp/opencode/device-checks-2026-10-20.md` (scratch) and the summary in
`docs/ideas/2026-10-16-outstanding-work.md` §3. Headline results:

- **Solarized Light** — fg/bg contrast **4.13**, below WCAG AA (4.5); ANSI green/yellow/cyan also low.
  Weakest of the three for legibility. Values are Solarized's published palette, so this is an
  upstream-theme property, not a rendering bug.
- **Gruvbox Dark** — fg/bg **10.75** (comfortable); ANSI colour 0 equals the background (upstream
  convention for dark themes), everything else fine.
- **One Dark** — fg/bg **6.57**; brights 8–14 are *deliberately identical* to dims 0–7 (verified
  on-device: identical rendered RGB), so "bright vs dim" is indistinguishable by design.

These numbers bound readability but do **not** answer the human half of the checklist — the
aesthetic judgements (`ls --color` / `git diff` / `vim` "looks right", selection-highlight taste) and
the portrait/landscape and font-size matrix still need a person at the screen. The boxes below stay
unticked until that pass happens.

For each preset:

- [ ] **Solarized Light** — switch to it from the Style menu, then from Settings. *(Both paths
      verified reachable on device; the human visual pass is still pending.)*
- [ ] **Gruvbox Dark** — same. *(Same status.)*
- [ ] **One Dark** — same. *(Same status.)*

For each, check:

- [ ] The prompt and normal output are easy to read against the background (foreground/background,
      and ANSI 0-7).
- [ ] The bright ANSI colours (8-15) stay distinguishable from their dim counterparts.
- [ ] The cursor is visible against both the background and the text it sits on.
- [x] A selection highlight does not swallow the selected text.
      **Answered structurally (2026-10-20), so it needs no per-preset eyeballing.** The terminal does not
      paint a highlight over the text: `TerminalRenderer` inverts the run
      (`reverseVideo || invertCursorTextColor || lastRunInsideSelection`), swapping foreground and
      background inside the selection rect, which is computed from column indices. Because the two colours
      are exchanged rather than blended, the WCAG contrast ratio is mathematically unchanged by selection
      (measured on device: 19.33 before and 19.33 after). Only a colour flag changes, so glyph positions
      cannot shift either. Caveat: a cell whose foreground already equals its background (e.g. ANSI 0 on
      the dark presets) is invisible both before and after selection.
- [ ] `ls --color`, a `git diff`, and `vim` (syntax highlighting) are legible.
- [ ] After picking the preset, Apply, then leave and reopen the screen: the same colours come back.
      *(One Dark verified: applied from the Style path, then read back unchanged from the Settings path.
      Solarized Light and Gruvbox Dark still to confirm.)*

Record anything that fails here with the preset name and a screenshot, so the palette can be
adjusted rather than dropped.

## Porting Termux:Float into this app

Termux:Float (`com.termux.window`) is the floating-terminal plugin: a foreground service that adds a
`TerminalView` to the screen through `WindowManager` — drag to move, pinch to resize, minimize to a
bubble. It lives in its own repo, `termux/termux-float`, and this app is already wired for it: the
float constants, properties paths, notification channel, `TermuxFloatAppSharedPreferences`, and the
hidden `termux_float` Settings entry all ship here today. Only the window itself is missing.

The short answer to "can we port it in?" is yes, but not by cloning the repo into this one. It is a
separate Gradle build (its own `settings.gradle`, wrapper and AGP) and it depends on *published*
`termux-shared` / `terminal-view` artifacts, so dropping it in would leave an unbuilt nested project
plus a second copy of every `com.termux.shared` class. Both projects are GPLv3, so licensing is not
the obstacle. Two shapes are on the table:

- **A — add a `:float` module.** Move the nine float sources in, point them at
  `project(':termux-shared')` and `project(':terminal-view')`, and keep `applicationId
  com.termux.window` with `sharedUserId`. A faithful, plugin-style second APK, roughly a day's work,
  and the existing Settings entry starts working. The debug keystore already matches, which is what
  makes `sharedUserId` viable here.
- **B — build the float into `:app`** as a first-class floating terminal over the sessions
  `TermuxService` already runs. No second APK and no `sharedUserId` constraints, but it needs real
  design work against the Compose shell host, and `TermuxFloatAppSharedPreferences.build(…)`
  currently exits the app unless the applicationId is `com.termux.window`, so it cannot be copied
  across as-is.

Decided neither yet (2026-10-16) — recorded rather than started. The full probe inventory (file and
line references, the resource and manifest list, and the build-plumbing gotchas) is kept locally in
`docs/superpowers/ideas/2026-10-16-termux-float-port-idea.md`, which is gitignored; this section is
the tracked summary.

## Not yet decided

- Whether the appearance screen should also surface the extended 256-colour palette; today only
  `color0`..`color15` are editable, and unmanaged keys are preserved untouched.
