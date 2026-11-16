# Motion craft fixes — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the craft and frame-timing defects in the shipped 2026-11-14 motion work: float enters from `scale(0)`, chrome bars use a bouncy spring on size/fade, the cursor loop is a fixed 16 ms timer with per-frame easing, and the cursor ignores "remove animations".

**Architecture:** Compose side — remap the float enter scale, swap the chrome `AnimatedVisibility` specs to tweens, tween the `animateItem` fades, and make `shellAnimationsEnabled()` reactive to the setting. Cursor side — make `CursorFx.easeTowards` time-based, drive `TerminalCursorBlinkerRunnable` from `Choreographer` frame callbacks, and gate blink/ease on the animator scale.

**Tech Stack:** Kotlin + Jetpack Compose (foundation 1.12.1, compose-bom 2026.09.00) in `:app`; Java + plain JUnit in `:terminal-view`.

**Spec:** `docs/specs/2026-11-15-motion-craft-design.md`

## Global Constraints

- Compose changes stay in `:app` (`app/src/main/java/com/termux/app/ui/`); cursor changes stay in `:terminal-view` (`com.termux.view`). No `:terminal-emulator`/`:termux-shared` change.
- Every Compose spec stays gated on `shellAnimationsEnabled()`; with it false every transition is instant. Do not regress this.
- `TerminalRenderer`'s cursor rendering (alpha, glow, eased rect) and the existing `render(...)` overloads are unchanged. Only the loop that feeds them changes.
- `CursorFx` imports no `android.*` types (pure float math, like `RenderBand`).
- `:terminal-view` has no Robolectric; the loop wiring is covered by device check D-14, the math by `CursorFxTest`.
- No new dependency.
- Commits: Conventional Commits with a capitalized type (`Added`, `Changed`, `Fixed`, …) and present-tense description; every commit GPG-signed (`git commit -S`).
- **Confirm the §9 reversals in the spec before Task 5** (Choreographer; tween chrome; static cursor).

## Review Focus

1. **Float first frame** — the bubble/window must be visible-but-small on its first frame, never a zero-size point. Pinned by D-15 (Task 1).
2. **Animations off, mid-run** — toggling "remove animations" while the app runs must flip every Compose spec and the cursor gate without a restart. Pinned by D-15/D-14 (Tasks 3, 5).
3. **First-frame `dtMs == 0`** — the cursor must not jump or stall on the first Choreographer frame. Test: `easeTowardsHoldsStillWithoutTime` (Task 4).
4. **In-flight frame at stop** — a frame already executing when the blinker is stopped must neither draw nor re-post. Pinned by D-14 (Task 5).
5. **Blink cadence** — with animations on, the full on/off cycle stays `2 * mBlinkRate`. Pinned by D-14 (Task 5).

---

## File Structure

- Modify: `app/src/main/java/com/termux/app/ui/theme/Motion.kt` — float enter scale remap.
- Modify: `app/src/main/java/com/termux/app/ui/ShellHost.kt` — tween specs on the two `AnimatedVisibility` blocks.
- Modify: `app/src/main/java/com/termux/app/ui/sessions/SessionsBar.kt` — tween item fades; pass `animationsEnabled` into `SessionTab`.
- Modify: `app/src/main/java/com/termux/app/ui/theme/Theme.kt` — reactive `shellAnimationsEnabled()`.
- Modify: `terminal-view/src/main/java/com/termux/view/CursorFx.java` — time-based `easeTowards`.
- Modify: `terminal-view/src/test/java/com/termux/view/CursorFxTest.java` — tests for the new signature.
- Modify: `terminal-view/src/main/java/com/termux/view/TerminalView.java` — Choreographer loop, time-based easing, animator-scale gate.
- Modify: `docs/ledger/outstanding-work.md`, `docs/ledger/manual-checks.md` — amended entries and D-14/D-15.

---

### Task 1: Float enter starts near full size

**Files:**
- Modify: `app/src/main/java/com/termux/app/ui/theme/Motion.kt`

**Interfaces:**
- Produces: `floatEnterScaleAndAlpha(): Pair<Float, Float>` (unchanged signature) — scale now in `[FloatEnterMinScale, 1]`, alpha the clamped progress. `FloatingWindow.kt`/`FloatingBubble.kt` already consume it.

