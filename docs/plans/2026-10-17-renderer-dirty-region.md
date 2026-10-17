# Terminal engine upgrade — workstream 2: renderer dirty-region drawing (implementation plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ])` syntax for tracking.

**Goal:** Make `:terminal-view` draw only the dirty visible rows (targeted invalidation + row-range rendering), consume WS1's dirty flags and monotonic change counter at draw time, and render distinct underline styles (double/curly/dotted/dashed). The "image layer" is not in scope; link ids remain unrendered.

**Architecture:** Approach A — `TerminalView.onScreenUpdated()` decides (full/targeted/none) via a new pure-Java `RowInvalidation` helper; targeted invalidates paint only the affected row band; `TerminalRenderer.render(...)` gains a row-range overload; `onDraw` reads the clip, renders that range, then sets `mLastSeenChangeCounter = buffer.getChangeCounter()` and calls `buffer.clearDirtyState()`. Overlays keep their existing invalidates. Underline styles are drawn as strokes (or kept as Paint underline for single) after text.

**Tech Stack:** Android API 21+, Java 8 (no `var`, etc.), AndroidX annotations. Plain JUnit for the new helper tests.

**Spec:** [`../specs/2026-10-17-renderer-dirty-region-design.md`](../specs/2026-10-17-renderer-dirty-region-design.md)

## Global Constraints

- **No file outside `:terminal-view`.** `:terminal-emulator` and `:termux-shared` must show no changes in `git diff --stat`.
- **Zero diff in `:terminal-emulator`.** The 208 `:terminal-emulator` tests must remain green.
- **Public API:** `TerminalRenderer.render(...)` keeps the existing signature as a delegating overload. No public API changes to `TerminalView` or to `:termux-shared`.
- **Java 8 source level.** `terminal-view/build.gradle` uses `JavaVersion.VERSION_1_8`; no new language features.
- **Test-scope only.** Add JUnit to `terminal-view/build.gradle` in `dependencies { testImplementation ... }` only; no main-source dependency changes.
- **Underline:** custom styles (2–5) draw after text inside the run's scale block and don't double up Paint's underline. `SINGLE` (1) and `NONE`+underline keep `Paint.setUnderlineText(true)`.
- **Protocol:** clearing happens in `onDraw()` after render; counter jump or `mTopRow` change → full repaint; no invalidation if nothing dirty.

## Review Focus

1. **Viewport offset** — screen row → view Y must include `mTopRow` (history scrolled up). Expected: targeted band covers the actual on-screen strip. *Test:* `testDirtySpanIncludesTopRow` (Task 1).
2. **Viewport change inside `onScreenUpdated()`** — any `mTopRow` change earlier in the call → full repaint. Expected: don't target a stale band. *Test:* `testTopRowChangeForcesFull` (Task 1).
3. **Coalesced updates + draw-time clearing** — updates coalesce, draw sees union clip, `clearDirtyState()` runs after render (not at update). Expected: a pending update doesn't lose its repaint. *Test:* `testCounterJumpForcesFull` + implementation note (Task 1/2).
4. **Counter decreases** — treated as jump (defensive). Expected: full repaint. *Test:* `testCounterDecreaseForcesFull` (Task 1).
5. **Session/emulator rebind** — `mLastSeenChangeCounter` reset so first draw is full. Expected: stale counter doesn't suppress repaint. *Test:* `testSessionRebindForcesFull` (Task 2).

---

### Task 1: RowInvalidation helper (pure decision logic)

**Files:**
- Create: `terminal-view/src/main/java/com/termux/view/RowInvalidation.java` (package-private, no Android imports)
- Create: `terminal-view/src/test/java/com/termux/view/RowInvalidationTest.java` (plain JUnit 4)

**Interfaces:**
- Consumes: visible row range (0..rows-1), per-visible-row dirty flags (or a way to test: has index→dirty), `lastCounter`, `currentCounter`, `topRow`, `rows`, `viewHeight`, `fontLineSpacing`.
- Produces: a result object/values — `NONE`, `FULL`, `RANGE(topPx, bottomPx)` (or equivalent ints).

- [ ] **Step 1: Write the failing helper tests**

```java
// RowInvalidationTest.java
public class RowInvalidationTest extends TestCase {
  public void testNoneWhenCounterEqualAndNoDirty() { /* NONE */ }
  public void testCounterJumpForcesFull() { /* FULL */ }
  public void testCounterDecreaseForcesFull() { /* FULL */ }
  public void testDirtyContiguousSpan() { /* RANGE with first..last */ }
  public void testDirtyScatteredGivesSpan() { /* first..last */ }
  public void testDirtySpanIncludesTopRow() { /* topRow < 0 math */ }
  public void testTopRowChangeForcesFull() { /* FULL */ }
  public void testSpanClampedToViewHeight() { /* bottom <= height */ }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :terminal-view:testDebugUnitTest
```
Expected: compile errors (class/method missing).

