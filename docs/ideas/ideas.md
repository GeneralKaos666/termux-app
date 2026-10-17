# Ideas and manual checks

## Colour-preset checks on real devices

The appearance screen ships seven presets. Four are already familiar (Termux default, and the
schemes inherited from Termux:Styling); three were added without a device pass and only ever need a
human eye, because no unit test can judge whether text is comfortable to read. Run this on a phone
in both portrait and landscape, at the smallest and largest font sizes, and in bright light.

For each preset:

- [ ] **Solarized Light** — switch to it from the Style menu, then from Settings.
- [ ] **Gruvbox Dark** — same.
- [ ] **One Dark** — same.

For each, check:

- [ ] The prompt and normal output are easy to read against the background (foreground/background,
      and ANSI 0-7).
- [ ] The bright ANSI colours (8-15) stay distinguishable from their dim counterparts.
- [ ] The cursor is visible against both the background and the text it sits on.
- [ ] A selection highlight does not swallow the selected text.
- [ ] `ls --color`, a `git diff`, and `vim` (syntax highlighting) are legible.
- [ ] After picking the preset, Apply, then leave and reopen the screen: the same colours come back.

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
