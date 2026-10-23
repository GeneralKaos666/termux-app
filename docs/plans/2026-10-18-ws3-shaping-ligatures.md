# Terminal engine upgrade — workstream 3: shaped-run grid snapping and ligatures (implementation plan)

**Status:** implemented (`d5bb6baa`, `94d29f2c`, `30d9a9e6`); the §-device checks are outstanding.
The task list below is the original execution flow and is left unchecked.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the renderer draw whole runs as platform-shaped text (so ligatures form and Indic/Arabic shaping survives) without letting a glyph leave its cell: a pure `ShapedRunPolicy` reads the platform's shaped advances and decides whether a run lands on the cell grid; a run that does not is re-drawn span by span, each span scaled onto its own cells. A new ligature toggle (default on, in the appearance screen) drives `Paint.setFontFeatureSettings` once per renderer.

**Architecture:** Approach 1 from the spec — measurement-only verification plus a per-cell fallback. `TerminalRenderer`'s single glyph call becomes a per-span loop: for every span the policy is asked for the first cell boundary the shaped advances do not hit; a span whose shaped total is off its cells is condensed with a canvas scale. Verification is a capability of API 29+: `Paint.getTextRunAdvances` does not exist below it, so `RunAdvancesSource.forPaint` returns `null` there and each run draws as one shaped span (the user's decision, spec §4). Background, cursor and custom underline stay drawn once per run and cell-exact.

**Tech Stack:** Java 8 (`:terminal-view`), Kotlin + Compose M3 (`:app`), JUnit 4.13.2 with `junit.framework.TestCase`-style tests in `:terminal-view` (plain JVM, no Robolectric) and JUnit4 + Robolectric in `:app`. No new dependency.

**Spec:** [`../specs/2026-10-18-ws3-shaping-ligatures-design.md`](../specs/2026-10-18-ws3-shaping-ligatures-design.md)

## Global Constraints

