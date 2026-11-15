# Cursor visual polish — design

**Date:** 2026-11-14
**Scope:** three cosmetic upgrades to the terminal cursor, all inside `:terminal-view`
(`com.termux.view`): smooth fade blink, a soft glow, and eased movement between cells.
**Status:** design approved in chat 2026-11-14; not yet implemented.
**Ledger item:** none yet — this is a new feature; a device check (D-14) is added to
`manual-checks.md` on implementation.

## 1. Why

Today the cursor is functional but abrupt:

- **Blink** is a hard square wave. `TerminalView.TerminalCursorBlinkerRunnable` toggles a boolean
  every `mBlinkRate` ms and calls `invalidate()`; `TerminalRenderer` draws the cursor or skips it
  depending on `TerminalEmulator.shouldCursorBeVisible()`. No intermediate states.
- **No glow.** The cursor rect is a flat filled rectangle drawn with the shared `mTextPaint`.
- **No easing.** The cursor jumps cell-to-cell from the emulator grid; there is no visual
  interpolation.

The three changes are cosmetic only: no input, selection, IME or emulator behaviour changes, no
published-API change.

## 2. Goals and non-goals

Goals:

- **Fade blink** — the cursor fades in and out on a triangular envelope instead of snapping.
- **Glow** — a soft shadow halo under the cursor, radius proportional to the font size.
- **Eased movement** — bar and underline cursors glide to their target cell (~120 ms); block cursors
  stay snapped.

Non-goals:

- **No block-cursor easing.** A block cursor inverts the glyph colours of its cell, and that inversion
  is baked into the text run (the run loop keys `invertCursorTextColor` to the true cursor column).
  Easing a block cursor would require drawing the cell's glyphs twice (once normal, once inverted at
  the eased position). Deferred until it is worth it.
- **No change to `:terminal-emulator`.** `shouldCursorBeVisible()`, `setCursorBlinkingEnabled()` and
  `setCursorBlinkState()` keep their exact meaning; the emulator stays the source of the cursor's true
  cell and its "shown at all" gate (`DECSET 25` + blink enabled).
- **No published-API change.** `TerminalRenderer.render(...)` keeps the existing 8-argument overload;
  the new behaviour arrives through an added overload, so `:app`, the companion apps and the existing
  tests compile unchanged.
- **No new dependency, no Robolectric.** `:terminal-view` still runs plain JUnit with
  `unitTests.returnDefaultValues = true`.

## 3. Design

Three pieces: a pure math helper, renderer changes, and thin view wiring.

### 3.1 `CursorFx` — pure helper

Package-private class in `com.termux.view`, the same shape as `RenderBand` / `TerminalGeometry`
(no `android.*` imports, static methods, private constructor):

```java
final class CursorFx {
    /** Triangular blink envelope in [0,1]: 1 at t=0, 0 at t=period/2, 1 at t=period. */
    static float blinkAlpha(long elapsedMs, long periodMs);

    /** One exponential-smoothing step of `current` toward `target`; `factor` clamped to [0,1]. */
    static float easeTowards(float current, float target, float factor);
}
```

`blinkAlpha` returns 1 for `periodMs <= 0` (blink disabled = always fully visible). `easeTowards`
with `factor = 1` jumps (snap), near 0 eases slowly.

### 3.2 `TerminalRenderer` — alpha, glow, eased rect

**New overload.** The existing 8-argument `render(...)` delegates to a new overload with four extra
parameters:

```java
public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow,
                         int selectionY1, int selectionY2, int selectionX1, int selectionX2,
                         int firstScreenRow, int lastScreenRowExclusive);

public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow,
                         int selectionY1, int selectionY2, int selectionX1, int selectionX2,
                         int firstScreenRow, int lastScreenRowExclusive,
                         float cursorAlpha, float glowRadius,
                         float easedCursorRow, float easedCursorCol);
```

`cursorAlpha` is the blink envelope in `[0,1]`; `glowRadius` is the shadow radius in px (0 = off);
`easedCursorRow`/`easedCursorCol` are the eased cursor cell in floats, in the same external-screen
coordinate space as `getCursorRow()`/`getCursorCol()`; a negative `easedCursorCol` means "no eased
cursor active". The 8-argument overload passes `(1f, 0f, -1f, -1f)` so existing callers and tests are
pixel-identical.

