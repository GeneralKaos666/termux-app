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

### Task 2: TerminalView consumes dirty state; row-range render

**Files:**
- Modify: `terminal-view/src/main/java/com/termux/view/TerminalView.java` (add `mLastSeenChangeCounter`, change `onScreenUpdated`, `onDraw`, `attachSession/updateSize` rebind)
- Modify: `terminal-view/src/main/java/com/termux/view/TerminalRenderer.java` (add row-range overload; keep existing signature delegating)
- Modify: `terminal-view/build.gradle` (add test-scope JUnit if not already present — it already is in the file shown, but confirm; Task 1 added test source; no change needed unless missing)

**Interfaces:**
- Consumes: WS1 API on `TerminalBuffer` — `getChangeCounter()`, `isRowDirty(int)`, `clearDirtyState()`; existing view state (`mTopRow`, `mEmulator`, `getWidth()`, `getHeight()`).
- Produces: clip-derived row range passed to renderer; targeted invalidates.

- [ ] **Step 1: Extend Renderer with row-range overload**

In `TerminalRenderer.java`: add `public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow, int selectionY1,int selectionY2,int selectionX1,int selectionX2, int firstRow, int lastRow)`. The existing `render` method (line ~61) becomes `public final void render(...same 7 args...) { render(..., 0, mEmulator.mRows); }` delegating. In the loop, replace the row range `for (int row = topRow; row < endRow; row++)` logic? Wait — existing code loops `int row = topRow; row < endRow; row++` over screen rows (topRow is the top visible screen row). For row-range rendering, we must render only that subrange of those visible rows: intersect with `[firstRow, lastRow)` (exclusive) relative to the visible band? Or pass screen-row bounds: easier to pass `firstScreenRow` and `lastScreenRowExclusive` (clamped to `[topRow, endRow)`). So overload takes `int firstScreenRow, int lastScreenRowExclusive` and loops `for (int row = Math.max(topRow,firstScreenRow); row < Math.min(endRow,lastScreenRowExclusive); row++)` — or simpler, have the caller pass the full visible range and also the clip-derived screen-row bounds intersected appropriately. But cleaner: in `onDraw`, compute `first = Math.max(topRow, clipFirstScreenRow)`, `last = Math.min(endRow, clipLastScreenRowExclusive)`. So the overload signature is `render(..., int firstScreenRow, int lastScreenRowExclusive)` and the loop becomes `for (int row = firstScreenRow; row < lastScreenRowExclusive; row++)` with the caller ensuring it's within bounds. Keep the existing method delegating to `(topRow, endRow)`.

- [ ] **Step 2: Add state to TerminalView**

Fields: `private long mLastSeenChangeCounter = -1L;` (package-private not needed; private). In `attachSession` (after `mEmulator = null;`) and after emulator changes in `updateSize` (when `mEmulator` becomes non-null or session changes), set `mLastSeenChangeCounter = -1L` (session rebind forces full). Also in `setTerminalSession` path? `attachSession` is the entry point; `updateSize` also rebinds emulator — reset there too.

- [ ] **Step 3: Rewrite `onScreenUpdated()` protocol**

Don't blindly `invalidate()`. Capture pre-state: `int oldTopRow = mTopRow;` (or remember whether it changed). Do existing scroll/autoscroll bookkeeping first (which may call `setTopRow`), then evaluate: get `screen = mEmulator.getScreen()`, `currentCounter = screen.getChangeCounter()`. Let `topRowChanged = (mTopRow != oldTopRow)` (or track). If `currentCounter != mLastSeenChangeCounter` OR `topRowChanged` → full `invalidate()`. Else find first/last dirty among visible `0..mEmulator.mRows-1`: for each, `row = topRow + i` mapped? visible are screen rows `topRow <= r < topRow + mRows` in the view's coordinate space relative to buffer; `isRowDirty` takes **external row**. So for `i = 0; i < mRows; i++`, `ext = topRow + i` (but note: `topRow` can be negative = history). But `isRowDirty` is called on external rows present in the buffer's view? WS1 states `isRowDirty(int externalRow)` returns false when row is null — so it's fine for history rows too. Find span over `ext in [topRow, topRow+mRows)` that are dirty. If any dirty → compute band: `firstExt = first dirty`, `lastExt = last dirty`, `topPx = (firstExt - mTopRow)*mRenderer.mFontLineSpacing`, `bottomPx = (lastExt - mTopRow + 1)*mRenderer.mFontLineSpacing`, clamp to `[0, getHeight()]`; `invalidate(0, topPx, getWidth(), bottomPx)`. Else → do **not** invalidate. Keep `setContentDescription(getText())` unconditionally. Preserve all existing early returns.

