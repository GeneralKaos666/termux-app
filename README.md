# Termux App: GeneralKaos666's Fork

Termux turns an Android phone into a Linux terminal. This repository is the app: the screen you type
on and the terminal emulation behind it. The packages you install inside Termux come from
[termux/termux-packages](https://github.com/termux/termux-packages).

I keep my own version of the app here: a friendlier shell and tidier session handling, on top of the
emulation upstream ships. It starts from
[termux/termux-app](https://github.com/termux/termux-app) commit `8629e632` (2026-09-26), plus a few
dozen commits of mine.

## What I changed so far

I turned on Kotlin and Jetpack Compose in the app module, then moved the screen onto a Compose shell.
The terminal still draws through the same `TerminalView`; I did not touch emulation.

Sessions changed the most. A bar along the top lists them and gives each tab a roomier touch target.
Tap a tab to switch to that session, or long-press and drag to reorder. The active tab carries a
small menu for renaming, closing and moving it.

The extra keys row changed shape too. I replaced the old swipeable row with a Compose bar and added
presets. The grid stays alive when it collapses, which stops the flash. Keys stopped staying shaded
after a press.

The terminal look is mine now as well. The Appearance screen opens from the terminal's Style menu and
from Settings. It holds colour-scheme presets, an editor for the background, foreground, cursor and
all sixteen ANSI colours, a font picker with a font size, and cursor style and blinking, all shown in
a live preview before you apply. It replaces the separate Termux:Styling app.

Fresh installs get a short first-run walkthrough and a 14dp default font size. You can point the
terminal background at your system wallpaper or a picture of your own.

Smaller fixes went in too: quick action icons no longer render black, and I can trigger the debug
build workflow by hand. Two failures are gone, one where `FileReceiverActivityTest` needed Conscrypt
natives, and one where Compose previews broke the release build. My reorder commit introduced two
bugs of its own, both fixed: tapping a tab had stopped switching sessions, and a finished drag did
not move the tab.

## What's not done yet

The other half: what is not done.

| Area | Not done yet | State |
|---|---|---|
| Sessions | Pin a tab | Small. The bar reorders by hand. |
| Sessions | Keep a shell alive across app restarts | Needs its own design: shells live in the app process, so restoring means saving a snapshot and starting fresh sessions. |
| Sessions bar | Fallback titles follow the list position, so a reorder renames untitled sessions | Small. |
| Sessions bar | Reach rename, close and move on a non-active tab without tapping it first | Small. Only the active tab shows its ⋮ menu. |
| Shell | Route activity lifecycle and window insets through the Compose shell | Deferred. The XML shell is still in place. |
| Device checks | Exercise onboarding cancel/retry, rotation mid-install and the back key | Left for a later pass. |
| Device checks | Check the Solarized Light, Gruvbox Dark and One Dark presets | Shipped but not checked on device. |
| Appearance | Dead string resources, and a cursor override that cannot be cleared | Small. |
| Background | A live wallpaper | Only a static image works. A live one yields no usable frame. |
| Android | targetSdk is still 28, plus edge-to-edge, notification permission and predictive back | Deferred. |
| Settings | The preferences rework and a setup wizard | Only the defaults half shipped. |
