# Acrylic terminal background — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Frost the terminal background: blur the wallpaper/custom-image picture behind the terminal, add a picture-opacity control, and replace the black live-wallpaper fallback with a sampled colour.

**Architecture:** Two additive preference pairs in `:termux-shared`; a pure `BackgroundFx` helper for the downscale fallback and fallback-colour choice; blur + opacity applied in `ShellHost.drawBehind`; the wallpaper loader samples a fallback colour on live wallpapers.

**Tech Stack:** Kotlin + Compose (`:app`), Java (`:termux-shared` preferences), `RenderEffect` (API 31+) with a load-time downscale fallback.

**Spec:** `docs/specs/2026-11-14-acrylic-background-design.md`

## Global Constraints

- Toolchain floors: `minSdkVersion=23`, `targetSdkVersion=28`, `compileSdkVersion=37` (`gradle.properties`).
- `RenderEffect` is API 31+; below that the load-time downscale fallback is used. `WallpaperManager.getWallpaperColors()` is API 27+.
- `:termux-shared` is a published library — additions are strictly additive (new keys/defaults and getter/setter pairs; no existing key, constant or method changes behaviour).
- Native graphics (`Bitmap`/`RenderEffect`/`WallpaperManager`) is unsupported on this aarch64 host; only `BackgroundFx` is unit-tested, the rest is device-verified.
- Commits: Conventional Commits, capitalized type + present-tense description; every commit GPG-signed (`git commit -S`).

## Review Focus

1. **Defaults are byte-identical** — `blurRadius == 0` and `opacity == 100` must reproduce today's rendering. Test: `BackgroundFxTest` (Task 2) + D-16.
2. **API < 31 blur fallback** — no `RenderEffect`, so the downscale factor must be a valid divisor. Test: `BackgroundFxTest` (Task 2).
3. **Live wallpaper not black** — the fallback colour must be the wallpaper primary when available, else the terminal background. Test: `BackgroundFxTest` (Task 2).
4. **Solid mode inert** — with no picture, blur/opacity must do nothing and the settings are hidden/disabled. Device check D-16.
5. **Opacity clamped** — `pictureOpacity` coerced to `[0.1, 1.0]` and the preference to `[10, 100]`. Test: `BackgroundFxTest` (Task 2) + `TerminalBackgroundState.update` coercion.

---

## File Structure

- Modify: `termux-shared/src/main/java/com/termux/shared/termux/settings/preferences/TermuxPreferenceConstants.java` — two keys + defaults in `TERMUX_APP`.
- Modify: `termux-shared/src/main/java/com/termux/shared/termux/settings/preferences/TermuxAppSharedPreferences.java` — two getter/setter pairs.
- Create: `app/src/main/java/com/termux/app/ui/background/BackgroundFx.kt` — pure helper.
- Create: `app/src/test/java/com/termux/app/ui/background/BackgroundFxTest.java` (or `.kt`).
- Modify: `app/src/main/java/com/termux/app/ui/background/TerminalBackgroundState.kt` — `blurRadius`/`pictureOpacity`.
- Modify: `app/src/main/java/com/termux/app/ui/ShellHost.kt` — blur + opacity in `drawBehind`.
- Modify: `app/src/main/java/com/termux/app/TermuxActivity.java` — wallpaper fallback + `syncTerminalBackground` wiring.
- Modify: `app/src/main/java/com/termux/app/fragments/settings/termux/DisplayPreferencesFragment.java` + string resources — two settings.
- Modify: `docs/ledger/outstanding-work.md`, `docs/ledger/manual-checks.md` — feature + D-16.

---

### Task 1: Preference keys and accessors

**Files:**
- Modify: `termux-shared/src/main/java/com/termux/shared/termux/settings/preferences/TermuxPreferenceConstants.java`
- Modify: `termux-shared/src/main/java/com/termux/shared/termux/settings/preferences/TermuxAppSharedPreferences.java`

**Interfaces:**
- Produces (constants, in `TERMUX_APP`):
  - `String KEY_TERMINAL_BACKGROUND_BLUR = "terminal_background_blur"`, `int DEFAULT_TERMINAL_BACKGROUND_BLUR = 0`
  - `String KEY_TERMINAL_BACKGROUND_OPACITY = "terminal_background_opacity"`, `int DEFAULT_TERMINAL_BACKGROUND_OPACITY = 100`
- Produces (methods, in `TermuxAppSharedPreferences`, next to `getTerminalBackgroundDim`):
  - `public int getTerminalBackgroundBlur()`, `public void setTerminalBackgroundBlur(int value)`
  - `public int getTerminalBackgroundOpacity()`, `public void setTerminalBackgroundOpacity(int value)`

- [ ] **Step 1: Add the constants**