- [x] **Step 1: Remap the scale**

Replace the body of `floatEnterScaleAndAlpha()` so the scale is `FloatEnterMinScale + (1f - FloatEnterMinScale) * progress` and the alpha stays `progress.coerceIn(0f, 1f)`. Add `private const val FloatEnterMinScale = 0.92f`. Update the KDoc: the scale starts at 92 %, so the overlay grows into place instead of appearing from a point; the spring overshoot still carries it past 100 %.

- [x] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [x] **Step 3: Commit**

```bash
git add app/src/main/java/com/termux/app/ui/theme/Motion.kt
git commit -S -m "Fixed(app): Grow the float overlay from 92% instead of a point"
```

---

### Task 2: Tween the chrome transitions

**Files:**
- Modify: `app/src/main/java/com/termux/app/ui/ShellHost.kt`
- Modify: `app/src/main/java/com/termux/app/ui/sessions/SessionsBar.kt`

**Interfaces:**
- Consumes: `shellSpringSpec()` (still used for the float pop and tab placement), `shellAnimationsEnabled()`.
- Produces: no new public API.

- [x] **Step 1: Swap the chrome specs in `ShellHost.kt`**

Add file-private specs (from `androidx.compose.animation.core`):

```kotlin
private val ChromeSizeIn = tween<IntSize>(durationMillis = 220, easing = FastOutSlowInEasing)
private val ChromeSizeOut = tween<IntSize>(durationMillis = 180, easing = FastOutSlowInEasing)
private val ChromeFadeIn = tween<Float>(durationMillis = 150)
private val ChromeFadeOut = tween<Float>(durationMillis = 120)
```

Replace both `AnimatedVisibility` blocks (the sessions bar and the extra-keys bar) so `enter = if (animationsEnabled) expandVertically(ChromeSizeIn) + fadeIn(ChromeFadeIn) else EnterTransition.None` and `exit = if (animationsEnabled) shrinkVertically(ChromeSizeOut) + fadeOut(ChromeFadeOut) else ExitTransition.None`. Add a comment: the size stays a layout animation because collapsing a bar reflows the terminal; the tween (not the spring) avoids a bar-height overshoot and a clamped alpha overshoot.

- [x] **Step 2: Tween the item fades in `SessionsBar.kt`**

Change the `Modifier.animateItem(...)` call (currently `fadeInSpec`/`placementSpec`/`fadeOutSpec` all `shellSpringSpec()`) so `fadeInSpec = tween(durationMillis = 150)`, `placementSpec = shellSpringSpec()`, `fadeOutSpec = tween(durationMillis = 120)`.

- [x] **Step 3: Pass `animationsEnabled` into `SessionTab`**

`SessionsBar` already computes `val animationsEnabled = shellAnimationsEnabled()`. Add an `animationsEnabled: Boolean` parameter to `SessionTab`, pass it at the call site, and delete the `val animationsEnabled = shellAnimationsEnabled()` line inside `SessionTab` so the tab does not register its own settings observer (Task 3 makes the helper reactive).

- [x] **Step 4: Compile and full test**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew test`
Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/com/termux/app/ui/ShellHost.kt app/src/main/java/com/termux/app/ui/sessions/SessionsBar.kt
git commit -S -m "Changed(app): Use tweens for chrome transitions and tab fades"
```

---

### Task 3: Reactive `shellAnimationsEnabled()`

**Files:**
- Modify: `app/src/main/java/com/termux/app/ui/theme/Theme.kt`

**Interfaces:**
- Produces: `shellAnimationsEnabled(): Boolean` (same signature, now recomposes when the setting changes).

- [x] **Step 1: Observe the setting**

Rewrite `shellAnimationsEnabled()` per spec §3.4: a `remember { mutableStateOf(animatorScaleEnabled(context)) }` plus a `DisposableEffect(context)` that registers a `ContentObserver` on `Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)` and unregisters it in `onDispose`. Add a private `animatorScaleEnabled(context: Context): Boolean` holding the existing `Settings.Global.getFloat(...) > 0f` read. New imports: `android.database.ContentObserver`, `android.os.Handler`, `android.os.Looper`, `androidx.compose.runtime.DisposableEffect`, `getValue`, `setValue`, `mutableStateOf`.