- [ ] **Step 3: Implement `RowInvalidation`**

Fields/methods: `static class Range { int topPx,bottomPx; }` or return an int array. `static Result decide(...)` where `Result` is `enum Result { NONE, FULL, RANGE }` carrying top/bottom only if RANGE. Pure arithmetic: `topPx = (firstDirty - topRow)*fontLineSpacing`, `bottomPx = (lastDirty - topRow + 1)*fontLineSpacing` (or +1 for next row), clamp to `[0, viewHeight]`. "Top row changed" is passed in as `topRowChanged` flag (TerminalView knows if it changed). Counter: if `currentCounter != lastCounter` → FULL (and treat decrease same). No Android types.

- [ ] **Step 4: Verify helper tests pass**

```bash
./gradlew :terminal-view:testDebugUnitTest
```
Expected: RowInvalidationTest passes.

- [ ] **Step 5: Commit**

```bash
git add terminal-view/src/main/java/com/termux/view/RowInvalidation.java terminal-view/src/test/java/com/termux/view/RowInvalidationTest.java
git commit -S -m "Added(view): Add RowInvalidation helper for dirty-region decisions"
```

### Task 2: TerminalRenderer row-range entry point

**Files:**
- Modify: `terminal-view/src/main/java/com/termux/view/TerminalRenderer.java` (the `render(...)` method at ~61 and its row loop at ~77)

**Interfaces:**
- Produces: `public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow, int selectionY1, int selectionY2, int selectionX1, int selectionX2, int firstScreenRow, int lastScreenRowExclusive)` — the two new parameters are **external row numbers**, already clamped inside the visible band `[topRow, topRow + mEmulator.mRows)` by the caller.
- Consumes: nothing new.

- [ ] **Step 1: Add the overload; keep the old signature delegating**

The existing 7-arg `render(...)` body becomes an overload of the new 8-arg method:

```java
public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow,
                         int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
    render(mEmulator, canvas, topRow, selectionY1, selectionY2, selectionX1, selectionX2,
        topRow, topRow + mEmulator.mRows);
}
```

- [ ] **Step 2: Walk only the band and re-seed the accumulator**

`endRow` disappears; the loop is `for (int row = firstScreenRow; row < lastScreenRowExclusive; row++)`. **Trap (found during execution, 2026-10-17):** `heightOffset` accumulates one row per iteration and is seeded before the loop, so skipped rows must be accounted for:

```java
float heightOffset = mFontLineSpacingAndAscent + (firstScreenRow - topRow) * mFontLineSpacing;
```

Everything else inside the loop is unchanged (selection/cursor per-row computation, the run machinery, the bitmap branch).

- [ ] **Step 3: Verify**

```bash
./gradlew :terminal-view:compileDebugJavaWithJavac
./gradlew :terminal-emulator:test
```
Expected: compile OK; `:terminal-emulator` 208/208 (zero diff there — this step proves the module still builds against the unchanged renderer API).

- [ ] **Step 4: Commit**

```bash
git add terminal-view/src/main/java/com/termux/view/TerminalRenderer.java
git commit -S -m "Added(view): Add a row-range entry point to the terminal renderer"
```

---

### Task 3: Tested viewport mapping (`dirtyByIndex`)

**Files:**
- Modify: `terminal-view/src/main/java/com/termux/view/RowInvalidation.java`
- Modify: `terminal-view/build.gradle` (add `testOptions { unitTests.returnDefaultValues = true }` inside `android { }` — test-scope only, needed because `TerminalBuffer` touches `android.os.SystemClock`/`Bitmap`)
- Create: `terminal-view/src/test/java/com/termux/view/RowInvalidationBufferTest.java`

**Interfaces:**
- Produces: `static boolean[] dirtyByIndex(TerminalBuffer screen, int topRow, int rows)` — returns a viewport-relative flag array where index `i` is `screen.isRowDirty(topRow + i)`. **This is the only place the external-row mapping lives**, so the sign can be tested.
- Consumes: WS1's `TerminalBuffer.isRowDirty(int)`.

- [ ] **Step 1: Write the failing buffer-backed tests**

