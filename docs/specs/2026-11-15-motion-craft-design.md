# Motion craft fixes — design

**Date:** 2026-11-15
**Scope:** correct the craft and frame-timing problems found in the 2026-11-14 motion work, in both the
`:app` Compose shell (`theme/Motion.kt`, `ShellHost.kt`, `sessions/SessionsBar.kt`, `theme/Theme.kt`)
and the `:terminal-view` cursor loop (`CursorFx`, `TerminalView`).
**Status:** approved and implemented 2026-11-15. The two reversals in §9 were confirmed before coding
and the work shipped (ledger §1.4 records it as `[shipped 2026-11-14]`, `[corrected 2026-11-15]`);
device checks D-14 and D-15 remain in the device phase.
**Ledger item:** amends §1.4 entries `[shipped 2026-11-14]`; updates device checks D-14 and D-15.

## 1. Why

The 2026-11-14 UI-motion and cursor-polish work shipped and is recorded as done. An animation review
of the whole app found four defects and two smaller ones:

1. **Float enters from `scale(0)`.** `Motion.kt#floatEnterScaleAndAlpha` returns the raw enter
   progress as both scale and alpha, and that progress starts at `0f`, so the bubble and the float
   window grow out of a point. Nothing should appear from nothing; the scale should start near `1`.
2. **Chrome bars use a bouncy spring on a size and a fade.** `ShellHost.kt` gives `expandVertically`/
   `shrinkVertically` and `fadeIn`/`fadeOut` the shared `MediumBouncy` spring. A bar-height overshoot
   reads as wrong, and a spring on alpha overshoots past 1 and is clamped, so it buys nothing.
3. **The cursor frame loop is a fixed 16 ms `Handler` timer.** `TerminalView.TerminalCursorBlinkerRunnable`
   re-posts itself with `postDelayed(this, 16)`. It is not vsync-aligned, it drifts, and it runs at
   60 fps on a 120 Hz panel.
4. **Cursor easing is per-frame, not per-time.** `CursorFx.easeTowards(current, target, 0.35f)` covers
   a fixed fraction of the distance per frame, so the glide speed depends on callback cadence rather
   than elapsed time.
5. **The terminal cursor ignores "remove animations".** The Compose shell is gated on
   `shellAnimationsEnabled()`, but the cursor fade-blink and glide are driven only by the blink-rate
   preference, so they keep animating with `ANIMATOR_DURATION_SCALE == 0`.
6. **`shellAnimationsEnabled()` is read once.** It is a `remember` over `Settings.Global`, so toggling
   "remove animations" while the app runs has no effect until the activity is recreated.

## 2. Goals and non-goals

Goals:

- Float bubble and float window grow from ~92 % to 100 % with the shell spring, fading in — no
  `scale(0)`.
- Chrome bars expand/collapse on a tween (no overshoot); their fade is a tween.
- The cursor animation runs on `Choreographer` frame callbacks; easing is time-based.
- The terminal cursor stops animating when the system animator scale is 0.
- `shellAnimationsEnabled()` reacts to the setting changing while the app runs.

Non-goals:

- **No gesture or `WindowManager` change.** Drag/resize/reorder handling and `FloatController` maths
  are untouched.
- **No terminal content motion.** `TerminalRenderer`'s cursor rendering (alpha, glow, eased rect) is
  unchanged; only the loop that supplies it changes.
- **No new dependency.**
- **No change to the blink cadence** when animations are on: the full on/off cycle stays
  `2 * mBlinkRate`.
- **Block cursor still never eases.**

Accepted as-is (reviewed, deliberately not changed):

- **Extra-keys pager height (`ExtraKeysBar.kt`) animates a `Dp`.** `animateDpAsState` starts at its
  first target, so it does not animate on first composition — only on a user collapse/page change,
  where the height change is the point. Left on the 180 ms tween.
- **`FirstRunOverlay`'s `CircularProgressIndicator` spins forever.** It signals ongoing work, so it is
  not gated on reduced motion.

## 3. Design — Compose shell (`:app`)

### 3.1 Float enter origin

`floatEnterProgress()` is unchanged (0 → 1 on first composition, 1 when animations are off).
`floatEnterScaleAndAlpha()` stops returning the progress as the scale; it maps the progress into
`[FloatEnterMinScale, 1]` for the scale and keeps the clamped progress as the alpha:

```kotlin
private const val FloatEnterMinScale = 0.92f

@Composable
fun floatEnterScaleAndAlpha(): Pair<Float, Float> {
    val progress = floatEnterProgress()
    val scale = FloatEnterMinScale + (1f - FloatEnterMinScale) * progress
    return scale to progress.coerceIn(0f, 1f)
}
```

