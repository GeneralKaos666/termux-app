# Termux App: GeneralKaos666's Fork

Termux turns an Android phone into a Linux terminal. This repository is the app itself: the screen
you type on and the terminal emulation behind it. The packages you install inside Termux come from
[termux/termux-packages](https://github.com/termux/termux-packages).

I keep my own version of the app here. My goal is a friendlier shell and tidier session handling
on top of the terminal engine that already works. I built it on upstream
[termux/termux-app](https://github.com/termux/termux-app) commit `8629e632` (2026-09-26), and a few
dozen commits of my own sit on top of it.

## What I changed so far

I started by turning on Kotlin and Jetpack Compose in the app module, then moved the screen onto
a Compose shell. The terminal keeps the same `TerminalView` that draws it, so the emulation is
untouched; everything around it is new.

Sessions got the most attention. A bar along the top lists them now. It adapts to the screen and
gives each tab a roomier touch target. Tapping a tab switches to that session, and long-pressing
one and dragging it changes the order; the active tab carries a small menu for renaming, closing
and moving it.

The extra keys row changed shape too. I swapped the old swipeable row for a Compose bar and added
presets, with the grid staying alive when it collapses so it no longer flashes. I also fixed keys
staying shaded after a press.

The terminal look is mine now as well. I built an Appearance screen that opens from the terminal's
Style menu and from Settings: colour-scheme presets, an editor for the background, foreground,
cursor and all sixteen ANSI colours, a font picker with a font size, and cursor style and blinking,
all shown in a live preview before you apply it. It replaces the separate Termux:Styling app rather
than asking you to install it.

Fresh installs get a short first-run walkthrough and a font size that suits a new start. You can
point the terminal background at your system wallpaper or a picture of your own.

Smaller fixes came along the way: quick action icons no longer render black, the debug build
workflow can be triggered by hand, and two problems are gone, one where `FileReceiverActivityTest`
needed Conscrypt natives, and one where Compose previews broke the release build. Reordering tabs
brought a pair of bugs of its own, both fixed now: tapping a tab had stopped switching sessions,
and a drag that finished never moved the tab.

## What's next

I have a shortlist now, and a plan written down for the next piece.

Sessions come next. The bar works and its tabs can be reordered by hand now, though they cannot be
pinned yet, which is the easy half. The bigger half is durability: a running shell cannot outlive
the app process, because the terminal lives inside it, so restoring really means remembering a
session snapshot and starting fresh ones on the next launch. I will write that one up separately
before building it.

The shell, the emulator (`:terminal-emulator`) and the view (`:terminal-view`) are still maybes.