```java
// RowInvalidationBufferTest.java — transcript helper: two scrolls make two transcript rows
public void testFreshBufferIsAllClean() { /* new TerminalBuffer(5, 10, 3) → all false */ }
public void testOnlyTheWrittenRowIsDirty() { /* setChar(0, 1, 'a', 0) → index 1 true, 0 and 2 false */ }
public void testTopRowNegativeReadsTheTranscriptBand() {
    // TerminalBuffer(5, 10, 3); scrollDownOneLine(0, 3, <style>) twice; clearDirtyState();
    // dirtyByIndex(screen, -2, 3) → all false (transcript rows -2,-1 and screen row 0 are clean)
    // then setChar on screen rows 1 and 2 → dirtyByIndex(screen, 0, 3) has 1 and 2 true, and
    //      dirtyByIndex(screen, -2, 3) is still all false (it queries rows -2,-1,0, not 1,2)
}
```

Note: `TerminalBuffer.externalToInternalRow` throws for rows below `-mActiveTranscriptRows`, so the negative-topRow case must build real transcript rows first (the two `scrollDownOneLine` calls). Use whatever style constant the WS1 tests use (e.g. `TextStyle.NORMAL` or `TextStyle.encode(0, 0, 0)` — copy from `terminal-emulator/src/test/.../ScreenBufferTest.java`).

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :terminal-view:testDebugUnitTest
```
Expected: compile failure (`dirtyByIndex` missing) or `returnDefaultValues` stub errors until `testOptions` is added.

- [ ] **Step 3: Implement**

Add the static method with the mapping in the body and a javadoc sentence stating it is the caller-facing external-row mapping. Keep the class free of Android imports (`TerminalBuffer` is a core class from `:terminal-emulator`, which is on the `api` classpath).

- [ ] **Step 4: Verify the module's tests pass**

```bash
./gradlew :terminal-view:testDebugUnitTest
```
Expected: 14 existing + the new buffer tests, all green.

- [ ] **Step 5: Commit**

```bash
git add terminal-view/src/main/java/com/termux/view/RowInvalidation.java terminal-view/src/test/java/com/termux/view/RowInvalidationBufferTest.java terminal-view/build.gradle
git commit -S -m "Added(view): Map buffer dirty rows to viewport indexes"
```

---

### Task 4: TerminalView protocol wiring

**Files:**
- Modify: `terminal-view/src/main/java/com/termux/view/TerminalView.java` (`attachSession` ~290, `updateSize` ~985, `onScreenUpdated` ~459, `onDraw` ~1021)

**Interfaces:**
- Consumes: `RowInvalidation.decide(...)`, `RowInvalidation.dirtyByIndex(...)` (Task 3), `TerminalRenderer.render(..., firstScreenRow, lastScreenRowExclusive)` (Task 2), WS1's `getChangeCounter()`/`clearDirtyState()`/`isRowDirty(int)`.
- Produces: the protocol behaviour of spec §2 — nothing other tasks consume.

- [ ] **Step 1: Add the state fields**

```java
private long mLastSeenChangeCounter = -1L;   // forces FULL on the first draw
private int mLastCursorRow = -1, mLastCursorCol = -1;
private boolean mLastReverseVideo;
private final Rect mClipBounds = new Rect(); // reusable, avoids per-frame allocation
```

- [ ] **Step 2: Reset on rebind**

In `attachSession(...)` and in `updateSize()` wherever the emulator is (re)bound: `mLastSeenChangeCounter = -1L;` (and the cursor fields to their sentinels), so a rebind always repaints fully.

- [ ] **Step 3: Replace the bare `invalidate()` in `onScreenUpdated()`**

Keep every existing early return, the scroll/autoscroll/`setTopRow` bookkeeping, and `setContentDescription`; capture `final int topRowBefore = mTopRow;` at the top of the method and replace only the `invalidate()` at ~498 with:

```java
TerminalBuffer screen = mEmulator.getScreen();
boolean topRowChanged = (mTopRow != topRowBefore);
boolean cursorOrVideoChanged = mEmulator.getCursorRow() != mLastCursorRow
    || mEmulator.getCursorCol() != mLastCursorCol
    || mEmulator.isReverseVideo() != mLastReverseVideo;
RowInvalidation.Decision d = RowInvalidation.decide(mLastSeenChangeCounter, screen.getChangeCounter(),
    topRowChanged || cursorMoved..., ...);