- [x] **Step 2: Compile and full test**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew test`
Expected: PASS.

- [x] **Step 3: Commit**

```bash
git add app/src/main/java/com/termux/app/ui/theme/Theme.kt
git commit -S -m "Changed(app): Follow the animator scale setting while running"
```

---

### Task 4: Time-based cursor easing

**Files:**
- Modify: `terminal-view/src/main/java/com/termux/view/CursorFx.java`
- Test: `terminal-view/src/test/java/com/termux/view/CursorFxTest.java`

**Interfaces:**
- Produces:
  - `static float CursorFx.easeTowards(float current, float target, float dtMs, float halfLifeMs)` — the remaining distance halves every `halfLifeMs`; `dtMs <= 0` returns `current`, `halfLifeMs <= 0` returns `target`.
  - `static float CursorFx.blinkAlpha(long elapsedMs, long periodMs)` — unchanged.

- [x] **Step 1: Replace the easing tests**

In `CursorFxTest`, delete `easeTowardsMovesProportionally`, `easeTowardsClampsFactor`, and `easeTowardsConvergesFromAbove`, and add:

```java
@Test public void easeTowardsHoldsStillWithoutTime() {
    assertEquals(3f, CursorFx.easeTowards(3f, 10f, 0f, 26f), 0.0001f);
}
@Test public void easeTowardsSnapsWithZeroHalfLife() {
    assertEquals(10f, CursorFx.easeTowards(0f, 10f, 16f, 0f), 0.0001f);
}
@Test public void easeTowardsHalvesDistanceOverOneHalfLife() {
    assertEquals(5f, CursorFx.easeTowards(0f, 10f, 26f, 26f), 0.0001f);
}
@Test public void easeTowardsCoversThreeQuartersOverTwoHalfLives() {
    assertEquals(7.5f, CursorFx.easeTowards(0f, 10f, 52f, 26f), 0.0001f);
}
@Test public void easeTowardsIsMonotonicInTime() {
    assertTrue(CursorFx.easeTowards(0f, 10f, 40f, 26f) > CursorFx.easeTowards(0f, 10f, 16f, 26f));
}
@Test public void easeTowardsNeverOvershoots() {
    assertEquals(10f, CursorFx.easeTowards(0f, 10f, 100000f, 26f), 0.01f);
}
@Test public void easeTowardsConvergesFromAbove() {
    assertEquals(5f, CursorFx.easeTowards(10f, 0f, 26f, 26f), 0.0001f);
}
```

- [x] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :terminal-view:testDebugUnitTest --tests "com.termux.view.CursorFxTest"`
Expected: compilation FAIL — the 3-argument `easeTowards` no longer has a 4-argument overload.

- [x] **Step 3: Implement the new `easeTowards`**

Replace the method in `CursorFx.java` with the spec §4.1 body: hold on `dtMs <= 0`, snap on `halfLifeMs <= 0`, else `factor = 1f - (float) Math.pow(0.5, dtMs / halfLifeMs)` and return `current + (target - current) * factor`. Update the Javadoc to the half-life meaning.

- [x] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :terminal-view:testDebugUnitTest --tests "com.termux.view.CursorFxTest"`
Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add terminal-view/src/main/java/com/termux/view/CursorFx.java terminal-view/src/test/java/com/termux/view/CursorFxTest.java
git commit -S -m "Changed(terminal): Make cursor easing time-based"
```

---

### Task 5: Choreographer loop and animator-scale gate

**Files:**
- Modify: `terminal-view/src/main/java/com/termux/view/TerminalView.java`

**Interfaces:**
- Consumes: `CursorFx.easeTowards(float, float, float, float)`, `CursorFx.blinkAlpha(long, long)`.
- Produces: no new public API; the loop and fields stay private to `TerminalView`.

- [x] **Step 1: Replace the constants and the handler**

Remove `CURSOR_ANIMATION_INTERVAL_MS` and `CURSOR_EASING_FACTOR`; add `private static final float CURSOR_EASING_HALF_LIFE_MS = 26f;`. Replace the `Handler mTerminalCursorBlinkerHandler` field with `private Choreographer mChoreographer;`. Add `import android.provider.Settings;` and `import android.view.Choreographer;`.