Add the four constants to `TermuxPreferenceConstants.TERMUX_APP`, grouped with the existing
`KEY_TERMINAL_BACKGROUND_DIM`/`DEFAULT_TERMINAL_BACKGROUND_DIM`.

- [ ] **Step 2: Add the accessors**

Add the four methods to `TermuxAppSharedPreferences`, using `SharedPreferenceUtils.getInt`/`setInt`
with the new key/default constants, matching the surrounding style. No `androidx.annotation`
requirement beyond what neighbours use.

- [ ] **Step 3: Build and full test**

Run: `./gradlew :termux-shared:assembleDebug`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew test`
Expected: PASS (existing suite green).

- [ ] **Step 4: Commit**

```bash
git add termux-shared/src/main/java/com/termux/shared/termux/settings/preferences/TermuxPreferenceConstants.java termux-shared/src/main/java/com/termux/shared/termux/settings/preferences/TermuxAppSharedPreferences.java
git commit -S -m "Added(termux-shared): Add terminal background blur and opacity preferences"
```

---

### Task 2: `BackgroundFx` pure helper

**Files:**
- Create: `app/src/main/java/com/termux/app/ui/background/BackgroundFx.kt`
- Test: `app/src/test/java/com/termux/app/ui/background/BackgroundFxTest.java`

**Interfaces:**
- Produces (Kotlin `object BackgroundFx`, no `android.*` imports):
  - `fun blurDownscale(radiusPx: Float): Float` — the load-time downscale divisor for the API<31 blur, `(1f + radiusPx / 8f).coerceIn(1f, 8f)`.
  - `fun wallpaperFallbackColor(colorsAvailable: Boolean, primaryColor: Int, terminalBackgroundColor: Int): Int` — `primaryColor` if available, else `terminalBackgroundColor`.
  - `fun clampOpacity(percent: Int): Float` — `(percent.coerceIn(10, 100)) / 100f`.

- [ ] **Step 1: Write the failing test** (`BackgroundFxTest`, plain JUnit, package `com.termux.app.ui.background`)

```java
public class BackgroundFxTest {
    @Test public void blurDownscaleIsOneAtZeroRadius() {
        assertEquals(1f, BackgroundFx.INSTANCE.blurDownscale(0f), 0.001f);
    }
    @Test public void blurDownscaleGrowsWithRadiusAndClamps() {
        assertEquals(1f + 25f / 8f, BackgroundFx.INSTANCE.blurDownscale(25f), 0.001f);
        assertEquals(8f, BackgroundFx.INSTANCE.blurDownscale(1000f), 0.001f);
    }
    @Test public void wallpaperFallbackColorPrefersPrimary() {
        assertEquals(0xFF123456, BackgroundFx.INSTANCE.wallpaperFallbackColor(true, 0xFF123456, 0xFF000000));
        assertEquals(0xFF000000, BackgroundFx.INSTANCE.wallpaperFallbackColor(false, 0xFF123456, 0xFF000000));
    }
    @Test public void clampOpacityCoercesRange() {
        assertEquals(0.1f, BackgroundFx.INSTANCE.clampOpacity(5), 0.001f);
        assertEquals(0.6f, BackgroundFx.INSTANCE.clampOpacity(60), 0.001f);
        assertEquals(1f, BackgroundFx.INSTANCE.clampOpacity(150), 0.001f);
    }
}
```

(If `BackgroundFx` is written as a Kotlin `object`, reference `BackgroundFx.INSTANCE` from Java; if it
is written as a `class` with `@JvmStatic` members, call `BackgroundFx.blurDownscale(...)` directly —
match whichever the implementer writes.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.ui.background.BackgroundFxTest"`
Expected: FAIL — `BackgroundFx` does not exist.

- [ ] **Step 3: Implement `BackgroundFx`**

