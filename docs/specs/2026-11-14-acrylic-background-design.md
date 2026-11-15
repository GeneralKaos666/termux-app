# Acrylic terminal background — design

**Date:** 2026-11-14
**Scope:** a frosted-glass background for the terminal: blur the wallpaper/custom-image background
behind the terminal, add a picture-opacity control, and replace the black live-wallpaper fallback with
a sampled colour. Changes span `:app` Compose (`ui/background`, `ui/ShellHost.kt`) and
`TermuxActivity`'s wallpaper loader.
**Status:** design approved in chat 2026-11-14; not yet implemented.
**Ledger item:** none yet — new feature; a device check (D-16) is added to `manual-checks.md` on
implementation. Relates to `outstanding-work.md` §1.4 (the live-wallpaper limitation).

## 1. Why

The terminal already shows the wallpaper/custom image behind it: `ShellHost.kt`'s `drawBehind`
(§3, lines 173–194) centre-crops the picture and draws a black dim overlay, and the `TerminalView`
is painted transparent so the picture shows through the terminal's default background. But:

- The picture is **sharp**, not frosted — a busy wallpaper competes with terminal text.
- The only readability control is a **black dim** overlay (`TerminalBackgroundState.dimPercent`,
  0–90, default 60); there is no picture-opacity control.
- A **live wallpaper** yields no bitmap from `WallpaperManager.getDrawable()`
  (`TermuxActivity.loadSystemWallpaperBitmap`, §1.4), so the background degrades to black.

The goal is the acrylic look: a blurred, optionally lightened background behind the terminal, with a
graceful fallback on live wallpapers.

## 2. Goals and non-goals

Goals:

- **Background blur** — a "blur radius" setting (0–25 px) applied to the wallpaper/custom-image
  picture. 0 keeps today's sharp picture (backward compatible).
- **Picture opacity** — a separate "picture opacity" setting (10–100 %) so the picture can be
  lightened without a black cast, alongside the existing dim (dim stays a readability overlay).
- **Live-wallpaper fallback** — when no bitmap is available, sample a flat colour from
  `WallpaperManager.getWallpaperColors()` (API 27+) instead of black; document the live-animation
  capture as a platform limitation that remains.

Non-goals:

- **No per-cell ANSI transparency.** Making coloured cell backgrounds or glyphs translucent would
  hurt readability and would touch `TerminalRenderer`; the frosted effect is on the picture behind
  the default background only.
- **No live-wallpaper animation capture.** Android exposes no public bitmap of a running live
  wallpaper to another app; the fallback is a flat sampled colour, and that limitation is documented,
  not "fixed".
- **No change to `:terminal-view` or `:terminal-emulator`.** The only `:termux-shared` touch is
  additive: two new preference keys in `TermuxPreferenceConstants.TERMUX_APP` and two getter/setter
  pairs in `TermuxAppSharedPreferences` (the terminal background preferences already live there, e.g.
  `KEY_TERMINAL_BACKGROUND_DIM`). No existing key, constant or method changes behaviour.
- **No new dependency.**

## 3. Design

### 3.1 State — `TerminalBackgroundState`

Extend the existing Compose-observable state with two fields next to `dimPercent`:

```kotlin
class TerminalBackgroundState {
    var bitmap by mutableStateOf<Bitmap?>(null); private set
    var dimPercent by mutableStateOf(60); private set          // existing black overlay, 0-90
    var blurRadius by mutableStateOf(0f); private set           // px, 0 = sharp
    var pictureOpacity by mutableStateOf(1f); private set       // 0.1-1.0
    fun update(bitmap: Bitmap?, dimPercent: Int, blurRadius: Float, pictureOpacity: Float) { ... }
}
```

The `update` overload grows; callers pass the two new values from preferences.

### 3.2 Blur application — `ShellHost.drawBehind`

The existing `drawImage` gains a blur. On API 31+ this is a `RenderEffect` on a per-draw `Paint`
(the image is drawn through `drawIntoCanvas` with a blurred paint); below API 31 there is no
`RenderEffect`, so the fallback is a **cheap pre-blur at load time**: the bitmap is downscaled by the
blur factor and drawn back up with `Paint.FILTER_BITMAP_FLAG`, which reads as a soft blur on every
API level.

