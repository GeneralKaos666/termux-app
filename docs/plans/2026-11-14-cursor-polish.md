# Cursor visual polish — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the terminal cursor a smooth fade blink, a soft glow, and eased movement (bar/underline cursors glide; block snaps).

**Architecture:** A pure math helper (`CursorFx`) holds the blink envelope and easing; `TerminalRenderer` gains an additive `render(...)` overload that applies alpha + glow to the cursor rect and draws an eased bar/underline rect in a post-pass; `TerminalView` drives the animation and passes the four effect values. The emulator is untouched.

**Tech Stack:** Java (plain JUnit in `:terminal-view`, `unitTests.returnDefaultValues=true`), Android `Canvas`/`Paint`.

**Spec:** `docs/specs/2026-11-14-cursor-polish-design.md`

## Global Constraints

- Toolchain floors live in `gradle.properties`: `minSdkVersion=23`, `targetSdkVersion=28`, `compileSdkVersion=37`.
- `CursorFx` must import no `android.*` types (pure float math, like `RenderBand`).
- `:terminal-view` has no Robolectric; tests are plain JUnit only.
- `:terminal-view:test` rejects `--tests`; run `./gradlew :terminal-view:testDebugUnitTest` per class group and `./gradlew test` for the full suite.
- No `:terminal-emulator` change; the 8- and 9-argument `render(...)` overloads stay byte-for-byte identical.
- Commits: Conventional Commits with capitalized types (`Added`, `Changed`, `Fixed`, …) and present-tense description; every commit GPG-signed (`git commit -S`).

## Review Focus

1. **Paint alpha/shadow leakage** — the cursor rect sets `setAlpha`/`setShadowLayer` on the shared `mTextPaint`; if not reset, the run's glyphs draw faded/haloed. Test: `cursorRectUsesAlphaAndGlow` (Task 2) asserts the paint ends reset (alpha 255, no shadow).
2. **Blink disabled still opaque** — `periodMs <= 0` must yield alpha 1 so a blink-off cursor never fades. Test: `blinkAlphaDisabledPeriodIsFull` (Task 1).
3. **Block cursor ignores eased position** — an eased rect must never move a block cursor or its text inversion. Test: `blockCursorIgnoresEasing` (Task 2).
4. **Zero alpha hides the cursor entirely** — at the off phase there is no rect and no block text inversion. Test: `cursorHiddenAtZeroAlpha` (Task 2).
5. **Existing render overloads unchanged** — the 8/9-argument paths must stay pixel-identical so `:app`, companion apps and `TerminalRendererRunTest` are unaffected. Pin: `TerminalRendererRunTest` stays green (Task 2).

---

## File Structure

- Create: `terminal-view/src/main/java/com/termux/view/CursorFx.java` — pure blink/easing math.
- Create: `terminal-view/src/test/java/com/termux/view/CursorFxTest.java`
- Create: `terminal-view/src/test/java/com/termux/view/TerminalRendererCursorTest.java`
- Modify: `terminal-view/src/main/java/com/termux/view/TerminalRenderer.java` — new overload, alpha/glow/eased cursor.
- Modify: `terminal-view/src/main/java/com/termux/view/TerminalView.java` — animation driver wiring.
- Modify: `docs/ledger/outstanding-work.md` — §3 entry.
- Modify: `docs/ledger/manual-checks.md` — D-14.

---

### Task 1: `CursorFx` pure helper

**Files:**
- Create: `terminal-view/src/main/java/com/termux/view/CursorFx.java`
- Test: `terminal-view/src/test/java/com/termux/view/CursorFxTest.java`

**Interfaces:**
- Produces:
  - `static float CursorFx.blinkAlpha(long elapsedMs, long periodMs)` — triangular wave in `[0,1]`: 1 at phase 0, 0 at `period/2`, 1 at `period`; `periodMs <= 0` returns 1.
  - `static float CursorFx.easeTowards(float current, float target, float factor)` — one exponential-smoothing step, `factor` clamped to `[0,1]`.

- [ ] **Step 1: Write the failing test** (`CursorFxTest`, JUnit 4, package `com.termux.view`)