- [ ] **Step 4: Rewrite `onDraw()` to use clip + row-range + draw-time clearing**

Read clip: `Rect clip = canvas.getClipBounds();` (if null, treat as full). Compute visible range: `int topRowVis = mTopRow`, `int endRowVis = topRowVis + mEmulator.mRows`. Derive `first = topRowVis`, `last = endRowVis`; intersect with clip rows: `clipTopRow = (int)Math.floor(clip.top / (double)mRenderer.mFontLineSpacing + mTopRow)` etc., but easier to convert clip Y to screen rows: `first = Math.max(topRowVis, (int)Math.floor(clip.top / (float)mRenderer.mFontLineSpacing) + mTopRow)` and `last = Math.min(endRowVis, (int)Math.ceil(clip.bottom / (float)mRenderer.mFontLineSpacing) + mTopRow)` (or just use the floor+ceiling to be safe). If clip is empty, fall back to full. Call the row-range renderer with `(first, last)`. After render, set `mLastSeenChangeCounter = mEmulator.getScreen().getChangeCounter(); mEmulator.getScreen().clearDirtyState();`. Keep the selection-handle drawing after that (it doesn't depend on buffer dirty state).

- [ ] **Step 5: Handle rebinds**

In `attachSession(session)`: when attaching, after setting `mEmulator=null`, `setTopRow(0,false)`, then `updateSize()` will set emulator; also set `mLastSeenChangeCounter = -1L` before/after. In `updateSize()`, when emulator becomes non-null (or size changed causing emulator rebind), set `mLastSeenChangeCounter = -1L`. Also in the early return paths where `mEmulator==null`, nothing to clear.

- [ ] **Step 6: Verify protocol behavior via targeted checks (no new test class for View yet; rely on helper + compile)**

Run full view tests (new helper + existing nothing): `./gradlew :terminal-view:testDebugUnitTest` — should compile and pass (only RowInvalidationTest exists so far).

- [ ] **Step 7: Commit**

```bash
git add terminal-view/src/main/java/com/termux/view/TerminalView.java terminal-view/src/main/java/com/termux/view/TerminalRenderer.java
git commit -S -m "Changed(view): Use dirty-region invalidation and row-range rendering"
```

### Task 3: Distinct underline styles

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

### Task 4: Branch gate

**Files:** none new.

- [ ] **Step 1: Run full test suite**

```bash
./gradlew test
```
Expected: `:terminal-emulator` (208), `:app` unit tests, `:terminal-view` (RowInvalidationTest) all green. No diff outside `terminal-view/`.

- [ ] **Step 2: Verify scope**

```bash
git diff --stat $(git merge-base master HEAD)..HEAD
```
Expected: only `terminal-view/` paths changed (new `RowInvalidation.java` + test, modified `TerminalView.java`, `TerminalRenderer.java`; possibly `build.gradle` unchanged). `:terminal-emulator` and `:termux-shared` show 0 lines.

- [ ] **Step 3: Device sanity (document results)**

Run the 6-item checklist from spec §8: heavy output, htop-style full redraw, scrollback drag, selection while output runs, underlines 4:1–4:5 distinct, font-size change/resize/rotation — note any issues. (This is the acceptance evidence; no code changes expected.)

- [ ] **Step 4: Commit gate note (optional)**

No code changes. Push branch if requested.