- `blurRadius == 0` → no blur, no extra paint, identical to today.
- The blur runs on the already-downsampled background bitmap, so cost is one blur per draw, gated to
  wallpaper/image mode only.

### 3.3 Opacity — `ShellHost.drawBehind`

The picture is drawn with `alpha = pictureOpacity`, and the black dim overlay remains a separate
`drawRect(Color.Black.copy(alpha = dimPercent / 100f))` on top. The two are independent: opacity
lightens the picture, dim darkens it for text contrast.

### 3.4 Live-wallpaper fallback — `TermuxActivity.loadSystemWallpaperBitmap`

When `WallpaperManager.getDrawable()` returns a null/empty drawable (the live-wallpaper case), fall
back to a 1×1 solid `Bitmap` coloured with the wallpaper's primary colour from
`WallpaperManager.getWallpaperColors()` (API 27+), falling back to the terminal background colour on
older APIs. This replaces today's black with a colour that matches the live wallpaper's dominant
tone. The live animation itself is still not captured.

### 3.5 Settings surface

The existing Display/Background preferences gain two entries next to `terminal_background_dim`:
`terminal_background_blur` (0–25) and `terminal_background_opacity` (10–100). They persist through
`TermuxAppSharedPreferences` (getters/setters added next to `getTerminalBackgroundDim`/
`setTerminalBackgroundDim`; keys/defaults added to `TermuxPreferenceConstants.TERMUX_APP`), are read
in `syncTerminalBackground` and passed through to `TerminalBackgroundState.update`.

## 4. Data flow

```
preferences ── blur / opacity / dim ──► syncTerminalBackground ──► TerminalBackgroundState.update
                                                                           │
WallpaperManager.getDrawable() ──► loadSystemWallpaperBitmap ──► bitmap    ▼
        └─ null (live) ──► getWallpaperColors() ──► 1×1 colour bitmap  ShellHost.drawBehind
                                                                           │  drawImage(blur, opacity)
                                                                           ▼  drawRect(dim)
```

## 5. Testing

- **Pure helper** (JVM, in `:app:testDebugUnitTest`): the downscale factor for the API<31 fallback
  blur, and the fallback-colour selection (choose `getWallpaperColors()` primary when available,
  else the terminal background). These are android-free and unit-tested.
- **Not unit-testable on this host** (native graphics unsupported on aarch64, same as the existing
  sixel/`TerminalBitmap` limitation): the actual `RenderEffect` blur, the `Bitmap` downscale/upscale,
  and the `getWallpaperColors()`/`getDrawable()` calls. Verified on device.
- **Device check D-16** (added to `manual-checks.md`, run in the device phase): blur radius 0 vs a
  mid value visibly frosts the wallpaper behind the terminal; opacity lightens the picture
  independently of dim; a live wallpaper no longer shows black behind the terminal; and the two new
  settings persist across restart.

## 6. Edge cases

- `blurRadius == 0` and `pictureOpacity == 1.0` → byte-for-byte today's behaviour.
- API < 31 → no `RenderEffect`; the load-time downscale fallback is used and is visually close.
- Live wallpaper on API < 27 → no `getWallpaperColors()`, so the terminal background colour is used
  (no worse than the current black).
- Solid (non-wallpaper/image) mode → no picture is drawn, so blur/opacity are inert and the two
  settings are hidden or disabled in the settings screen.

## 7. Verification

1. `./gradlew :app:testDebugUnitTest` green (the new pure-helper test).
2. Full `./gradlew test` green.
3. `./gradlew :app:assembleDebug` green.
4. Device check D-16 in the device phase (kept last, per the work order).

## 8. Documentation and rollback

- `outstanding-work.md` §1.4 is updated: the live-wallpaper limitation narrows from "black" to
  "flat sampled colour; no live animation", and the new blur/opacity feature is recorded with its
  D-16 check.
- `manual-checks.md` gains D-16.
- Blur, opacity and the live-wallpaper fallback are three independent pieces; each is a small change
  that can be reverted without the others.