```java
public class CursorFxTest {
    @Test public void blinkAlphaFullAtPeriodStartAndEnd() {
        assertEquals(1f, CursorFx.blinkAlpha(0, 1000), 0.0001f);
        assertEquals(1f, CursorFx.blinkAlpha(1000, 1000), 0.0001f);
        assertEquals(1f, CursorFx.blinkAlpha(2000, 1000), 0.0001f);
    }
    @Test public void blinkAlphaZeroAtHalfPeriod() {
        assertEquals(0f, CursorFx.blinkAlpha(500, 1000), 0.0001f);
        assertEquals(0f, CursorFx.blinkAlpha(1500, 1000), 0.0001f);
    }
    @Test public void blinkAlphaQuarterPeriodIsHalf() {
        assertEquals(0.5f, CursorFx.blinkAlpha(250, 1000), 0.0001f);
        assertEquals(0.5f, CursorFx.blinkAlpha(750, 1000), 0.0001f);
    }
    @Test public void blinkAlphaDisabledPeriodIsFull() {
        assertEquals(1f, CursorFx.blinkAlpha(123, 0), 0.0001f);
        assertEquals(1f, CursorFx.blinkAlpha(123, -5), 0.0001f);
    }
    @Test public void easeTowardsMovesProportionally() {
        assertEquals(5f, CursorFx.easeTowards(0f, 10f, 0.5f), 0.0001f);
        assertEquals(10f, CursorFx.easeTowards(0f, 10f, 1f), 0.0001f);
        assertEquals(0f, CursorFx.easeTowards(0f, 10f, 0f), 0.0001f);
    }
    @Test public void easeTowardsClampsFactor() {
        assertEquals(10f, CursorFx.easeTowards(0f, 10f, 2f), 0.0001f);
        assertEquals(0f, CursorFx.easeTowards(0f, 10f, -1f), 0.0001f);
    }
    @Test public void easeTowardsConvergesFromAbove() {
        assertEquals(7.5f, CursorFx.easeTowards(10f, 0f, 0.25f), 0.0001f);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :terminal-view:testDebugUnitTest --tests "com.termux.view.CursorFxTest"`
Expected: compilation FAIL — `CursorFx` does not exist.

- [ ] **Step 3: Implement `CursorFx` in `terminal-view/src/main/java/com/termux/view/CursorFx.java`**

Package-private `final class CursorFx` with a private constructor, no `android.*` imports. `blinkAlpha` computes the triangular wave from `elapsedMs % periodMs`; `easeTowards` clamps `factor` then returns `current + (target - current) * factor`. Javadoc mirrors the spec §3.1.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :terminal-view:testDebugUnitTest --tests "com.termux.view.CursorFxTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add terminal-view/src/main/java/com/termux/view/CursorFx.java terminal-view/src/test/java/com/termux/view/CursorFxTest.java
git commit -S -m "Added(terminal): Add CursorFx blink and easing math"
```

---

### Task 2: `TerminalRenderer` alpha, glow and eased cursor

**Files:**
- Modify: `terminal-view/src/main/java/com/termux/view/TerminalRenderer.java` (fields, new overload, `drawCursorRect` helper, eased post-pass)
- Test: `terminal-view/src/test/java/com/termux/view/TerminalRendererCursorTest.java`

**Interfaces:**
- Consumes: `CursorFx` (not used here; only Task 3 uses it).
- Produces (new overload):
  ```java
  public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow,
                           int selectionY1, int selectionY2, int selectionX1, int selectionX2,
                           int firstScreenRow, int lastScreenRowExclusive,
                           float cursorAlpha, float glowRadius, float easedCursorRow, float easedCursorCol);
  ```
  The existing 9-argument `render(...)` (line 109) delegates to it with `(1f, 0f, -1f, -1f)`.

- [ ] **Step 1: Write the failing test** (`TerminalRendererCursorTest`, JUnit 4, package `com.termux.view`)

Seam: a `RecordingPaint extends Paint` overriding `getFontSpacing()=20f`, `ascent()=-15f`, and the three `measureText(...)` overloads to return `10f`, plus `setAlpha(int)`, `setShadowLayer(float,float,float,int)`, `clearShadowLayer()` recording into fields `lastAlpha`/`lastShadowRadius`. A `RecordingCanvas extends Canvas` overriding `drawRect(float,float,float,float,Paint)` to append `{left, top, right, bottom, paint.lastAlpha, paint.lastShadowRadius}` to a `rects` list, and `drawText`/`drawTextRun`/`save`/`restore`/`scale` as no-ops. `setUp` builds `new TerminalEmulator(new StubOutput(), 10, 3, 10, 20, 3, null)` (StubOutput mirrors `TerminalRendererRunTest`), the paint and canvas. `render(a, g, r, c)` calls
`new TerminalRenderer(14, Typeface.MONOSPACE, true, mPaint, null).render(mTerminal, mCanvas, 0, -1, -1, -1, -1, 0, 1, a, g, r, c)`.

```java
@Test public void cursorRectUsesAlphaAndGlow() {
    render(0.5f, 4f, -1f, -1f);
    assertEquals(1, mCanvas.rects.size());
    assertEquals(128f, mCanvas.rects.get(0)[4], 1f);   // alpha 0.5 -> 128
    assertEquals(4f, mCanvas.rects.get(0)[5], 0.01f);  // glow radius 4
    assertEquals(255, mPaint.lastAlpha);               // reset after the rect
    assertEquals(0f, mPaint.lastShadowRadius, 0.01f);  // shadow cleared
}