- **API gate.** `Paint.getTextRunAdvances(char[], int, int, int, int, boolean, float[], int)` is **API 29**, not 21 (spec §4, corrected 2026-10-18). `RunAdvancesSource.forPaint` returns the shaped source on API 29+ and `null` below; a null source means one shaped span per run and no policy consultation. The ligature toggle applies on every API level. The gate has no host test (`Build.VERSION.SDK_INT` is stubbed to 0 in `:terminal-view` tests), so it is covered by inspection and the spec's §7 legacy device check.
- **No new dependency.** No third-party library, no JNI, no HarfBuzz.
- **No `:terminal-emulator` or `:termux-shared` change.** Both must stay empty in `git diff --stat`.
- **No new `TerminalSessionClient`/`TerminalViewClient` methods.** `TerminalView` gains a plain setter.
- **No public API break.** `TerminalRenderer`'s public constructor keeps its signature as a delegating overload; the `render(...)` overloads are untouched.
- **Grid honesty.** Background, cursor and custom underline are drawn once per run and stay cell-exact; only glyphs are ever horizontally scaled, and only within a span. The one incidental difference: a *decorated* underline of a run whose glyph total is off-grid is no longer horizontally stretched with that run (its endpoints are unchanged; its dash pattern is now the same as every other run's).
- **Java 8 source level** (`terminal-view/build.gradle`: `VERSION_1_8`), anonymous classes over lambdas (the module has no lambdas today), 4-space indent, LF.
- **No pixel assertions.** Robolectric cannot rasterize on this host (workstream 5); pixel truth lives in the spec's §7 device checklist.
- **Protocol:** one Conventional Commit per task, capitalized type (`Added`/`Changed`), GPG-signed, gated on a green `./gradlew test`.

## Review Focus

1. **The API gate.** Below API 29 (and in every host test) no advances exist, so nothing may be verified and each run must still draw as one shaped span. *Test:* `testNoShapedAdvancesDrawsOneSpan` (Task 2).
2. **Degenerate geometry never throws and never scales by a non-finite factor.** Empty advance arrays, non-finite entries, a non-positive or NaN cell width, `mFontWidth <= 0` and a zero measured span total. *Tests:* `ShapedRunPolicyTest#testDegenerateGeometryIsMisalignedOrSkipped`, `#testNonFiniteAdvancesAreMisaligned`, `testWideCodePointIsStillDrawnPerCharScaled` (Task 1/2).
3. **The split walk is column-based and exact.** Only column counts are known at a split, so spans are sliced by walking code points with `WcWidth` (combining chars stay with their base char). Expected: every glyph of the run is drawn exactly once, at its own cell. *Test:* `testMisalignedRunIsDrawnSpanBySpan`, `testOneCellRemainderNeverConsultsThePolicy` (Task 2).
4. **The scale branch reads the span's own shaped total, and the run's rectangles are not affected.** A run whose only misalignment is its total width is condensed by its single span; the background rect is still drawn once per run. *Test:* `testOffGridSpanIsCondensedOntoItsCells` (asserts `x * scale == cell origin`), `testRunBackgroundIsDrawnOncePerRunEvenWhenSplit` (Task 2).
5. **The per-codepoint `fontWidthMismatch` path still works** (pre-existing behaviour, runs broken per char and each char condensed), and the ligature toggle is a construction setting read once per `checkForFontAndColors()`. *Tests:* `testWideCodePointIsStillDrawnPerCharScaled`, `TerminalLigatureTest`, the `:app` store/state tests (Task 2/3). The Java read site itself is inspection-only, like the other appearance read sites.

---

### Task 1: `ShapedRunPolicy` + the `RunAdvancesSource` seam

**Files:**
- Create: `terminal-view/src/main/java/com/termux/view/ShapedRunPolicy.java` (package-private, no Android imports)
- Create: `terminal-view/src/main/java/com/termux/view/RunAdvancesSource.java` (package-private)
- Create: `terminal-view/src/test/java/com/termux/view/ShapedRunPolicyTest.java` (plain `junit.framework.TestCase`)

**Interfaces:**
- Consumes: a shaped advance array, the number of valid entries, the run's column count and the cell width in pixels.
- Produces:
  - `static int ShapedRunPolicy.firstMisalignedCell(float[] advances, int advanceCount, int runColumns, float cellWidth)` — `-1` when every cell boundary (including the run's own total width) is hit, otherwise the first (1-based) cell boundary that no glyph boundary coincides with, and `-1` for `runColumns < 2` (a one-cell span is never verified).
  - `interface RunAdvancesSource { int advances(char[] text, int start, int count, float[] out); }` and `static RunAdvancesSource forPaint(Paint paint)` — fills `out[0..n)` and returns `n`, or is `null` when the platform cannot report shaped advances (API < 29).
- Style precedent: `terminal-view/src/main/java/com/termux/view/RowInvalidation.java` and `RowInvalidationTest.java` (package-private helper, no Android imports, JUnit 3-style tests).

- [ ] **Step 1: Write the failing policy tests**

Create `terminal-view/src/test/java/com/termux/view/ShapedRunPolicyTest.java`:

```java
package com.termux.view;

import junit.framework.TestCase;

/**
 * Unit tests for {@link ShapedRunPolicy}: given the shaped advance array the platform reported for a run, which
 * cell boundary (if any) the run does not land on. Advances are in pixels and a cell is {@code cellWidth} wide.
 */
public class ShapedRunPolicyTest extends TestCase {

    public void testAlignedRunHasNoMisalignedCell() {
        assertEquals(-1, ShapedRunPolicy.firstMisalignedCell(new float[]{10f, 10f, 10f}, 3, 3, 10f));
    }

    public void testLigatureAdvanceFailsAtTheCellItsClusterSkips() {
        // A 10px glyph then a 37px cluster in a 3-cell run: glyph boundaries at 10 and 47, cell boundaries at
        // 10, 20 and 30. The 20px boundary is the first one no glyph boundary coincides with.
        assertEquals(2, ShapedRunPolicy.firstMisalignedCell(new float[]{10f, 37f}, 2, 3, 10f));
    }

    public void testFirstBoundaryMissIsReported() {
        assertEquals(1, ShapedRunPolicy.firstMisalignedCell(new float[]{15f}, 1, 3, 10f));
    }

    public void testRunWhoseOnlyMissIsItsTotalReportsTheLastBoundary() {
        // Boundaries 10 and 20: the glyph boundary at 10 hits, the one at 20 does not, and 20 is also the run's
        // total width, so the caller draws one span and condenses it.
        assertEquals(2, ShapedRunPolicy.firstMisalignedCell(new float[]{10f, 10.5f}, 2, 2, 10f));
    }

    public void testBoundaryWithinToleranceIsAligned() {
        // cellWidth 8 -> tolerance 0.08 (1% of a cell, the same spirit as the renderer's per-codepoint check).
        assertEquals(-1, ShapedRunPolicy.firstMisalignedCell(new float[]{8f, 8f + 0.08f}, 2, 2, 8f));
    }

    public void testBoundaryJustOutsideToleranceIsMisaligned() {
        assertEquals(2, ShapedRunPolicy.firstMisalignedCell(new float[]{8f, 8f + 0.5f}, 2, 2, 8f));
    }

    public void testSeveralGlyphsPerCellStillHitEveryBoundary() {
        // Two 5px glyphs per 10px cell: the cell boundaries are hit by glyph boundaries inside the cells.
        assertEquals(-1, ShapedRunPolicy.firstMisalignedCell(new float[]{5f, 5f, 5f, 5f}, 4, 2, 10f));
    }

    public void testPerGlyphAndPerClusterReportingAgree() {
        // The same geometry, reported once per glyph and once per cluster, must get the same verdict.
        assertEquals(-1, ShapedRunPolicy.firstMisalignedCell(new float[]{5f, 5f, 10f}, 3, 2, 10f));
        assertEquals(-1, ShapedRunPolicy.firstMisalignedCell(new float[]{10f, 10f}, 2, 2, 10f));
        assertEquals(2, ShapedRunPolicy.firstMisalignedCell(new float[]{5f, 5f, 30f}, 3, 2, 10f));
        assertEquals(2, ShapedRunPolicy.firstMisalignedCell(new float[]{10f, 30f}, 2, 2, 10f));
    }

    public void testFloatNoiseOfALongRunStaysAligned() {
        // 200 cells of two glyphs summing to 13.999999... per cell: the boundary at 14 is off by float noise only.
        float[] advances = new float[400];
        for (int i = 0; i < advances.length; i += 2) {
            advances[i] = 7.02f;
            advances[i + 1] = 6.98f;
        }
        assertEquals(-1, ShapedRunPolicy.firstMisalignedCell(advances, advances.length, 200, 7f));
    }

    public void testNoEntriesWrittenIsMisaligned() {
        assertEquals(1, ShapedRunPolicy.firstMisalignedCell(new float[0], 0, 3, 10f));
    }

    public void testStaleTailBeyondTheEntryCountIsIgnored() {
        // Only [0, advanceCount) is read, so a poisoned entry past the count cannot change the verdict.
        assertEquals(-1, ShapedRunPolicy.firstMisalignedCell(new float[]{10f, 10f, 999f}, 2, 2, 10f));
    }

    public void testNonFiniteAdvancesAreMisaligned() {
        assertEquals(2, ShapedRunPolicy.firstMisalignedCell(new float[]{10f, Float.NaN}, 2, 2, 10f));
        assertEquals(2, ShapedRunPolicy.firstMisalignedCell(new float[]{10f, Float.POSITIVE_INFINITY}, 2, 2, 10f));
    }

    public void testZeroAndNegativeEntriesNeverThrow() {
        // A leading zero-width entry (a combining mark reported on its own) leaves every boundary hit.
        assertEquals(-1, ShapedRunPolicy.firstMisalignedCell(new float[]{0f, 10f, 10f, 10f, 10f}, 5, 4, 10f));
        // A negative advance cannot coincide with a boundary; the verdict is a misaligned cell, not an exception.
        assertEquals(2, ShapedRunPolicy.firstMisalignedCell(new float[]{-10f, 10f, 10f}, 3, 3, 10f));
    }

    public void testDegenerateGeometryIsMisalignedOrSkipped() {
        assertEquals(1, ShapedRunPolicy.firstMisalignedCell(new float[]{10f, 10f}, 2, 2, 0f));
        assertEquals(1, ShapedRunPolicy.firstMisalignedCell(new float[]{10f, 10f}, 2, 2, Float.NaN));
        assertEquals(-1, ShapedRunPolicy.firstMisalignedCell(new float[]{10f, 10f}, 2, 1, 10f));
    }
}
```

- [ ] **Step 2: Run the tests and watch them fail**

```bash
./gradlew :terminal-view:testDebugUnitTest --tests "com.termux.view.ShapedRunPolicyTest"
```

Expected: compilation failure (`ShapedRunPolicy` does not exist yet).

- [ ] **Step 3: Write the policy**

Create `terminal-view/src/main/java/com/termux/view/ShapedRunPolicy.java`:

```java
package com.termux.view;

/**
 * Decides, from the shaped advances the platform reported for one run, whether the run lands on the terminal cell
 * grid. A run that does (and any run when the platform cannot report advances) is drawn as one shaped span, so
 * ligatures and complex-script shaping survive; a run that does not is cut at the first cell boundary its glyph
 * boundaries miss, and the pieces are drawn on their own so no glyph can drift into the next cell.
 *
 * The decision is boundary-based rather than cluster-identity-based, so it does not care whether the platform
 * reports one entry per glyph, per cluster or per character - only the cumulative advances are read. It reads no
 * Android types and holds no state.
 */
final class ShapedRunPolicy {

    /** Tolerance as a fraction of one cell; the same 1% spirit as the renderer's per-codepoint width check. */
    static final float TOLERANCE_FRACTION = 0.01f;

    private ShapedRunPolicy() {
    }

    /**
     * @param advances     the run's shaped advances in pixels; only {@code [0, advanceCount)} is read
     * @param advanceCount how many entries {@code advances} holds
     * @param runColumns   how many cells the run spans
     * @param cellWidth    the width of one cell in pixels
     * @return {@code -1} when every cell boundary (including the run's total width) coincides with a glyph
     * boundary within tolerance, {@code -1} for a run of fewer than two cells (nothing to verify), otherwise the
     * first 1-based cell boundary that no glyph boundary coincides with. Degenerate input (no entries, a
     * non-positive or non-finite cell width) is reported as cell 1, that is, misaligned.
     */
    static int firstMisalignedCell(float[] advances, int advanceCount, int runColumns, float cellWidth) {
        if (runColumns < 2) return -1;
        if (advanceCount <= 0) return 1;
        if (!(cellWidth > 0f)) return 1;

        final float tolerance = TOLERANCE_FRACTION * cellWidth;
        float position = 0f;
        int entry = 0;
        for (int cell = 1; cell <= runColumns; cell++) {
            final float boundary = cell * cellWidth;
            boolean boundaryHit = false;
            while (true) {
                if (Math.abs(position - boundary) <= tolerance) {
                    boundaryHit = true;
                    break;
                }
                // Walk glyph boundaries up to the first one at or past this cell boundary; entries are consumed
                // monotonically, so the whole run costs one pass.
                if (position > boundary || entry >= advanceCount) break;
                position += advances[entry];
                entry++;
            }
            if (!boundaryHit) return cell;
        }
        return -1;
    }
}
```

Do not relax an assertion to make this pass: `testRunWhoseOnlyMissIsItsTotalReportsTheLastBoundary` pins that the
run's *total* width is a cell boundary (a run that is only too wide or too narrow is condensed as one span by the
caller), `testBoundaryWithinToleranceIsAligned` pins the 1% tolerance's inclusivity, and
`testStaleTailBeyondTheEntryCountIsIgnored` pins that nothing past `advanceCount` is read.

- [ ] **Step 4: Run the policy tests**

```bash
./gradlew :terminal-view:testDebugUnitTest --tests "com.termux.view.ShapedRunPolicyTest"
```

Expected: green.

- [ ] **Step 5: Add the `RunAdvancesSource` seam**

Create `terminal-view/src/main/java/com/termux/view/RunAdvancesSource.java`:

```java
package com.termux.view;

import android.graphics.Paint;
import android.os.Build;

/**
 * The shaped advances of one drawn run, behind a seam so the renderer's grid-snap decisions can be tested without
 * a font. The production implementation delegates to {@link Paint#getTextRunAdvances}.
 */
interface RunAdvancesSource {

    /**
     * Writes the shaped advances of {@code text[start, start + count)} to {@code out[0..n)} and returns {@code n}.
     * Only {@code [0, n)} is meaningful to the caller; the rest of {@code out} must be ignored.
     */
    int advances(char[] text, int start, int count, float[] out);

    /**
     * The platform source for {@code paint}, or {@code null} when the platform cannot report shaped per-character
     * advances. {@code Paint.getTextRunAdvances} is API 29; below it a run cannot be verified against the grid, so
     * the renderer draws each run as a single shaped span and never consults {@link ShapedRunPolicy} (spec, §4).
     */
    static RunAdvancesSource forPaint(final Paint paint) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        return new RunAdvancesSource() {
            @Override
            public int advances(char[] text, int start, int count, float[] out) {
                paint.getTextRunAdvances(text, start, count, start, count, false, out, 0);
                return count;
            }
        };
    }
}
```

The wrapper writes one entry per character (the platform assigns a cluster's total advance to its first logical
character and gives a trailing low surrogate `0`). The policy does not depend on that layout; the seam only
promises "`n` written, `[0, n)` filled".

- [ ] **Step 6: Run the module suite (compiles the new source) and commit**

```bash
./gradlew :terminal-view:testDebugUnitTest
git add terminal-view/src/main/java/com/termux/view/ShapedRunPolicy.java \
        terminal-view/src/main/java/com/termux/view/RunAdvancesSource.java \
        terminal-view/src/test/java/com/termux/view/ShapedRunPolicyTest.java
git commit -S -m "Added(view): Add the shaped-run grid-snap policy and advance source"
```

---

### Task 2: Draw a run span by span when shaping misses the grid

**Files:**
- Modify: `terminal-view/src/main/java/com/termux/view/TerminalRenderer.java` (field 29; constructor 48–71; row loop 104–216; `drawTextRun` 219–328)
- Create: `terminal-view/src/test/java/com/termux/view/TerminalRendererRunTest.java`

**Interfaces:**
- Consumes: `RunAdvancesSource` and `ShapedRunPolicy.firstMisalignedCell` (Task 1).
- Produces: package-private
  `TerminalRenderer(int textSize, Typeface typeface, Paint textPaint, RunAdvancesSource runAdvances)` (the test seam; Task 3 adds a `boolean ligatures` argument). `drawTextRun` loses its `mes` parameter, the row loop loses `measuredWidthForRun`, and the scale block moves into the per-span draw.
- Unchanged externally: `render(...)` overloads; background, cursor and underline drawn once per run, cell-exact.

- [ ] **Step 1: Write the failing call-sequence tests**

Create `terminal-view/src/test/java/com/termux/view/TerminalRendererRunTest.java`. A stub paint with a 10px cell
and a scripted advance source make every path deterministic — no font, no Robolectric, no API-level dependence
(host tests take the `drawText` branch because `Build.VERSION.SDK_INT` is stubbed to 0).

```java
package com.termux.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;

import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Call-sequence tests for the renderer's glyph stage: how many glyph draws a run produces, where they start, and
 * when the canvas is scaled onto the cells. Every assertion is on recorded calls, never on pixels.
 */
public class TerminalRendererRunTest extends TestCase {

    private static final float CELL_WIDTH = 10f;

    private static final class StubOutput extends TerminalOutput {
        @Override public void write(byte[] data, int offset, int count) { }
        @Override public void titleChanged(String oldTitle, String newTitle) { }
        @Override public void onCopyTextToClipboard(String text) { }
        @Override public void onPasteTextFromClipboard() { }
        @Override public void onBell() { }
        @Override public void onColorsChanged() { }
    }

    /** A paint whose metrics are fixed: "X" and every single ASCII char measure {@link #cellWidth}. */
    private static class StubPaint extends Paint {
        /** The advance of one cell. */
        float cellWidth = CELL_WIDTH;
        /** A non-ASCII char whose single-char advance is {@link #wideCharWidth} (the mismatch path). */
        char wideChar = 0;
        float wideCharWidth = 2 * CELL_WIDTH;
        /** When set (>= 0), the advance reported for a multi-char slice starting at this char index. */
        int spanAdvanceStartChar = -1;
        float spanAdvance = -1f;

        @Override public float getFontSpacing() { return 20f; }
        @Override public float ascent() { return -15f; }
        @Override public float measureText(String text) { return cellWidth; }
        @Override public float measureText(CharSequence text, int start, int end) { return cellWidth; }
        @Override public float measureText(char[] text, int index, int count) {
            if (count == 1) return (text[index] == wideChar) ? wideCharWidth : cellWidth;
            if (spanAdvance >= 0f && index == spanAdvanceStartChar) return spanAdvance;
            return cellWidth * count;
        }
    }

    /** A canvas that records glyph draws as {startCharIndex, charCount, x} plus the transform calls. */
    private static final class RecordingCanvas extends Canvas {
        final List<String> calls = new ArrayList<>();
        final List<float[]> glyphs = new ArrayList<>();
        final List<Float> scales = new ArrayList<>();

        @Override public void drawText(char[] text, int index, int count, float x, float y, Paint paint) {
            calls.add("text");
            glyphs.add(new float[]{index, count, x});
        }

        @Override public void drawTextRun(char[] text, int index, int count, int contextIndex, int contextCount,
                                         float x, float y, boolean isRtl, Paint paint) {
            calls.add("textRun");
            glyphs.add(new float[]{index, count, x});
        }

        @Override public void drawRect(float left, float top, float right, float bottom, Paint paint) {
            calls.add("rect");
        }

        @Override public void scale(float sx, float sy) {
            calls.add("scale");
            scales.add(sx);
        }

        @Override public int save() {
            calls.add("save");
            return 0;
        }

        @Override public void restore() {
            calls.add("restore");
        }
    }

    /** Advances scripted per consultation, so the policy sees exactly what a test wants it to see. */
    private static final class ScriptedAdvances implements RunAdvancesSource {
        private final float[][] script;
        private int call;
        /** How often the renderer asked, whether or not the script had an entry for it. */
        int calls;

        ScriptedAdvances(float[]... script) {
            this.script = script;
        }

        @Override public int advances(char[] text, int start, int count, float[] out) {
            calls++;
            assertTrue("source consulted more often than scripted: " + calls, call < script.length);
            float[] advances = script[call++];
            System.arraycopy(advances, 0, out, 0, advances.length);
            return advances.length;
        }
    }

    private TerminalEmulator mTerminal;
    private StubPaint mPaint;
    private RecordingCanvas mCanvas;

    @Override protected void setUp() {
        mTerminal = new TerminalEmulator(new StubOutput(), 10, 3, 10, 20, 3, null);
        mPaint = new StubPaint();
        mCanvas = new RecordingCanvas();
    }

    private void enter(String string) {
        byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
        mTerminal.append(bytes, bytes.length);
    }

    /** Renders row 0. The row is space-filled to the full width, so its final run covers all 10 cells. */
    private void render(RunAdvancesSource advances) {
        TerminalRenderer renderer = new TerminalRenderer(14, Typeface.MONOSPACE, mPaint, advances);
        renderer.render(mTerminal, mCanvas, 0, -1, -1, -1, -1, 0, 1);
    }

    private static float[] cells(int count) {
        float[] advances = new float[count];
        for (int i = 0; i < count; i++) advances[i] = CELL_WIDTH;
        return advances;
    }

    public void testAlignedRunDrawsOneShapedSpan() {
        enter("===\r\n");
        render(new ScriptedAdvances(new float[]{10f, 10f, 10f, 10f, 10f, 10f, 10f, 10f, 10f, 10f}));
        assertEquals(1, mCanvas.glyphs.size());
        assertEquals(0f, mCanvas.glyphs.get(0)[0], 0.001f);
        assertEquals(10f, mCanvas.glyphs.get(0)[1], 0.001f);
        assertEquals(0f, mCanvas.glyphs.get(0)[2], 0.001f);
        assertTrue("an aligned run is never scaled: " + mCanvas.scales, mCanvas.scales.isEmpty());
    }

    public void testMisalignedRunIsDrawnSpanBySpan() {
        enter("===\r\n");
        // The first three cells advance as one 25px cluster (the ligature), so the fourth cell boundary is missed.
        render(new ScriptedAdvances(
            new float[]{10f, 10f, 25f, 10f, 10f, 10f, 10f, 10f, 10f, 10f},
            new float[]{10f, 10f, 10f, 10f, 10f, 10f, 10f}));
        assertEquals(2, mCanvas.glyphs.size());
        assertEquals(0f, mCanvas.glyphs.get(0)[0], 0.001f);
        assertEquals(3f, mCanvas.glyphs.get(0)[1], 0.001f);
        assertEquals(0f, mCanvas.glyphs.get(0)[2], 0.001f);
        assertEquals(3f, mCanvas.glyphs.get(1)[0], 0.001f);
        assertEquals(7f, mCanvas.glyphs.get(1)[1], 0.001f);
        assertEquals(30f, mCanvas.glyphs.get(1)[2], 0.001f);
        assertTrue("a span whose total is on-grid is not scaled: " + mCanvas.scales, mCanvas.scales.isEmpty());
    }

    public void testOneCellRemainderNeverConsultsThePolicy() {
        enter("===\r\n");
        // The ninth cell boundary is missed (the tenth cell stands alone), so the run splits after nine cells and
        // the one-cell remainder must not be measured again: one consult for the whole run.
        ScriptedAdvances advances = new ScriptedAdvances(
            new float[]{10f, 10f, 10f, 10f, 10f, 10f, 10f, 10f, 30f, 10f});
        render(advances);
        assertEquals(1, advances.calls);
        assertEquals(2, mCanvas.glyphs.size());
        assertEquals(9f, mCanvas.glyphs.get(0)[1], 0.001f);
        assertEquals(0f, mCanvas.glyphs.get(0)[2], 0.001f);
        assertEquals(1f, mCanvas.glyphs.get(1)[1], 0.001f);
        assertEquals(90f, mCanvas.glyphs.get(1)[2], 0.001f);
    }

    public void testOffGridSpanIsCondensedOntoItsCells() {
        enter("abc" + "\033[44m" + "===\r\n");
        mPaint.spanAdvanceStartChar = 3;
        mPaint.spanAdvance = 40f; // the three-char coloured span measures four cells
        ScriptedAdvances advances = new ScriptedAdvances(
            new float[]{10f, 10f, 10f},       // "abc" run
            new float[]{10f, 10f, 10f},       // the coloured run
            new float[]{10f, 10f, 10f, 10f}); // the row's trailing spaces
        render(advances);
        assertEquals(3, advances.calls);
        assertEquals(3, mCanvas.glyphs.size());
        assertEquals(1, mCanvas.scales.size());
        assertEquals(0.75f, mCanvas.scales.get(0), 0.0001f);
        // The condensed span is pre-divided so that, after the canvas scale, it starts exactly at its first cell.
        assertEquals(40f, mCanvas.glyphs.get(1)[2], 0.001f);
        assertEquals(40f * 0.75f, mCanvas.glyphs.get(1)[2] * mCanvas.scales.get(0), 0.001f);
        assertEquals(3f, mCanvas.glyphs.get(1)[1], 0.001f);
        assertEquals(0f, mCanvas.glyphs.get(0)[2], 0.001f);
        assertEquals(60f, mCanvas.glyphs.get(2)[2], 0.001f);
    }

    public void testRunBackgroundIsDrawnOncePerRunEvenWhenSplit() {
        enter("\033[44m===\r\n");
        ScriptedAdvances advances = new ScriptedAdvances(
            new float[]{10f, 25f, 10f},                    // the coloured run, split after two cells
            new float[]{10f, 10f, 10f, 10f, 10f, 10f, 10f}); // the row's trailing spaces
        render(advances);
        assertEquals(2, advances.calls);
        assertEquals("one span plus the run's one-cell remainder, plus the trailing run",
            3, mCanvas.glyphs.size());
        assertEquals(1, Collections.frequency(mCanvas.calls, "rect"));
    }

    public void testNoShapedAdvancesDrawsOneSpan() {
        enter("===\r\n");
        // Below API 29 RunAdvancesSource.forPaint returns null (the host stub reports API 0), so a run is drawn as
        // one shaped span and the policy is never consulted at all.
        TerminalRenderer renderer = new TerminalRenderer(14, Typeface.MONOSPACE, mPaint, null);
        renderer.render(mTerminal, mCanvas, 0, -1, -1, -1, -1, 0, 1);
        assertEquals(1, mCanvas.glyphs.size());
        assertEquals(10f, mCanvas.glyphs.get(0)[1], 0.001f);
        assertTrue(mCanvas.scales.isEmpty());
    }

    public void testWideCodePointIsStillDrawnPerCharScaled() {
        enter("\u00e9\u00e9\u00e9\r\n");
        mPaint.wideChar = '\u00e9'; // each 'é' measures two cells, so the per-codepoint check breaks every run
        ScriptedAdvances advances = new ScriptedAdvances(new float[]{10f, 10f, 10f, 10f, 10f, 10f, 10f});
        render(advances);
        assertEquals(1, advances.calls); // the three one-cell runs are never verified
        assertEquals(4, mCanvas.glyphs.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(1f, mCanvas.glyphs.get(i)[1], 0.001f);
            assertEquals(0.5f, mCanvas.scales.get(i), 0.0001f);
            assertEquals(20f * i, mCanvas.glyphs.get(i)[2], 0.001f);
        }
        assertEquals(7f, mCanvas.glyphs.get(3)[1], 0.001f);
    }
}
```

Notes for the implementer:
- `mCanvas.glyphs.get(i)` is `{startCharIndex, charCount, x}`; every float assertion needs the 3-argument
  `assertEquals` (JUnit 3's `Assert` has no two-argument float overload).
- Rows are space-filled (`TerminalRow.getSpaceUsed() == mColumns`), so a row's runs cover all 10 cells; the final
  run contains the trailing spaces, which is why the scripts have 10 (or 7, after a run break) entries.
- The cursor sits on row 1 after `\r\n`, so it never splits row 0's runs.
- Host tests take the `drawText` branch (`Build.VERSION.SDK_INT` is stubbed to 0): assert on `glyphs`, not on
  `"textRun"`.

- [ ] **Step 2: Run the tests and watch them fail**

```bash
./gradlew :terminal-view:testDebugUnitTest --tests "com.termux.view.TerminalRendererRunTest"
```

Expected: compilation failure (no 4-argument constructor yet).

- [ ] **Step 3: Add the seam constructor and the advance buffer**

In `TerminalRenderer.java`, change field line 29 and add the new fields:

```java
    final int mTextSize;
    final Typeface mTypeface;
    private final Paint mTextPaint;
    /** The shaped-advance provider; {@code null} when the platform cannot report them (API < 29). */
    private final RunAdvancesSource mRunAdvances;
    /** Grow-only scratch for shaped advances; only the run just measured is ever read back. */
    private float[] mRunAdvancesBuffer = new float[0];
```

Replace the constructor's opening (48–50) with the delegating pair (the rest of the body is unchanged):

```java
    public TerminalRenderer(int textSize, Typeface typeface) {
        this(textSize, typeface, new Paint(), null);
    }

    /** Test seam: {@code textPaint} and {@code runAdvances} replace the platform implementations. */
    TerminalRenderer(int textSize, Typeface typeface, Paint textPaint, RunAdvancesSource runAdvances) {
        mTextSize = textSize;
        mTypeface = typeface;
        mTextPaint = textPaint;
        mRunAdvances = (runAdvances != null) ? runAdvances : RunAdvancesSource.forPaint(mTextPaint);

        mTextPaint.setTypeface(typeface);
        mTextPaint.setAntiAlias(true);
        mTextPaint.setTextSize(textSize);
        // ... the remainder of the existing constructor body is unchanged ...
    }
```

- [ ] **Step 4: Drop `mes` and `measuredWidthForRun`**

Delete the `float measuredWidthForRun = 0.f;` declaration (line 127), its reset in the bitmap branch (147), its
reset on a run break (186) and its accumulation (196). Remove `measuredWidthForRun` from both `drawTextRun` calls
(181–184 and 213–215) and the `float mes` parameter from `drawTextRun` (line 220).

- [ ] **Step 5: Make the run's rectangles cell-exact and let the span own the scale**

Replace the scale block (258–271) with:

```java
        float left = startColumn * mFontWidth;
        float right = left + runWidthColumns * mFontWidth;
        // The run's background, cursor and custom underline are cell-exact and are never scaled; only the glyphs
        // are, and only within a span (drawGlyphs).
        final float runRight = right;
```

Replace the glyph call (306–311) with:

```java
            drawGlyphs(canvas, text, startCharIndex, runWidthChars, startColumn, runWidthColumns, y);
```

Delete the trailing `if (savedMatrix) canvas.restore();` (line 327).

- [ ] **Step 6: Add the glyph stage**

Add after `drawTextRun`:

```java
    /**
     * Draws the glyphs of one run. Where the platform reports shaped advances (API 29+), the run is verified
     * against the cell grid: at the first cell boundary the advances miss, the run is cut and the spans are drawn
     * on their own, so a cluster can never push a glyph into the next cell. A run the policy does not cut, and
     * every run when no advances are available, is drawn as a single span.
     */
    private void drawGlyphs(Canvas canvas, char[] text, int startCharIndex, int runWidthChars, int startColumn,
                            int runWidthColumns, float y) {
        int spanCharIndex = startCharIndex;
        int spanStartColumn = startColumn;
        int columnsLeft = runWidthColumns;
        int charsLeft = runWidthChars;
        while (columnsLeft > 0) {
            int spanColumns = columnsLeft;
            if (mRunAdvances != null && columnsLeft > 1) {
                ensureRunAdvancesBuffer(charsLeft);
                final int advanceCount = mRunAdvances.advances(text, spanCharIndex, charsLeft, mRunAdvancesBuffer);
                final int misalignedCell = ShapedRunPolicy.firstMisalignedCell(mRunAdvancesBuffer, advanceCount,
                    columnsLeft, mFontWidth);
                if (misalignedCell > 0) spanColumns = misalignedCell;
            }
            final int spanChars = charsForColumns(text, spanCharIndex, spanColumns, spanCharIndex + charsLeft);
            if (spanChars <= 0) {
                // Defensive: the slice cannot be walked (degenerate zero-width data); draw the rest and stop.
                drawGlyphSpan(canvas, text, spanCharIndex, charsLeft, spanStartColumn, columnsLeft, y);
                return;
            }
            drawGlyphSpan(canvas, text, spanCharIndex, spanChars, spanStartColumn, spanColumns, y);
            spanCharIndex += spanChars;
            spanStartColumn += spanColumns;
            charsLeft -= spanChars;
            columnsLeft -= spanColumns;
        }
    }

    /**
     * Draws one span onto its own cells. The span's shaped total decides whether the canvas is scaled
     * horizontally, so a span the font measures off its cells still lands inside them (the same 1% tolerance the
     * run loop's per-codepoint check uses). A non-positive cell width cannot be scaled, so the span is drawn at
     * its cell origin instead.
     */
    private void drawGlyphSpan(Canvas canvas, char[] text, int startCharIndex, int spanChars, int startColumn,
                               int spanColumns, float y) {
        final float left = startColumn * mFontWidth;
        final float measuredWidth = mTextPaint.measureText(text, startCharIndex, spanChars);
        final float measuredColumns = (mFontWidth > 0f) ? measuredWidth / mFontWidth : spanColumns;
        if (measuredColumns > 0f && Math.abs(measuredColumns - spanColumns) > 0.01) {
            // The canvas is scaled about the origin, so the glyph origin is pre-divided to land on the cell.
            canvas.save();
            canvas.scale(spanColumns / measuredColumns, 1.f);
            drawGlyphText(canvas, text, startCharIndex, spanChars, left * (measuredColumns / spanColumns), y);
            canvas.restore();
        } else {
            drawGlyphText(canvas, text, startCharIndex, spanChars, left, y);
        }
    }

    /** Draws the glyphs of one span, shaped when the platform can shape a char range (API 23+). */
    private void drawGlyphText(Canvas canvas, char[] text, int startCharIndex, int spanChars, float x, float y) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            canvas.drawTextRun(text, startCharIndex, spanChars, startCharIndex, spanChars, x,
                y - mFontLineSpacingAndAscent, false, mTextPaint);
        } else {
            canvas.drawText(text, startCharIndex, spanChars, x, y - mFontLineSpacingAndAscent, mTextPaint);
        }
    }

    /**
     * How many chars of {@code text} from {@code startCharIndex} occupy {@code columns} cells, never past
     * {@code endCharIndex}. Combining chars are eaten with the cell of their base char, exactly as the run loop's
     * own column walk keeps them inside the run.
     */
    private int charsForColumns(char[] text, int startCharIndex, int columns, int endCharIndex) {
        int charIndex = startCharIndex;
        int columnsLeft = columns;
        while (charIndex < endCharIndex && columnsLeft > 0) {
            final boolean highSurrogate = Character.isHighSurrogate(text[charIndex]) && charIndex + 1 < endCharIndex;
            final int codePoint = highSurrogate
                ? Character.toCodePoint(text[charIndex], text[charIndex + 1])
                : text[charIndex];
            columnsLeft -= Math.max(WcWidth.width(codePoint), 0);
            charIndex += highSurrogate ? 2 : 1;
            while (charIndex < endCharIndex && WcWidth.width(text, charIndex) <= 0) {
                charIndex += (Character.isHighSurrogate(text[charIndex]) && charIndex + 1 < endCharIndex) ? 2 : 1;
            }
        }
        return charIndex - startCharIndex;
    }

    private void ensureRunAdvancesBuffer(int chars) {
        if (mRunAdvancesBuffer.length < chars) mRunAdvancesBuffer = new float[chars];
    }
```

- [ ] **Step 7: Run the module suite**

```bash
./gradlew :terminal-view:testDebugUnitTest
```

Expected: green, including the pre-existing `RowInvalidationTest`/`RowInvalidationBufferTest`.

- [ ] **Step 8: Check the scope, run the full gate, commit**

```bash
git diff --stat -- terminal-emulator termux-shared   # must be empty
./gradlew test
git add terminal-view/src/main/java/com/termux/view/TerminalRenderer.java \
        terminal-view/src/test/java/com/termux/view/TerminalRendererRunTest.java
git commit -S -m "Changed(view): Snap shaped runs to the cell grid in the renderer"
```

---

### Task 3: The ligature toggle, end to end

**Files:**
- Modify: `terminal-view/src/main/java/com/termux/view/TerminalRenderer.java` (constructor pair from Task 2 + the feature-settings call)
- Modify: `terminal-view/src/main/java/com/termux/view/TerminalView.java` (`setTextSize` 567, `setTypeface` 572; add the field and `setLigaturesEnabled`)
- Modify: `terminal-view/src/test/java/com/termux/view/TerminalRendererRunTest.java` (the seam constructor gains the flag)
- Create: `terminal-view/src/test/java/com/termux/view/TerminalLigatureTest.java`
- Modify: `app/src/main/java/com/termux/app/ui/appearance/AppearanceStore.kt` (keys object 48–57; interface 15–33; `read()` 128–135; `write()` 149–162)
- Modify: `app/src/main/java/com/termux/app/ui/appearance/AppearanceState.kt` (`TerminalAppearance` 52–60; add a setter)
- Modify: `app/src/main/java/com/termux/app/ui/appearance/AppearanceActivity.kt` (`AndroidAppearancePreferences` 156–188)
- Modify: `app/src/main/java/com/termux/app/ui/appearance/AppearanceScreen.kt` (font section 202–208; add a row composable)
- Modify: `app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java` (`checkForFontAndColors()`)
- Modify: `app/src/main/res/values/strings.xml` (next to `appearance_font_size`, line 138)
- Modify: `app/src/test/java/com/termux/app/ui/appearance/AppearanceStoreTest.kt` (`FakePreferences` 28–49) and `AppearanceStateTest.kt`

**Interfaces:**
- `TerminalRenderer(int textSize, Typeface typeface, boolean ligatures)` — new public overload; the existing
  public 2-argument constructor delegates with `true`.
- `TerminalRenderer(int textSize, Typeface typeface, boolean ligatures, Paint textPaint, RunAdvancesSource runAdvances)`
  — the test seam (Task 2's 4-argument form gains the flag).
- `TerminalView.setLigaturesEnabled(boolean)` — rebuilds the renderer only when the value changes.
- `AppearancePreferences.getLigatures(): Boolean` / `setLigatures(enabled: Boolean)`.
- `TerminalAppearance.ligatures: Boolean` (defaulted to `true`, so existing constructions keep compiling).
- `AppearanceState.setLigatures(enabled: Boolean)`.
- Feature strings: on `"+liga,+calt"`, off `"-liga,-calt"` (the spec's exact values).

- [ ] **Step 1: Write the failing renderer paint test**

Create `terminal-view/src/test/java/com/termux/view/TerminalLigatureTest.java`:

```java
package com.termux.view;

import android.graphics.Paint;
import android.graphics.Typeface;

import junit.framework.TestCase;

/**
 * The ligature toggle is a renderer-construction setting: it becomes a font feature string on the one paint the
 * renderer keeps. The paint is a stub so the value can be read back on this host (a stubbed {@link Paint} would
 * silently discard it).
 */
public class TerminalLigatureTest extends TestCase {

    private static final class RecordingPaint extends Paint {
        String featureSettings;

        @Override public void setFontFeatureSettings(String featureSettings) {
            this.featureSettings = featureSettings;
        }
    }

    public void testLigaturesOnRequestsLigaturesAndContextualAlternates() {
        RecordingPaint paint = new RecordingPaint();
        new TerminalRenderer(14, Typeface.MONOSPACE, true, paint, null);
        assertEquals("+liga,+calt", paint.featureSettings);
    }

    public void testLigaturesOffDisablesBoth() {
        RecordingPaint paint = new RecordingPaint();
        new TerminalRenderer(14, Typeface.MONOSPACE, false, paint, null);
        assertEquals("-liga,-calt", paint.featureSettings);
    }
}
```

- [ ] **Step 2: Write the failing `:app` tests**

In `AppearanceStoreTest.kt`, give `FakePreferences` the new members (a constructor parameter
`var storedLigatures: Boolean = true` plus the two overrides):

```kotlin
        override fun getLigatures(): Boolean = storedLigatures
        override fun setLigatures(enabled: Boolean) {
            storedLigatures = enabled
        }
```

and add:

```kotlin
    @Test
    fun `ligatures round-trip through the store`() {
        val preferences = FakePreferences()
        store(preferences).write(appearance().copy(ligatures = false))
        assertFalse(preferences.storedLigatures)
        assertFalse(store(preferences).read().ligatures)
    }

    @Test
    fun `a store with the default preference reads ligatures as on`() {
        assertTrue(store(FakePreferences()).read().ligatures)
    }
```

Add `ligatures: Boolean = true` to that file's `appearance(...)` helper and pass it through to
`TerminalAppearance`.

In `AppearanceStateTest.kt`, add `ligatures: Boolean = true` to its `appearance(...)` helper plus:

```kotlin
    @Test
    fun `staging the ligature flag marks the state dirty`() {
        val state = AppearanceState(appearance())
        state.setLigatures(false)
        assertFalse(state.draft.ligatures)
        assertTrue(state.isDirty)
    }

    @Test
    fun `reset leaves the ligature flag alone`() {
        // Reset is documented as leaving the font settings (family, size) alone, so the toggle is not reset.
        val state = AppearanceState(appearance(ligatures = false))
        state.setForeground(0xff123456.toInt())
        state.resetToDefault()
        assertFalse(state.draft.ligatures)
    }
```

- [ ] **Step 3: Run the new tests and watch them fail**

```bash
./gradlew :terminal-view:testDebugUnitTest :app:testDebugUnitTest
```

Expected: compilation failures (no ligature flag on the renderer, no `ligatures` on the preference interface).

- [ ] **Step 4: Apply the flag in the renderer**

```java
    public TerminalRenderer(int textSize, Typeface typeface) {
        this(textSize, typeface, true);
    }

    /** Whether the paint asks the font for ligatures is a construction setting: it does not affect metrics. */
    public TerminalRenderer(int textSize, Typeface typeface, boolean ligatures) {
        this(textSize, typeface, ligatures, new Paint(), null);
    }

    /** Test seam: {@code textPaint} and {@code runAdvances} replace the platform implementations. */
    TerminalRenderer(int textSize, Typeface typeface, boolean ligatures, Paint textPaint,
                     RunAdvancesSource runAdvances) {
        mTextSize = textSize;
        mTypeface = typeface;
        mTextPaint = textPaint;
        mRunAdvances = (runAdvances != null) ? runAdvances : RunAdvancesSource.forPaint(mTextPaint);

        mTextPaint.setTypeface(typeface);
        mTextPaint.setAntiAlias(true);
        mTextPaint.setTextSize(textSize);
        mTextPaint.setFontFeatureSettings(ligatures ? LIGATURES_ON : LIGATURES_OFF);
        // ... the remainder of the existing constructor body is unchanged ...
    }
```

with, next to the other constants:

```java
    /** Font features that ligatures-on requests: standard ligatures plus contextual alternates. */
    static final String LIGATURES_ON = "+liga,+calt";
    /** Font features that ligatures-off requests. */
    static final String LIGATURES_OFF = "-liga,-calt";
```

Update `TerminalRendererRunTest`: `render(RunAdvancesSource)` and `testNoShapedAdvancesDrawsOneSpan` pass `true` as
the flag of the 5-argument constructor (`new TerminalRenderer(14, Typeface.MONOSPACE, true, mPaint, advances)`).
The glyph-stage assertions are unaffected.

- [ ] **Step 5: Thread the flag through `TerminalView`**

```java
    /** Whether ligatures are requested; carried across the renderer rebuilds in setTextSize/setTypeface. */
    private boolean mLigaturesEnabled = true;

    public void setTextSize(int textSize) {
        mRenderer = new TerminalRenderer(textSize,
            mRenderer == null ? Typeface.MONOSPACE : mRenderer.mTypeface, mLigaturesEnabled);
        updateSize();
    }

    public void setTypeface(Typeface newTypeface) {
        mRenderer = new TerminalRenderer(mRenderer.mTextSize, newTypeface, mLigaturesEnabled);
        updateSize();
        invalidate();
    }

    /**
     * Sets whether the font's ligatures are used. The feature string cannot change the cell metrics, so a change
     * only needs the renderer rebuilt and the view repainted; no re-layout is requested.
     */
    public void setLigaturesEnabled(boolean ligaturesEnabled) {
        if (mLigaturesEnabled == ligaturesEnabled) return;
        mLigaturesEnabled = ligaturesEnabled;
        if (mRenderer != null) {
            mRenderer = new TerminalRenderer(mRenderer.mTextSize, mRenderer.mTypeface, mLigaturesEnabled);
        }
        invalidate();
    }
```

Confirm by inspection that these are the only `new TerminalRenderer(` sites in the module. The renderer keeps no
ligature field of its own on purpose: `TerminalView` owns the state and rebuilds the renderer from it, exactly as
it owns the typeface and size for rebuilds.

- [ ] **Step 6: Add the preference, state and screen wiring**

`AppearanceStore.kt` — the keys object:

```kotlin
object AppearancePreferenceKeys {
    const val PREFERENCES_FILE = "termux_appearance"

    const val CURSOR_STYLE = "appearance_cursor_style"
    const val CURSOR_BLINK_RATE = "appearance_cursor_blink_rate"

    /** Whether the terminal asks the font for ligatures. */
    const val LIGATURES = "appearance_ligatures"
    /** Ligatures are on unless the user turns them off. */
    const val LIGATURES_DEFAULT = true

    // ... STYLING_REVISION unchanged ...
}
```

the interface:

```kotlin
    fun getLigatures(): Boolean

    fun setLigatures(enabled: Boolean)
```

`read()`, in the `TerminalAppearance(...)` call: `ligatures = preferences.getLigatures(),`
`write()`, next to the cursor writes: `preferences.setLigatures(appearance.ligatures)`

`AppearanceState.kt` — `TerminalAppearance` gains a last, defaulted parameter:

```kotlin
    /** Whether the terminal asks the font for ligatures. */
    val ligatures: Boolean = true,
```

and `AppearanceState` gains:

```kotlin
    fun setLigatures(enabled: Boolean) {
        draft = draft.copy(ligatures = enabled)
    }
```

`resetToDefault()` is deliberately left unchanged: it stages colours and the cursor, and the class documents that
font settings (family, size) are left alone, which is where the flag belongs.

`AppearanceActivity.kt` — `AndroidAppearancePreferences`:

```kotlin
    private val appearancePreferences =
        context.applicationContext.getSharedPreferences(
            AppearancePreferenceKeys.PREFERENCES_FILE,
            Context.MODE_PRIVATE,
        )

    override fun getLigatures(): Boolean =
        appearancePreferences.getBoolean(
            AppearancePreferenceKeys.LIGATURES,
            AppearancePreferenceKeys.LIGATURES_DEFAULT,
        )

    override fun setLigatures(enabled: Boolean) {
        appearancePreferences.edit().putBoolean(AppearancePreferenceKeys.LIGATURES, enabled).apply()
    }
```

`AppearanceScreen.kt` — in the font section, after `FontSizeRow(...)`:

```kotlin
            LigaturesRow(draft.ligatures) { state.setLigatures(it) }
```

and next to the other row composables:

```kotlin
/** The ligature toggle: the whole row is the switch target, so it is a single accessible control. */
@Composable
private fun LigaturesRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(stringResource(R.string.appearance_ligatures))
        Switch(checked = enabled, onCheckedChange = null)
    }
}
```

New imports for that file (check which are already present): `androidx.compose.foundation.selection.toggleable`
and `androidx.compose.material3.Switch` (`androidx.compose.ui.Alignment` and `Role` are already imported).

`strings.xml`, next to `appearance_font_size`:

```xml
    <string name="appearance_ligatures">Ligatures</string>
```

`TermuxTerminalSessionActivityClient.java` — in `checkForFontAndColors()`, right after the existing
`setTypeface(...)` call, with `import com.termux.app.ui.appearance.AppearancePreferenceKeys;` added (the file
already imports `android.content.Context`):

```java
        // The appearance screen owns the ligature preference; the constants are the read-site contract.
        mActivity.getTerminalView().setLigaturesEnabled(mActivity
            .getSharedPreferences(AppearancePreferenceKeys.PREFERENCES_FILE, Context.MODE_PRIVATE)
            .getBoolean(AppearancePreferenceKeys.LIGATURES, AppearancePreferenceKeys.LIGATURES_DEFAULT));
```

- [ ] **Step 7: Run the module suites**

```bash
./gradlew :terminal-view:testDebugUnitTest :app:testDebugUnitTest
```

Expected: green.

- [ ] **Step 8: Check the scope, run the full gate, commit**

```bash
git diff --stat                 # terminal-view/, app/ and (later) the docs only
./gradlew test
git add terminal-view app
git commit -S -m "Added(terminal): Add the ligature toggle to the terminal appearance"
```

---

### Task 4: Close out the workstream records

**Files:**
- Modify: `docs/specs/2026-10-18-ws3-shaping-ligatures-design.md` (status header)
- Modify: `docs/ledger/outstanding-work.md` (§7 WS3 bullet)

**Interfaces:**
- Consumes: the three commits from Tasks 1–3.
- Produces: records that name the commit range, the tested surface and the remaining device checks.

- [ ] **Step 1: Full gate from the committed tree**

```bash
./gradlew test
git status --short              # only pre-existing untracked files (.planning/) may show
```

- [ ] **Step 2: Update the two status lines**

- Spec header: `**Status:** approved` → `**Status:** implemented (commits `<a>`, `<b>`, `<c>`); the §7 device
  checks are outstanding` (keep the API-29 note where it is).
- Ledger §7 WS3 bullet: replace "Not yet planned or implemented" with "Implemented in three commits
  (`<a>`, `<b>`, `<c>`): the grid-snap policy and advance seam, the per-span glyph stage, and the ligature toggle
  (appearance screen, default on). The §3 device checklist is still outstanding."

- [ ] **Step 3: Commit**

```bash
git add docs/specs/2026-10-18-ws3-shaping-ligatures-design.md docs/ledger/outstanding-work.md
git commit -S -m "Changed(docs): Record the WS3 shaping and ligatures implementation"
```
