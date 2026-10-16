# Termux

Termux is a terminal emulator and Linux environment for Android. This repository holds the
app itself: the interface you tap and the terminal emulation behind it. The packages you
install inside Termux live in [termux/termux-packages](https://github.com/termux/termux-packages).

This is [GeneralKaos666's fork](https://github.com/GeneralKaos666/termux-app) of the upstream
[termux/termux-app](https://github.com/termux/termux-app), with the interface rebuilt on
Kotlin and Jetpack Compose.

## What this fork adds

- **Sessions bar** across the top: tap a tab to switch, long-press and drag to reorder, and
  rename, close or move any session from its tab's menu.
- **Extra-keys presets** in a Compose row that replaces the old swipeable pager.
- **Appearance screen** under the terminal's Style menu and in Settings: colour schemes, an
  editor for the background, foreground, cursor and all sixteen ANSI colours, font and size,
  and cursor style and blink, all with a live preview. Both cursor settings can be left at
  their Default, which follows `termux.properties`. It replaces the separate Termux:Styling app.
- **Wallpaper backgrounds**: point the terminal at your system wallpaper or an image of your
  own.
- **First-run walkthrough** and roomier touch targets across the shell chrome.

Emulation stays upstream's; changes concentrate in the `:app` module.

## Install

This fork publishes no GitHub releases. Every APK comes from the
[Build workflow](https://github.com/GeneralKaos666/termux-app/actions/workflows/debug_build.yml):

1. Open the workflow, pick the latest successful run on `master`, and download an artifact
   from it. Artifact downloads require you to be logged into a GitHub account.
2. Pick the variant that matches your device: `apt-android-7` for Android 7 and above,
   `apt-android-5` for Android 5 and 6. Inside a variant, grab the `universal` APK, or the
   smaller one for your architecture (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`).
3. Uninstall every Termux app and plugin already on the device before installing. The fork
   keeps the `com.termux` shared user ID, so its APKs must carry the same signature as
   everything else that runs under that ID. F-Droid, Play and upstream GitHub builds use
   different keys, and plugins installed from another source will refuse to work alongside
   this one.
4. Allow your browser or file manager to install unknown apps, then open the APK.

The builds are debug-signed and debuggable. Treat APKs from anywhere other than this
repository's Actions page as untrusted.

## Using it

Run `pkg install <package>` to add tools, `pkg update` to upgrade. The
[package management wiki page](https://wiki.termux.dev/wiki/Package_Management) covers
repositories and fixes for `repository is under maintenance or down` errors. General help
sits on the [Termux wiki](https://wiki.termux.dev/).

## Android 12 and above

Android kills background and CPU-hungry processes aggressively, so a shell can die with
`[Process completed (signal 9) - press Enter]` without you exiting it. Upstream tracks the
problem and its workarounds in
[termux-app#2366](https://github.com/termux/termux-app/issues/2366).

## What's coming

The fork keeps moving. These are the pieces in progress or next up.

- **Appearance verification**: the three newer colour presets (Solarized Light, Gruvbox Dark, One
  Dark) still need a look on real devices. There is a short checklist in `docs/ideas/ideas.md`.
- **Settings rework and a setup wizard.** The first-run walkthrough shipped; the preferences
  themselves still need reorganising.
- **Terminal engine upgrade** (exploratory): kitty graphics, OSC 8 hyperlinks, richer underline
  styles, ligatures and grapheme shaping, and a renderer that redraws only what changed. The
  approach is still open; the first step is a new cell model.

## License and security

Code is [GPLv3 only](LICENSE.md), with Apache 2.0 exceptions for the terminal emulator and
view libraries. Report vulnerabilities per [termux.dev/security](https://termux.dev/security);
see also [SECURITY.md](SECURITY.md).