@Test public void cursorHiddenAtZeroAlpha() {
    render(0f, 0f, -1f, -1f);
    assertEquals(0, mCanvas.rects.size());             // no rect, no inversion
}

@Test public void barCursorEasesToEasedPosition() {
    enter("\033[6 q");                                 // DECSCUSR: steady bar
    render(1f, 0f, 0f, 5.5f);
    assertEquals(1, mCanvas.rects.size());
    assertEquals(55f, mCanvas.rects.get(0)[0], 0.01f); // left = 5.5 * 10 (eased), not 0
}

@Test public void blockCursorIgnoresEasing() {
    render(1f, 0f, 0f, 5.5f);                          // default block cursor
    assertEquals(1, mCanvas.rects.size());
    assertEquals(0f, mCanvas.rects.get(0)[0], 0.01f);  // stays at the true cell 0
}
```

(`enter(s)` = `mTerminal.append(s.getBytes(UTF_8), len)`, as in `TerminalRendererRunTest`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :terminal-view:testDebugUnitTest --tests "com.termux.view.TerminalRendererCursorTest"`
Expected: FAIL — `cursorRectUsesAlphaAndGlow` expects one rect but the default 9-arg path draws it with no alpha/shadow; the eased cases fail to compile against the missing overload.

- [ ] **Step 3: Implement the renderer changes in `TerminalRenderer.java`**

Add fields `private float mCursorAlpha = 1f;`, `private float mGlowRadius = 0f;`, `private float mEasedCursorRow = -1f;`, `private float mEasedCursorCol = -1f;`, `private boolean mEaseActive = false;`.

Add the 13-argument `render(...)` overload with the exact signature above. At its top, store the four values into the fields, and after `cursorShape` is read set
`mEaseActive = easedCursorCol >= 0f && (cursorShape == TERMINAL_CURSOR_STYLE_BAR || cursorShape == TERMINAL_CURSOR_STYLE_UNDERLINE)`.

Change the `cursorX` gate to `(row == cursorRow && cursorVisible && mCursorAlpha > 0f)`.

Replace the inline cursor-rect block in `drawTextRun` (currently `if (cursor != 0) { … }`) with `if (cursor != 0 && !mEaseActive) drawCursorRect(canvas, left, right, y, cursorShape, cursor);`, and add the private helper (moves the existing geometry verbatim, adds alpha/shadow around it):

```java
private void drawCursorRect(Canvas canvas, float left, float right, float y, int cursorShape, int cursorColor) {
    mTextPaint.setColor(cursorColor);
    mTextPaint.setAlpha((int) (mCursorAlpha * 255f + 0.5f));
    if (mGlowRadius > 0f) mTextPaint.setShadowLayer(mGlowRadius, 0f, 0f, cursorColor);
    float cursorHeight = mFontLineSpacingAndAscent - mFontAscent;
    if (cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) cursorHeight /= 4.f;
    else if (cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) right -= ((right - left) * 3) / 4.f;
    canvas.drawRect(left, y - cursorHeight, right, y, mTextPaint);
    if (mGlowRadius > 0f) mTextPaint.clearShadowLayer();
    mTextPaint.setAlpha(255);
}
```

After the row loop, before the method returns, add the eased post-pass:

```java
if (mEaseActive && cursorVisible && mCursorAlpha > 0f && mEasedCursorRow >= 0f) {
    float easedLeft = mEasedCursorCol * mFontWidth;
    float easedY = mFontLineSpacingAndAscent + (mEasedCursorRow - topRow + 1) * mFontLineSpacing;
    drawCursorRect(canvas, easedLeft, easedLeft + mFontWidth, easedY, cursorShape,
        palette[TextStyle.COLOR_INDEX_CURSOR]);
}
```