- [x] **Step 2: Gate on the animator scale**

Add `private boolean animationsEnabled()` returning `Settings.Global.getFloat(getContext().getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f`. In `setTerminalCursorBlinkerState(boolean start, boolean startOnlyIfCursorEnabled)`, when `start` is true but `!animationsEnabled()`, skip setting `mCursorBlinkRunning` and do not start the loop (the cursor stays steady and fully opaque). In `updateCursorAnimationBeforeRender()`, do not set `mCursorEasing` when `!animationsEnabled()` (a bar/underline cursor snaps).

- [x] **Step 3: Move the loop to frame callbacks**

Change `TerminalCursorBlinkerRunnable` to `implements Choreographer.FrameCallback`, keeping its fields and its `setEmulator`/`cancel`/`isCancelled` methods. Replace `run()` with `doFrame(long frameTimeNanos)`: compute `dtMs` from a new `long mLastFrameNanos` (0 on the first frame), set `mCursorAlpha` from `CursorFx.blinkAlpha(SystemClock.uptimeMillis() - mBlinkStartUptimeMs, 2L * mBlinkRate)` while `mCursorBlinkRunning`, step `mDisplayCursorRow`/`mDisplayCursorCol` with `CursorFx.easeTowards(display, target, dtMs, CURSOR_EASING_HALF_LIFE_MS)` while `mCursorEasing` (same `0.05f` snap epsilon), invalidate the cursor union as today, and in a `finally` re-post with `mChoreographer.postFrameCallback(this)` only while `!mCancelled && (mCursorBlinkRunning || mCursorEasing)`.

- [x] **Step 4: Point the start/stop helpers at the Choreographer**

`startCursorAnimationFrames()` obtains `mChoreographer` from `Choreographer.getInstance()` and posts the callback (replacing `mTerminalCursorBlinkerHandler.post(...)`); the cancel-and-replace logic for a stale runnable stays. `stopTerminalCursorBlinker()` calls `mChoreographer.removeFrameCallback(mTerminalCursorBlinkerRunnable)` (replacing `removeCallbacks`) and keeps its existing cancellation flag.

- [x] **Step 5: Build and full test**

Run: `./gradlew test`
Expected: PASS (existing suite stays green).

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL (proves `:app` still compiles against `:terminal-view`).

- [x] **Step 6: Commit**

```bash
git add terminal-view/src/main/java/com/termux/view/TerminalView.java
git commit -S -m "Changed(terminal): Drive the cursor on frame callbacks and honor remove-animations"
```

---

### Task 6: Ledger and device checks

**Files:**
- Modify: `docs/ledger/outstanding-work.md` — amend the two `[shipped 2026-11-14]` entries in §1.4.
- Modify: `docs/ledger/manual-checks.md` — update D-14 and D-15.

- [x] **Step 1: Amend the ledger entries**

Under §1.4, append to the cursor-polish entry: `[corrected 2026-11-15]` the blink/ease loop now runs on
`Choreographer` frame callbacks with time-based easing, and the cursor is static when the system
animator scale is 0. Append to the shell UI motion entry: `[corrected 2026-11-15]` the chrome bars
expand/collapse on a tween (no overshoot), the float overlay enters from 92 %, and
`shellAnimationsEnabled()` follows the setting while the app runs.

- [x] **Step 2: Update D-14**

In `manual-checks.md` §15 (D-14), add: the cursor glide speed is the same on a 120 Hz panel as on
60 Hz (time-based easing); with "remove animations" on the cursor is steady and fully opaque and a
bar/underline cursor snaps rather than gliding; toggling "remove animations" while a session is open
takes effect without restarting the app.

- [x] **Step 3: Update D-15**

In `manual-checks.md` §16 (D-15), change the two bar checks to expect a tween (no overshoot) and the
float-enter check to expect the bubble/window to grow from ~92 % rather than from a point; add:
toggling "remove animations" while the app runs makes the transitions instant without a restart.

- [x] **Step 4: Commit**

```bash
git add docs/ledger/outstanding-work.md docs/ledger/manual-checks.md
git commit -S -m "Added(docs): Record the motion craft corrections and update D-14/D-15"
```