**Fields.** The four values are stored on the renderer as `mCursorAlpha`, `mGlowRadius`,
`mEasedCursorRow`, `mEasedCursorCol` at the top of `render(...)`, so `drawTextRun` can read them.
`mEaseActive` is `easedCursorCol >= 0 && cursorShape ∈ {BAR, UNDERLINE}` (block never eases).

**Alpha gate.** In the row loop, the cursor cell is only marked when it would actually show:

```java
final int cursorX = (row == cursorRow && cursorVisible && mCursorAlpha > 0f) ? cursorCol : -1;
```

This gates the inline cursor rect *and* the block-cursor text inversion together, so at the off
phase (`mCursorAlpha == 0`) the cell reverts to plain text with no inversion.

**Cursor rect.** The rect is still drawn inside `drawTextRun` (the cursor cell is always its own run,
so the run's `left`/`right` are the cell bounds), with the frame's alpha applied on the shared
`mTextPaint`, then reset:

```java
if (cursor != 0 && !mEaseActive && !fadingBlockCursor) {
    mTextPaint.setColor(cursor);
    mTextPaint.setAlpha((int) (mCursorAlpha * 255f + 0.5f));
    // ... existing cursorHeight / bar-shrink geometry ...
    canvas.drawRect(left, y - cursorHeight, right, y, mTextPaint);
    mTextPaint.setAlpha(255);
}
```

`setAlpha(255)` is mandatory: the same paint immediately draws the run's glyphs, which must not inherit
the cursor's fade.

**Block cursor fade.** A block cursor's cell is painted opaque — the cursor colour behind, the cell's
background colour in front — so a translucent rect over it cannot fade it. When
`cursor != 0 && cursorStyle == BLOCK && mCursorAlpha < 1f`, the run's two colours are blended towards
the block's instead (`blendColor`), which fades the block and its inverted glyph together, and the
overlay rect is skipped. At `mCursorAlpha == 1` the solid path above runs unchanged.

**Glow.** A `Paint` shadow layer is ignored for `Canvas.drawRect` on a hardware-accelerated canvas
(what a `View`'s `onDraw` is handed), so the halo is drawn explicitly: three expanding rects of low,
alpha-scaled opacity, **before the rows**, so every glyph is painted on top of it and stays crisp.
`glowRadius == 0` draws no halo at all.

**Eased rect (post-pass).** After the row loop, when `mEaseActive` and `mCursorAlpha > 0`, the eased
rect is drawn once at the eased pixel position instead of inline:

```java
float easedLeft = mEasedCursorCol * mFontWidth;
float easedY = mFontLineSpacingAndAscent + (mEasedCursorRow - topRow + 1) * mFontLineSpacing;
// same drawCursorRect geometry as the inline path, single cell wide
```

`mEaseActive` suppresses the inline bar/underline rect (`cursor != 0 && !mEaseActive`), so a single
rect is drawn, at the eased position, with the same alpha and glow.

### 3.3 `TerminalView` — animation driver

The blinker's start/stop gating is preserved (`setTerminalCursorBlinkerState`,
`stopTerminalCursorBlinker`, the `DECSET 25` / cursor-enabled checks, and the activity-visibility
guard). The runnable body changes from a boolean toggle to an animation loop:

- **Blink fade.** When blinking is enabled, alpha is computed from the clock rather than toggled:
  `CursorFx.blinkAlpha(SystemClock.uptimeMillis(), 2 * mBlinkRate)` — the full on/off cycle stays
  `2 * mBlinkRate`, matching today's cadence (1 at t=0, 0 at t=mBlinkRate, 1 at t=2*mBlinkRate).
  Because alpha must move smoothly, the existing `mTerminalCursorBlinkerHandler` re-posts the
  runnable at a fixed ~16 ms cadence (instead of once per `mBlinkRate`) while blink or easing is
  active, and the runnable stops re-posting when both are idle. No `Choreographer` is added.
- **Easing.** On a cursor move (the `overlayChanged` check already detects row/col changes), a display
  cursor (`mDisplayCursorRow`, `mDisplayCursorCol`, floats) eases toward the true cell via
  `CursorFx.easeTowards(..., factor)` each frame; it snaps to the target when within a small epsilon
  and the animation stops. The eased floats are passed to the new `render(...)` overload only while the
  glide is running (`mCursorEasing`); at rest the renderer's inline path draws the bar/underline behind
  the glyphs and across the whole cluster's cells. A block cursor never eases.
- **Invalidation.** Each animation frame invalidates only the union of the cursor's previous and current
  cell plus the halo, not the whole view, so an idle fade does not repaint every visible row at 60 fps.
  Such a frame is flagged as cursor-only and `onDraw` then leaves the buffer's dirty state and the
  overlay bookkeeping alone for the next screen update (a partial frame must not consume dirty rows it
  did not paint). The runnable is always posted on the main thread, and its state fields are `volatile`.
- **Fallback.** When neither blink nor ease is active the loop stops re-posting; the view still calls
  the new overload so the values are explicit.

## 4. Data flow

```
SystemClock.uptimeMillis()
        │  CursorFx.blinkAlpha            ┌──────────────┐
        ├───────────────────────────────► │ TerminalView │ ── cursorAlpha ──┐
emulator.getCursorRow()/Col()             │  animation   │ ── easedRow/Col ─┼──► TerminalRenderer.render(...)
        └─ CursorFx.easeTowards ──────────►│  driver      │ ── glowRadius ──┘        │
                                          └──────────────┘                          ▼
                                                        cursor rect: alpha + shadow (+ eased post-pass)
```

## 5. Testing

- **`CursorFxTest`** (plain JUnit): `blinkAlpha` at period start/mid/end/quarter, disabled period,
  and `easeTowards` proportionality, snap at factor 1, no-op at factor 0, factor clamping, and a
  target-below-current step.
- **`TerminalRendererCursorTest`** (plain JUnit, same `RecordingCanvas` seam as
  `TerminalRendererRunTest`, plus a `Paint` stub that records `setAlpha`/`setShadowLayer`/
  `clearShadowLayer` calls): assert —
  - at `cursorAlpha == 0` no cursor rect is drawn and the paint is never given a cursor alpha;
  - at `cursorAlpha == 0.5` the cursor rect is drawn with the paint's alpha set to ~128;
  - `glowRadius > 0` draws the halo rects before the rows (no `Paint` shadow layer, which a hardware
    canvas ignores for `drawRect`), and `glowRadius == 0` draws none;
  - a fading block cursor blends its cell's background and glyph colours instead of painting an opaque
    cell under a translucent overlay, and is solid at `cursorAlpha == 1`;
  - an eased bar/underline cursor draws exactly one rect at the eased pixel position and none inline;
  - a block cursor never eases (rect stays at the true cell even when an eased position is passed).
- **Not unit-testable on this host** (no Robolectric in `:terminal-view`): the `Choreographer`/
  `Handler` animation loop and the view wiring. These are kept thin; all their math lives in
  `CursorFx`, and the renderer decisions are covered above.
- **Device check D-14** (added to `manual-checks.md`, run in the device phase): cursor visibly fades
  instead of snapping; glow is visible at a small font size and does not smear text; bar/underline
  cursor glides on arrow-key movement and lands on the exact cell; block cursor still snaps and still
  inverts its cell; blink-off and `DECSET 25` hide behave as before.

## 6. Edge cases

- Blink disabled (`periodMs <= 0`) → `blinkAlpha` returns 1, cursor fully opaque, no glow artefact.
- `cursorAlpha == 0` → no rect, no block inversion; the cell's text is drawn normally.
- `glowRadius == 0` (default 8-arg overload) → no halo rects at all, identical to today.
- A fading block cursor blends its cell colours; at `cursorAlpha == 1` the solid path is unchanged.
- Eased position not active (`easedCursorCol < 0`) → inline path, no post-pass.
- Block cursor with an eased position supplied → `mEaseActive` is false, inline rect at the true cell.
- Alpha/shadow must always be reset after the cursor rect so glyphs are not faded or haloed.

## 7. Verification

1. `CursorFxTest` and `TerminalRendererCursorTest` written first; the red state is a compile failure
   against the not-yet-existing helper / overload.
2. `./gradlew :terminal-view:testDebugUnitTest` green after each piece
   (`:terminal-view:test` rejects `--tests`, per the 2026-11-04 seam spec).
3. Full `./gradlew test` green.
4. One `:app:assembleDebug` to prove the app still compiles against the new overload.
5. Device check D-14 is deferred to the device phase (kept last, per the work order).

## 8. Documentation and rollback

- `outstanding-work.md` gains a short entry under §3 recording the feature and its D-14 device check.
- `manual-checks.md` gains D-14.
- The renderer's new overload is additive; reverting it leaves the 8-argument path byte-for-byte
  identical, so the change is independently revertible from the view wiring.