```

Concretely: treat the cursor/reverse-video change as a third force-FULL input — either extend `decide`'s `topRowChanged` parameter usage by passing `topRowChanged || cursorOrReverseVideoChanged` (preferred: no signature change), or add an explicit parameter to `decide` **with its own test** if you choose that route. Then `d.kind == RANGE` → `invalidate(0, d.topPx, getWidth(), d.bottomPx)`, `FULL` → `invalidate()`, `NONE` → no call. Flags come from `RowInvalidation.dirtyByIndex(screen, mTopRow, mEmulator.mRows)` — never a hand-rolled loop.

- [ ] **Step 4: `onDraw()` uses the clip-derived band and consumes the dirty state once**

```java
canvas.getClipBounds(mClipBounds);   // empty/unbounded → full band
int first = Math.max(mTopRow, mTopRow + Math.floorDiv(mClipBounds.top, mRenderer.mFontLineSpacing) - 1);
int last  = Math.min(mTopRow + mEmulator.mRows,
                     mTopRow + (int) Math.ceil(mClipBounds.bottom / (float) mRenderer.mFontLineSpacing) + 1);
mRenderer.render(mEmulator, canvas, mTopRow, sel[0], sel[1], sel[2], sel[3], first, last);
mLastSeenChangeCounter = mEmulator.getScreen().getChangeCounter();
mEmulator.getScreen().clearDirtyState();
mLastCursorRow = mEmulator.getCursorRow();
mLastCursorCol = mEmulator.getCursorCol();
mLastReverseVideo = mEmulator.isReverseVideo();
```

The ±1 row slack is deliberate (cheap insurance against the off-by-one in the pixel↔row mapping; redrawing a clean row is harmless). Selection-handle drawing and the `mEmulator == null` branch stay where they are.

- [ ] **Step 5: Verify**

```bash
./gradlew :terminal-view:testDebugUnitTest
./gradlew :terminal-emulator:test
```
Expected: green (the protocol itself has no unit test — `TerminalView` is an Android `View`; spec §8 assigns it to the device checklist).

- [ ] **Step 6: Commit**

```bash
git add terminal-view/src/main/java/com/termux/view/TerminalView.java
git commit -S -m "Changed(view): Use dirty-region invalidation in the terminal view"
```

---

### Task 5: Distinct underline styles

**Files:**
- Modify: `terminal-view/src/main/java/com/termux/view/TerminalRenderer.java` (`drawTextRun`)

**Interfaces:**
- Consumes: `TextStyle.decodeUnderlineStyle(textStyle)` (WS1). Run bounds and colors already known.

- [ ] **Step 1: Add underline drawing path**

In `drawTextRun`, after resolving colors and before/after text: get `int us = TextStyle.decodeUnderlineStyle(textStyle);` boolean `hasUnderlineEffect = (effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0;`. If `!hasUnderlineEffect`, do nothing special. Else: if `us == TextStyle.UNDERLINE_STYLE_SINGLE || us == 0` → `mTextPaint.setUnderlineText(true)`. Else → `mTextPaint.setUnderlineText(false)` and draw custom path with stroke paint in `foreColor`, scaled by the existing matrix (`savedMatrix` block). For DOUBLE: two lines; CURLY: zigzag; DOTTED/DASHED: dash effects. Use density-agnostic small offsets from baseline (e.g. `y - descent/4` etc., derived from `mFontAscent/mFontLineSpacing`).

- [ ] **Step 2: Compile and quick sanity**

`./gradlew :terminal-view:compileDebugJavaWithJavac` should succeed. No new tests required (device visual checklist covers 4:1–4:5).

- [ ] **Step 3: Commit**

```bash
git add terminal-view/src/main/java/com/termux/view/TerminalRenderer.java
git commit -S -m "Changed(view): Render distinct underline styles (double/curly/dotted/dashed)"
```

### Task 6: Branch gate

**Files:** none new.

- [ ] **Step 1: Run full test suite**

```bash
./gradlew test
```
Expected: `:terminal-emulator` (208), `:app` unit tests, `:terminal-view` (`RowInvalidationTest` + `RowInvalidationBufferTest`) all green. No diff outside `terminal-view/` (+ this plan's docs).

- [ ] **Step 2: Verify scope**

```bash
git diff --stat $(git merge-base master HEAD)..HEAD
```
Expected: only `terminal-view/` paths changed (new `RowInvalidation.java` + 2 test classes, modified `TerminalView.java`, `TerminalRenderer.java`, `build.gradle` testOptions), plus `docs/`. `:terminal-emulator` and `:termux-shared` show 0 lines.

- [ ] **Step 3: Device sanity (document results)**

Run the **7-item** checklist from spec §8: heavy output, htop-style full redraw, scrollback drag, selection while output runs, underlines 4:1–4:5 distinct, font-size change/resize/rotation, cursor move + reverse-video toggle — note any issues. (This is the acceptance evidence; no code changes expected.)

- [ ] **Step 4: Commit gate note (optional)**

No code changes. Push branch if requested.