Make the 9-argument `render(...)` delegate to the new overload with `(1f, 0f, -1f, -1f)`. Do not change the 7-argument overload.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :terminal-view:testDebugUnitTest --tests "com.termux.view.TerminalRendererCursorTest" --tests "com.termux.view.TerminalRendererRunTest"`
Expected: PASS — the new cursor tests plus the existing run tests (which pin Review Focus 5) stay green.

- [ ] **Step 5: Commit**

```bash
git add terminal-view/src/main/java/com/termux/view/TerminalRenderer.java terminal-view/src/test/java/com/termux/view/TerminalRendererCursorTest.java
git commit -S -m "Added(terminal): Add cursor fade, glow and eased bar/underline rendering"
```

---

### Task 3: `TerminalView` animation wiring

**Files:**
- Modify: `terminal-view/src/main/java/com/termux/view/TerminalView.java`

**Interfaces:**
- Consumes: `CursorFx.blinkAlpha(long, long)`, `CursorFx.easeTowards(float, float, float)`; `TerminalRenderer.render(...)` 13-argument overload.
- Produces: no new public API; the animation loop and eased-cursor fields are private to `TerminalView`.

- [ ] **Step 1: Add fields and constants**

Add `private static final long CURSOR_ANIMATION_INTERVAL_MS = 16L;` and fields `private float mCursorAlpha = 1f;`, `private float mDisplayCursorRow = -1f;`, `private float mDisplayCursorCol = -1f;`. (No unit test — this is the View-bound half; `:terminal-view` has no Robolectric, per the ledger §3.2.)

- [ ] **Step 2: Rework `TerminalCursorBlinkerRunnable`**

`run()` stops toggling the boolean; instead it: (a) when blinking is enabled, sets `mCursorAlpha = CursorFx.blinkAlpha(SystemClock.uptimeMillis(), 2 * mBlinkRate)`; (b) when easing is active, steps `mDisplayCursorRow`/`mDisplayCursorCol` one `CursorFx.easeTowards(..., 0.35f)` toward the target and snaps when within `0.05f`; (c) calls `invalidate()`; (d) re-posts after `CURSOR_ANIMATION_INTERVAL_MS` only while blink or easing is active. `stopTerminalCursorBlinker()` resets `mCursorAlpha = 1f`.

- [ ] **Step 3: Drive easing on cursor move**

In `onDraw`, after the existing `overlayChanged` check (lines 489–493) detects a `getCursorRow()`/`getCursorCol()` change, start easing: set the target to the new cell, initialise `mDisplayCursorRow`/`mDisplayCursorCol` to the previous drawn position (snap to the new cell on the first bind), and mark easing active for bar/underline only. The emulator's true `getCursorCol`/`getCursorRow` are never written, so selection/IME stay on the true cell.

- [ ] **Step 4: Pass the effect values in `onDraw`**

Replace the `mRenderer.render(...)` call (line 1067) with the 13-argument overload, passing
`cursorAlpha = mCursorAlpha`,
`glowRadius = Math.max(1f, mRenderer.mFontLineSpacing / 8f)`,
and `easedCursorRow/Col = mDisplayCursorRow/Col` when the cursor style is `TERMINAL_CURSOR_STYLE_BAR` or `TERMINAL_CURSOR_STYLE_UNDERLINE`, else `(-1f, -1f)`.

- [ ] **Step 5: Build and full test**

Run: `./gradlew test`
Expected: PASS (existing 701 methods stay green; no new unit tests).

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (proves `:app` compiles against the new overload).

- [ ] **Step 6: Commit**

```bash
git add terminal-view/src/main/java/com/termux/view/TerminalView.java
git commit -S -m "Added(terminal): Drive cursor fade and eased movement from TerminalView"
```

---

### Task 4: Ledger and device check

**Files:**
- Modify: `docs/ledger/outstanding-work.md` — add a §3 entry recording the feature and its D-14 check.
- Modify: `docs/ledger/manual-checks.md` — add D-14.

- [ ] **Step 1: Add the ledger entry**

Under §3 of `outstanding-work.md`, add one line: cursor visual polish (fade blink, glow, eased bar/underline) shipped in `:terminal-view`; block cursor does not ease; device check D-14.

- [ ] **Step 2: Add D-14 to `manual-checks.md`**

Add a D-14 section with the checks from the spec §5: cursor fades instead of snapping; glow visible at a small font and does not smear text; bar/underline cursor glides on arrow-key movement and lands on the exact cell; block cursor still snaps and inverts its cell; blink-off and `DECSET 25` hide behave as before.

- [ ] **Step 3: Commit**

```bash
git add docs/ledger/outstanding-work.md docs/ledger/manual-checks.md
git commit -S -m "Docs(ledger): Record cursor polish feature and D-14 device check"
```