Write the `object BackgroundFx` with the three functions above, no `android.*` imports. Javadoc mirrors
the spec §3.2/§3.4.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.ui.background.BackgroundFxTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/termux/app/ui/background/BackgroundFx.kt app/src/test/java/com/termux/app/ui/background/BackgroundFxTest.java
git commit -S -m "Added(app): Add BackgroundFx blur and fallback math"
```

---

### Task 3: State and blur/opacity drawing

**Files:**
- Modify: `app/src/main/java/com/termux/app/ui/background/TerminalBackgroundState.kt`
- Modify: `app/src/main/java/com/termux/app/ui/ShellHost.kt`

**Interfaces:**
- Consumes: `BackgroundFx.clampOpacity`; the new preferences from Task 1.
- Produces:
  - `TerminalBackgroundState.update(bitmap: Bitmap?, dimPercent: Int, blurRadius: Float, pictureOpacity: Float)` (the old 2-arg `update` delegates with `0f`/`1f`).

- [ ] **Step 1: Extend `TerminalBackgroundState`**

Add `var blurRadius by mutableStateOf(0f); private set` and `var pictureOpacity by mutableStateOf(1f); private set`. Add the 4-argument `update(...)` that coerces `dimPercent` to `[0, 90]`, `pictureOpacity` to `[0.1f, 1f]`, and `blurRadius` to `>= 0f`. Keep the existing 2-argument `update` delegating with `0f`/`1f` so current callers are unchanged.

- [ ] **Step 2: Apply blur and opacity in `ShellHost.drawBehind`**

In the `drawBehind` block (lines 173–194): draw the picture with `alpha = terminalBackgroundState.pictureOpacity`; when `blurRadius > 0`, on API 31+ wrap the draw in a `Paint` carrying
`RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP)` (via
`drawIntoCanvas { it.drawImageRect(...) }` or a blurred `Paint` on the image draw), else (API < 31) use
the load-time downscale already applied in Task 4 via `BackgroundFx.blurDownscale`. Keep the black dim
`drawRect` unchanged and drawn after the picture.

- [ ] **Step 3: Build and full test**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (device rendering is D-16; no host pixel check possible).

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/termux/app/ui/background/TerminalBackgroundState.kt app/src/main/java/com/termux/app/ui/ShellHost.kt
git commit -S -m "Added(app): Apply blur and opacity to the terminal background"
```

---

### Task 4: Wallpaper fallback and wiring

**Files:**
- Modify: `app/src/main/java/com/termux/app/TermuxActivity.java`

**Interfaces:**
- Consumes: `BackgroundFx.blurDownscale`, `BackgroundFx.wallpaperFallbackColor`; the new preferences.

- [ ] **Step 1: Fallback colour in `loadSystemWallpaperBitmap`**

When `WallpaperManager.getDrawable()` returns null/empty (the live-wallpaper case), return a 1×1 solid
`Bitmap` coloured with `BackgroundFx.wallpaperFallbackColor(...)`: the wallpaper primary from
`WallpaperManager.getWallpaperColors()` on API 27+, else the terminal background colour.

- [ ] **Step 2: Downscale the loaded picture for the API<31 blur**

When `mPreferences.getTerminalBackgroundBlur() > 0`, downscale the returned bitmap by
`BackgroundFx.blurDownscale(blurRadius)` before it reaches the state (the API<31 blur fallback); the
up-scale with `Paint.FILTER_BITMAP_FLAG` happens in `ShellHost`'s draw, which already centre-crops.

- [ ] **Step 3: Pass the new values in `syncTerminalBackground`**

Read `getTerminalBackgroundBlur()` and `getTerminalBackgroundOpacity()` and pass them (with the
existing `dim`) to `TerminalBackgroundState.update(...)`.

- [ ] **Step 4: Build and full test**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/termux/app/TermuxActivity.java
git commit -S -m "Added(app): Add live-wallpaper fallback colour and blur/opacity wiring"
```

---

### Task 5: Settings surface

**Files:**
- Modify: `app/src/main/java/com/termux/app/fragments/settings/termux/DisplayPreferencesFragment.java`
- Modify: `app/src/main/res/xml/*.xml` and `app/src/main/res/values/strings.xml` — two entries.

**Interfaces:**
- Consumes: the new preference accessors from Task 1.

- [ ] **Step 1: Add the two settings rows**

Add `terminal_background_blur` (0–25, integer) and `terminal_background_opacity` (10–100, integer)
rows to the Display/Background preference screen, next to the existing `terminal_background_dim`,
wired to the Task 1 accessors and hidden or disabled when the background mode is solid. Add their
string resources.

- [ ] **Step 2: Build and full test**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/termux/app/fragments/settings/termux/DisplayPreferencesFragment.java app/src/main/res
git commit -S -m "Added(app): Add background blur and opacity settings"
```

---

### Task 6: Ledger and device check

**Files:**
- Modify: `docs/ledger/outstanding-work.md` — update §1.4 and add the feature + D-16.
- Modify: `docs/ledger/manual-checks.md` — D-16.

- [ ] **Step 1: Update §1.4 and add the ledger entry**

In `outstanding-work.md` §1.4, narrow the live-wallpaper limitation from "black" to "flat sampled
colour; no live animation", and add a line recording the blur/opacity feature with its D-16 check.

- [ ] **Step 2: Add D-16 to `manual-checks.md`**

Add a D-16 section: blur radius 0 vs a mid value visibly frosts the wallpaper; opacity lightens the
picture independently of dim; a live wallpaper no longer shows black; the two settings persist across
restart; solid mode hides the controls and stays unchanged.

- [ ] **Step 3: Commit**

```bash
git add docs/ledger/outstanding-work.md docs/ledger/manual-checks.md
git commit -S -m "Docs(ledger): Record acrylic background feature and D-16 device check"
```
