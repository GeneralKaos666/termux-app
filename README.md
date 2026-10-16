# Termux App: GeneralKaos666's Fork

Termux turns your Android phone into a Linux terminal. This repository is the app: the screen you
type on and the terminal emulation behind it. The packages you install inside Termux come from
[termux/termux-packages](https://github.com/termux/termux-packages).

I keep my own version of the app here: a friendlier shell and tidier session handling, on top of the
emulation upstream ships. I started from
[termux/termux-app](https://github.com/termux/termux-app) commit `8629e632` (2026-09-26) and added a
few dozen commits of my own.

## What I changed so far

I turned on Kotlin and Jetpack Compose in the app module, then moved the screen onto a Compose shell.
The terminal still draws through the same `TerminalView`; I did not touch emulation.

I gave sessions the most attention. I put a bar along the top, with a roomier touch target on each
tab. I can tap a tab to switch to it, or long-press and drag to reorder. The active tab carries a
small menu where I can rename, close and move it.

I reshaped the extra keys row too. I replaced the old swipeable row with a Compose bar and added
presets. I keep the grid alive when it collapses, which stops the flash, and I fixed keys staying
shaded after a press.

I took over the terminal's look as well. I built an Appearance screen that opens from the terminal's
Style menu and from Settings. It holds colour-scheme presets, an editor for the background,
foreground, cursor and all sixteen ANSI colours, a font picker with a font size, and cursor style and
blinking, all shown in a live preview before you apply, and it replaces the separate Termux:Styling
app.

I added a short first-run walkthrough and a 14dp default font size for fresh installs. You can point
the terminal background at your system wallpaper or a picture of your own.

I fixed smaller things too: quick action icons no longer render black, and I can trigger the debug
build workflow by hand. I cleared two failures, one where `FileReceiverActivityTest` needed Conscrypt
natives, and one where Compose previews broke the release build. My reorder commit introduced two
bugs of its own, both fixed: tapping a tab had stopped switching sessions, and a finished drag did
not move the tab.

## What I have not done yet

- I have not added tab pinning (small). I can reorder by hand.
- I cannot keep a shell alive across app restarts. That needs its own design: shells live in the app
  process, so I would save a snapshot and start fresh sessions on the next launch.
- I have not given untitled tabs a stable name, so a reorder renames them (small).
- I cannot rename, close or move a tab without switching to it first (small). I show the ⋮ menu only
  on the active tab.
- I have not routed the activity lifecycle and window insets through the Compose shell (deferred). I
  still run the XML shell.
- I have not exercised onboarding cancel/retry, rotation mid-install or the back key. I left that for
  a later pass.
- I have not checked the Solarized Light, Gruvbox Dark and One Dark presets. I shipped them but have
  not checked them on device.
- I left some dead string resources in, and I cannot clear a cursor override (small).
- I only support a static wallpaper; a live one gives me no usable frame.
- I still target SDK 28. Edge-to-edge, the notification permission and predictive back are still to
  come.
- I have not reworked the preferences or added a setup wizard. I shipped only the defaults half.