At the first frame the overlay is at 92 % and transparent; the spring's overshoot carries the scale
just past 100 % (the pop) while the alpha clamps at 1. `FloatingWindow.kt` and `FloatingBubble.kt`
already read this helper and need no change.

### 3.2 Chrome transitions

`ShellHost.kt` drops the spring on the two `AnimatedVisibility` blocks for a tween, with asymmetric
enter/exit (showing a bar is the deliberate act, hiding is a response):

```kotlin
private val ChromeSizeIn = tween<IntSize>(durationMillis = 220, easing = FastOutSlowInEasing)
private val ChromeSizeOut = tween<IntSize>(durationMillis = 180, easing = FastOutSlowInEasing)
private val ChromeFadeIn = tween<Float>(durationMillis = 150)
private val ChromeFadeOut = tween<Float>(durationMillis = 120)

enter = if (animationsEnabled) expandVertically(ChromeSizeIn) + fadeIn(ChromeFadeIn) else EnterTransition.None,
exit  = if (animationsEnabled) shrinkVertically(ChromeSizeOut) + fadeOut(ChromeFadeOut) else ExitTransition.None,
```

The size animation stays a layout animation on purpose: collapsing a bar reflows the terminal, so a
layout change is what the motion communicates. `shellSpringSpec()` stays in `Motion.kt` — it is still
the right spec for the float pop and the tab placement/slide.

### 3.3 Session-tab item fade

`SessionsBar.kt` keeps the spring for placement but moves the item fades to tweens, so a fading tab
does not carry a clamped alpha overshoot:

```kotlin
Modifier.animateItem(
    fadeInSpec = tween(durationMillis = 150),
    placementSpec = shellSpringSpec(),
    fadeOutSpec = tween(durationMillis = 120),
)
```

`SessionsBar` already computes `animationsEnabled`; pass it into `SessionTab` instead of each tab
calling `shellAnimationsEnabled()` itself (with the new reactive helper in §3.4, a call per tab would
register one content observer per tab).

### 3.4 Reactive `shellAnimationsEnabled()`

`Theme.kt#shellAnimationsEnabled()` reads the animator scale through a `ContentObserver` on
`Settings.Global.ANIMATOR_DURATION_SCALE` held in a `DisposableEffect`, so the value follows the
setting while the app is running:

```kotlin
@Composable
fun shellAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(animatorScaleEnabled(context)) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { enabled = animatorScaleEnabled(context) }
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer,
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return enabled
}

private fun animatorScaleEnabled(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
```

## 4. Design — cursor loop (`:terminal-view`)

### 4.1 Time-based easing

`CursorFx.easeTowards` becomes time-based. The remaining distance halves every `halfLifeMs`:

```java
static float easeTowards(float current, float target, float dtMs, float halfLifeMs) {
    if (dtMs <= 0f) return current;        // no time elapsed: hold
    if (halfLifeMs <= 0f) return target;   // degenerate half-life: snap
    float factor = 1f - (float) Math.pow(0.5, dtMs / halfLifeMs);
    return current + (target - current) * factor;
}
```

`TerminalView` uses `CURSOR_EASING_HALF_LIFE_MS = 26f`, which reproduces the old ~0.35-per-frame feel
at 60 fps (`1 - 0.5^(16/26) ≈ 0.347`) but is correct at any frame cadence. `CURSOR_EASING_FACTOR` is
removed.

### 4.2 Choreographer frame loop

`TerminalCursorBlinkerRunnable` implements `Choreographer.FrameCallback` instead of `Runnable`. It
keeps its `mEmulator` / `mBlinkRate` / `mBlinkStartUptimeMs` / `mCancelled` fields and its
`setEmulator`/`cancel`/`isCancelled` methods (called from `updateSize` and
`stopTerminalCursorBlinker`). `run()` becomes `doFrame(long frameTimeNanos)`:

- `dtMs` is the delta from the previous frame time (`0` on the first frame).
- blink alpha is unchanged (`CursorFx.blinkAlpha(SystemClock.uptimeMillis() - mBlinkStartUptimeMs, 2L * mBlinkRate)`).
- easing steps use `CursorFx.easeTowards(display, target, dtMs, CURSOR_EASING_HALF_LIFE_MS)`, with the
  same `0.05f` snap epsilon.
- the cursor-only invalidate is unchanged.
- it re-posts with `mChoreographer.postFrameCallback(this)` only while blink or easing is active and
  it was not cancelled.

`mTerminalCursorBlinkerHandler` (the `Handler`) is replaced by a `Choreographer` obtained from
`Choreographer.getInstance()`; `startCursorAnimationFrames` posts the frame callback and
`stopTerminalCursorBlinker` calls `removeFrameCallback`. `CURSOR_ANIMATION_INTERVAL_MS` is removed.

