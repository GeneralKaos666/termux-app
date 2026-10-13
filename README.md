# Termux App: GeneralKaos666's Fork

Termux turns an Android phone into a Linux terminal. This repository is the app itself: the screen
you type on and the terminal emulation behind it. The packages you install inside Termux come from
[termux/termux-packages](https://github.com/termux/termux-packages).

I keep my own version of the app here. My goal is a friendlier shell and tidier session handling
on top of the terminal engine that already works. I built it on upstream
[termux/termux-app](https://github.com/termux/termux-app) commit `8629e632` (2026-09-26), and my
21 commits since then sit on top of it.

## What I changed so far

I started by turning on Kotlin and Jetpack Compose in the app module, then moved the screen onto
a Compose shell. The terminal keeps the same `TerminalView` that draws it, so the emulation is
untouched; everything around it is new.

Sessions got the most attention. A bar along the top lists them now. It adapts to the screen and
gives each tab a roomier touch target. Tapping a tab opens a session menu.

The extra keys row changed shape too. I swapped the old swipeable row for a Compose bar and added
presets, with the grid staying alive when it collapses so it no longer flashes. I also fixed keys
staying shaded after a press.

Fresh installs get a short first-run walkthrough and a font size that suits a new start. You can
point the terminal background at your system wallpaper or a picture of your own.

Smaller fixes came along the way: quick action icons no longer render black, the debug build
workflow can be triggered by hand, and two problems are gone, one where `FileReceiverActivityTest`
needed Conscrypt natives, and one where Compose previews broke the release build.

## What's next

I have no fixed plan. The shell is a maybe, along with the terminal engine and emulator
(`:terminal-emulator`) and the terminal view (`:terminal-view`).