### 4.3 Respect "remove animations"

`TerminalView` gains `private boolean animationsEnabled()` reading
`Settings.Global.ANIMATOR_DURATION_SCALE`. When it is false:

- `setTerminalCursorBlinkerState(true, …)` does not set `mCursorBlinkRunning`, so the cursor is
  steady and fully opaque;
- `updateCursorAnimationBeforeRender` never enters easing, so a bar/underline cursor snaps to its
  cell;
- the frame loop is never started.

The cursor is then static: no fade, no glide, no per-frame work. Blink-off and `DECSET 25` keep their
current behaviour.

## 5. Data flow

```
ANIMATOR_DURATION_SCALE ── ContentObserver ──► shellAnimationsEnabled() ──► Compose specs (tween/None)
                        └─ TerminalView.animationsEnabled() ──► blink/ease gate

Choreographer.doFrame(frameTimeNanos)
        │  dtMs = frameTimeNanos - mLastFrameNanos
        ├─ CursorFx.blinkAlpha(uptime, 2*mBlinkRate) ─► mCursorAlpha
        └─ CursorFx.easeTowards(display, target, dtMs, 26f) ─► mDisplayCursorRow/Col
                    └────────────────► TerminalRenderer.render(...)   (unchanged)
```

## 6. Testing

- **Compose (`:app`):** declarative animation, no Compose UI test infra. Verification is
  `./gradlew test` + `:app` compile, plus device check D-15 (updated).
- **Cursor math (`:terminal-view`):** `CursorFxTest` is updated for the new `easeTowards` signature —
  hold with no time, snap with a zero half-life, half the distance over one half-life, three quarters
  over two, monotonic in `dt`, no overshoot. Plain JUnit, `unitTests.returnDefaultValues = true`.
- **Loop wiring:** not unit-testable on this host (no Robolectric in `:terminal-view`); kept thin, all
  math in `CursorFx`. Covered by device check D-14 (updated).

## 7. Edge cases

- Animations off → chrome transitions instant, float enters fully sized and opaque, cursor static.
- `dtMs == 0` (first frame, or a re-post in the same frame) → easing holds; blink still advances from
  the clock.
- Blink disabled (`mBlinkRate == 0`) → `blinkAlpha` returns 1; only easing drives the loop, and it
  stops when the glide settles.
- A frame already in flight when the loop is stopped → `mCancelled` makes it neither draw nor re-post.
- Rapid toggling of "remove animations" → the observer flips the state; Compose specs switch between
  tween and `None` on the next composition, and the cursor gate is read on the next blinker call.

## 8. Verification

1. `./gradlew :terminal-view:testDebugUnitTest` green after the `CursorFx` change.
2. Full `./gradlew test` green.
3. `./gradlew :app:compileDebugKotlin` (and the Java compile) green; `assembleDebug` where the host
   allows it (the NDK `aarch64` host failure is pre-existing).
4. Device checks D-14 and D-15, updated in `manual-checks.md`, deferred to the device phase.

## 9. Decisions reversed (need sign-off)

These contradict decisions recorded in the 2026-11-14 designs and must be confirmed before coding:

1. **Choreographer (was: "No `Choreographer` is added").** `cursor-polish-design.md` §3.3 deliberately
   kept the `Handler` loop. This design moves the loop to `Choreographer` frame callbacks for
   vsync alignment and correct cadence on high-refresh displays. Reversal.
2. **Tween chrome, not a bouncy spring (was: `MediumBouncy` on size and fade).**
   `ui-motion-design.md` §3.1 chose the spring on `expandVertically` + `fadeIn`. This design uses a
   tween for the size and the fade and keeps the spring only for the float pop and tab placement.
   Reversal of part of the earlier design.
3. **Static cursor under "remove animations" (new).** With animations off the cursor does not blink.
   This is the reduced-motion reading; it changes today's behaviour, where the cursor keeps fading and
   gliding.

## 10. Documentation and rollback

- `outstanding-work.md` §1.4: amend the two `[shipped 2026-11-14]` entries with a one-line
  "corrected 2026-11-15" note.
- `manual-checks.md`: update D-14 (cursor loop/static-under-remove-animations) and D-15 (tween chrome,
  float from 92 %).
- Each piece is independent and separately revertible: `Motion.kt` (float origin), `ShellHost.kt` +
  `SessionsBar.kt` (chrome easing), `Theme.kt` (reactive gate), `CursorFx` + `CursorFxTest` (easing
  math), `TerminalView` (loop + gate).
