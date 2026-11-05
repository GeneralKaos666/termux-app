# TerminalView pure-seam split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move four pure seams out of `TerminalView` into package-private helpers with unit tests, and fix the unpaired-surrogate crash in the IME text path.

**Architecture:** Each seam becomes a stateless class in `com.termux.view` (`RenderBand`, `KeyCodePoints`, `ImeTextEncoder`, `TerminalGeometry`), matching the existing `LinkUris`/`RowInvalidation`/`ShapedRunPolicy` pattern. `TerminalView` keeps the Android-type unwrapping and calls them. Helpers import no `android.*` types, so the existing plain-JUnit suite in `:terminal-view` works without Robolectric.

**Tech Stack:** Java 17 source, Android library module `:terminal-view`, JUnit 4 (plain, `unitTests.returnDefaultValues = true`), Gradle.

**Spec:** `docs/specs/2026-11-04-terminalview-pure-seams-design.md`

## Global Constraints

- No public API change to `TerminalView`: `attachSession`, `updateSize`, `setTopRow`, selection, autofill and blinker signatures stay exact.
- No Robolectric, no new dependency, no `android.*` import in any new helper.
- Extractions are mechanical: code cut and pasted, identifiers and comments preserved.
- The **only** intentional behaviour change is the unpaired-surrogate handling in Task 4; it gets its own commit.
- One commit per task; full `./gradlew test` green before each commit.
- `:terminal-view:test` rejects `--tests`; use `:terminal-view:testDebugUnitTest`.
- `:app:assembleDebug` in this Termux workspace needs `-PndkVersion=30.0.16248370`.
- Commit messages use capitalized Conventional Commits types (`Added(...)`, `Fixed(...)`, `Changed(...)`), signed (repo `commit.gpgsign` is on; check `%G?` is `G`).

## Review Focus

- **Unpaired surrogate from the IME** (lone low, or high followed by a non-low): must not throw, must not emit a bogus code point, and must not swallow the character that follows a failed pair. Task 4.
- **Bluetooth/external keyboard sending modifier letters** (`0x02DC` etc.): normalization must stay gated on event source, so a soft keyboard that legitimately types `˜` is not rewritten. Tasks 2 and 3.
- **Shift plus control on a letter**: shift uppercasing happens before the control remap, so both paths still produce 1–26. Tasks 2 and 3.
- **`Canvas.getClipBounds` returning false**: the stale `Rect` must not be used; the band must be the full visible band. Task 1.
- **Clip taller than the view** (scroll container): band must clamp to `[topRow, topRow + screenRows)`. Task 1.

---

### Task 1: `RenderBand`

**Files:**
- Create: `terminal-view/src/main/java/com/termux/view/RenderBand.java`
- Create: `terminal-view/src/test/java/com/termux/view/RenderBandTest.java`
- Modify: `terminal-view/src/main/java/com/termux/view/TerminalView.java` (`onDraw`, remove `ceilDivInt`, around lines 1131–1182)

**Interfaces:**
- Consumes: nothing.
- Produces: `static RenderBand RenderBand.forClip(int topRow, int screenRows, int rowHeight, boolean clipKnown, int clipTop, int clipBottom)` returning an object with `final int firstRow` and `final int lastRowExclusive`.

- [ ] **Step 1: Write the failing test**

`terminal-view/src/test/java/com/termux/view/RenderBandTest.java`, JUnit 4 with static imports:

```java
package com.termux.view;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class RenderBandTest {
    private static void assertBand(int expectedFirst, int expectedLast, RenderBand band) {
        assertEquals(expectedFirst, band.firstRow);
        assertEquals(expectedLast, band.lastRowExclusive);
    }

    @Test public void fullBandWhenClipIsUnknown() {
        assertBand(-5, 19, RenderBand.forClip(-5, 24, 20, false, 0, 0));
    }

    @Test public void fullBandWhenClipIsEmpty() {
        assertBand(0, 24, RenderBand.forClip(0, 24, 20, true, 0, 0));
    }

    @Test public void fullBandWhenRowHeightIsZero() {
        assertBand(0, 24, RenderBand.forClip(0, 24, 0, true, 100, 200));
    }

    @Test public void clipInsideTheBandKeepsOneRowOfSlack() {
        // rows 5-9 at 20px are y=100..199; slack -> rows 4..10 inclusive -> last exclusive 11
        assertBand(4, 11, RenderBand.forClip(0, 24, 20, true, 100, 200));
    }

    @Test public void clipLargerThanTheScreenIsClamped() {
        assertBand(-2, 22, RenderBand.forClip(-2, 24, 20, true, -1000, 5000));
    }

    @Test public void clipEntirelyAboveTheBandFallsBackToFullBand() {
        assertBand(0, 24, RenderBand.forClip(0, 24, 20, true, 0, 1));
    }

    @Test public void clipEntirelyBelowTheBandFallsBackToFullBand() {
        assertBand(0, 24, RenderBand.forClip(0, 24, 20, true, 5000, 6000));
    }

    @Test public void negativeClipTopUsesFloorDivision() {
        assertBand(-1, 3, RenderBand.forClip(0, 24, 20, true, -10, 40));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :terminal-view:testDebugUnitTest --tests "com.termux.view.RenderBandTest"`
Expected: compile failure, `RenderBand` does not exist.

- [ ] **Step 3: Implement `RenderBand`**

Move the band computation from `TerminalView.onDraw` and `ceilDivInt` verbatim:

```java
first = Math.max(topRow, topRow + Math.floorDiv(clipTop, rowHeight) - 1);
last = Math.min(topRow + screenRows, topRow + ceilDiv(clipTop-equivalent...));
```

Exact rules: default `[topRow, topRow + screenRows)`; if `clipKnown && clipTop != clipBottom && rowHeight > 0`, apply the floor/ceil formula from the current code; if the result has `lastRowExclusive <= firstRow`, return the default band. Keep `ceilDivInt` as a private static in `RenderBand`.

- [ ] **Step 4: Replace the block in `TerminalView.onDraw`**

Keep `canvas.getClipBounds(mClipBounds) && !mClipBounds.isEmpty()` at the call site; pass `mClipBounds.top`, `mClipBounds.bottom` as ints. Call:

```java
RenderBand band = RenderBand.forClip(mTopRow, mEmulator.mRows, rowHeight,
    canvas.getClipBounds(mClipBounds) && !mClipBounds.isEmpty(), mClipBounds.top, mClipBounds.bottom);
mRenderer.render(mEmulator, canvas, mTopRow, sel[0], sel[1], sel[2], sel[3],
    band.firstRow, band.lastRowExclusive);
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :terminal-view:testDebugUnitTest --tests "com.termux.view.RenderBandTest"`
Expected: 8 tests, 0 failures.

- [ ] **Step 6: Run the full suite**

Run: `./gradlew test`
Expected: 0 failures.

- [ ] **Step 7: Commit**

```bash
git add terminal-view docs
git commit -m "Changed(view): Extract the draw band range into RenderBand"
```

---

### Task 2: `KeyCodePoints`

**Files:**
- Create: `terminal-view/src/main/java/com/termux/view/KeyCodePoints.java`
- Create: `terminal-view/src/test/java/com/termux/view/KeyCodePointsTest.java`
- Modify: `terminal-view/src/main/java/com/termux/view/TerminalView.java` (`inputCodePoint`, around lines 956–1019)

**Interfaces:**
- Consumes: nothing.
- Produces: `static int KeyCodePoints.applyControl(int codePoint)`, `static int KeyCodePoints.normalizeExternalKeyboard(int codePoint)`.

- [ ] **Step 1: Write the failing test**

Cover every row of the spec table plus pass-through:

```java
@Test public void controlMapsLettersToTheirControlCode() {
    for (int i = 0; i < 26; i++) {
        assertEquals(i + 1, KeyCodePoints.applyControl('a' + i));
        assertEquals(i + 1, KeyCodePoints.applyControl('A' + i));
    }
}
@Test public void controlMapsSpaceAndTwoToNul() { assertEquals(0, KeyCodePoints.applyControl(' ')); assertEquals(0, KeyCodePoints.applyControl('2')); }
@Test public void controlMapsBracketsAndDigits() {
    assertEquals(27, KeyCodePoints.applyControl('['));  assertEquals(27, KeyCodePoints.applyControl('3'));
    assertEquals(28, KeyCodePoints.applyControl('\\')); assertEquals(28, KeyCodePoints.applyControl('4'));
    assertEquals(29, KeyCodePoints.applyControl(']'));  assertEquals(29, KeyCodePoints.applyControl('5'));
    assertEquals(30, KeyCodePoints.applyControl('^'));  assertEquals(30, KeyCodePoints.applyControl('6'));
    assertEquals(31, KeyCodePoints.applyControl('_'));  assertEquals(31, KeyCodePoints.applyControl('7'));
    assertEquals(31, KeyCodePoints.applyControl('/'));  assertEquals(127, KeyCodePoints.applyControl('8'));
}
@Test public void controlLeavesOtherCodePointsAlone() {
    assertEquals('9', KeyCodePoints.applyControl('9'));
    assertEquals('0', KeyCodePoints.applyControl('0'));
    assertEquals('!', KeyCodePoints.applyControl('!'));
    assertEquals(0x20AC, KeyCodePoints.applyControl(0x20AC));
    assertEquals(27, KeyCodePoints.applyControl(27));
}
@Test public void externalKeyboardModifierLettersAreNormalized() {
    assertEquals('~', KeyCodePoints.normalizeExternalKeyboard(0x02DC));
    assertEquals('`', KeyCodePoints.normalizeExternalKeyboard(0x02CB));
    assertEquals('^', KeyCodePoints.normalizeExternalKeyboard(0x02C6));
    assertEquals('a', KeyCodePoints.normalizeExternalKeyboard('a'));
}
```

Also assert that a valid non-ASCII character typed on a **soft** keyboard is not normalized: that gate stays in `TerminalView`, so the test encodes it as "`normalizeExternalKeyboard(0x02DC)` is only called for `eventSource > 0`" — add a `TerminalView`-free comment test in `KeyCodePointsTest` is not possible; instead Task 3 covers the IME path with `0x02DC` expected unchanged, which pins the gate.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :terminal-view:testDebugUnitTest --tests "com.termux.view.KeyCodePointsTest"`
Expected: compile failure.

- [ ] **Step 3: Implement `KeyCodePoints`**

Move the two chains verbatim from `inputCodePoint`. Do not move the `eventSource > KEY_EVENT_SOURCE_SOFT_KEYBOARD` condition or the `codePoint > -1` guard.

- [ ] **Step 4: Update `inputCodePoint`**

Replace the ctrl chain with `codePoint = KeyCodePoints.applyControl(codePoint);` and the switch with `codePoint = KeyCodePoints.normalizeExternalKeyboard(codePoint);` inside the existing `eventSource > KEY_EVENT_SOURCE_SOFT_KEYBOARD` block.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :terminal-view:testDebugUnitTest --tests "com.termux.view.KeyCodePointsTest"`
Expected: 4 tests, 0 failures.

- [ ] **Step 6: Full suite and commit**

Run: `./gradlew test` → 0 failures.

```bash
git add terminal-view
git commit -m "Changed(view): Extract key code-point mapping into KeyCodePoints"
```

---

### Task 3: `ImeTextEncoder` (extraction, behaviour preserved)

**Files:**
- Create: `terminal-view/src/main/java/com/termux/view/ImeTextEncoder.java`
- Create: `terminal-view/src/test/java/com/termux/view/ImeTextEncoderTest.java`
- Modify: `terminal-view/src/main/java/com/termux/view/TerminalView.java` (`onCreateInputConnection`'s `sendTextToTerminal`, around lines 414–468)

**Interfaces:**
- Consumes: nothing.
- Produces: `static List<ImeTextEncoder.KeyStroke> ImeTextEncoder.encode(CharSequence text, boolean shiftDown)`, with `KeyStroke.codePoint` (int) and `KeyStroke.controlHeld` (boolean).

- [ ] **Step 1: Write the failing test (valid input only; the surrogate fix is Task 4)**

```java
private static void assertStroke(int expectedCodePoint, boolean expectedControl, KeyStroke stroke) {
    assertEquals(expectedCodePoint, stroke.codePoint);
    assertEquals(expectedControl, stroke.controlHeld);
}

@Test public void plainAsciiIsOneStrokeEach() {
    List<KeyStroke> strokes = ImeTextEncoder.encode("abc", false);
    assertEquals(3, strokes.size());
    assertStroke('a', false, strokes.get(0));
}
@Test public void shiftUppercasesBeforeAnythingElse() {
    assertStroke('A', false, ImeTextEncoder.encode("a", true).get(0));
}
@Test public void newlineBecomesControlM() {
    assertStroke('m', true, ImeTextEncoder.encode("\n", false).get(0));
}
@Test public void controlRangeIsRemappedWithControlHeld() {
    assertStroke('_', true, ImeTextEncoder.encode("\u001f", false).get(0));
    assertStroke('^', true, ImeTextEncoder.encode("\u001e", false).get(0));
    assertStroke(']', true, ImeTextEncoder.encode("\u001d", false).get(0));
    assertStroke('\\', true, ImeTextEncoder.encode("\u001c", false).get(0));
    assertStroke('a', true, ImeTextEncoder.encode("\u0001", false).get(0));
}
@Test public void escapeIsPassedThroughWithoutControl() {
    assertStroke(27, false, ImeTextEncoder.encode("\u001b", false).get(0));
}
@Test public void modifierLetterFromASoftKeyboardIsNotNormalized() {
    assertStroke(0x02DC, false, ImeTextEncoder.encode("\u02dc", false).get(0));
}
@Test public void emojiSurrogatePairIsOneStroke() {
    List<KeyStroke> strokes = ImeTextEncoder.encode("\uD83D\uDE00", false);
    assertEquals(1, strokes.size());
    assertStroke(0x1F600, false, strokes.get(0));
}
@Test public void highSurrogateAtTheEndIsReplacement() {
    assertStroke(0xFFFD, false, ImeTextEncoder.encode("a\uD83D", false).get(1));
}
@Test public void emptyTextHasNoStrokes() {
    assertTrue(ImeTextEncoder.encode("", false).isEmpty());
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :terminal-view:testDebugUnitTest --tests "com.termux.view.ImeTextEncoderTest"`
Expected: compile failure.

- [ ] **Step 3: Implement `ImeTextEncoder`**

Move the loop body verbatim, including the existing surrogate branch, the shift uppercasing, the `\n` → `\r` replacement and the four switch arms with the `+ 96` default. Return a `List<KeyStroke>` built in input order.

- [ ] **Step 4: Replace `sendTextToTerminal`'s loop**

```java
void sendTextToTerminal(CharSequence text) {
    stopTextSelectionMode();
    for (ImeTextEncoder.KeyStroke stroke : ImeTextEncoder.encode(text, mClient.readShiftKey())) {
        inputCodePoint(KEY_EVENT_SOURCE_SOFT_KEYBOARD, stroke.codePoint, stroke.controlHeld, false);
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :terminal-view:testDebugUnitTest --tests "com.termux.view.ImeTextEncoderTest"`
Expected: 9 tests, 0 failures.

- [ ] **Step 6: Full suite and commit**

Run: `./gradlew test` → 0 failures.

```bash
git add terminal-view
git commit -m "Changed(view): Extract the IME text encoding into ImeTextEncoder"
```

---

### Task 4: Fix unpaired surrogates in the IME path

**Files:**
- Modify: `terminal-view/src/main/java/com/termux/view/ImeTextEncoder.java`
- Modify: `terminal-view/src/test/java/com/termux/view/ImeTextEncoderTest.java`
- Modify: `docs/ledger/outstanding-work.md` (§2.4 entry, new item)

**Interfaces:**
- Consumes: `ImeTextEncoder.encode(CharSequence, boolean)` from Task 3.
- Produces: same signature; unpaired surrogates now yield `0xFFFD`.

- [ ] **Step 1: Add the failing tests**

```java
@Test public void loneLowSurrogateIsReplacement() {
    assertStroke(0xFFFD, false, ImeTextEncoder.encode("\uDE00", false).get(0));
}
@Test public void highSurrogateFollowedByNonLowKeepsBothCharacters() {
    List<KeyStroke> strokes = ImeTextEncoder.encode("\uD83Da", false);
    assertEquals(2, strokes.size());
    assertStroke(0xFFFD, false, strokes.get(0));
    assertStroke('a', false, strokes.get(1));
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :terminal-view:testDebugUnitTest --tests "com.termux.view.ImeTextEncoderTest"`
Expected: `loneLowSurrogateIsReplacement` fails (0xDE00 instead of 0xFFFD) and `highSurrogateFollowedByNonLowKeepsBothCharacters` fails (one bogus stroke, or size 1).

- [ ] **Step 3: Fix the surrogate branch**

Treat a high surrogate as a valid lead only when the next char is a low surrogate; otherwise emit `TerminalEmulator.UNICODE_REPLACEMENT_CHAR` and do not consume the next char. A low surrogate reached as a base char emits `UNICODE_REPLACEMENT_CHAR` too. Valid pairs keep going through `Character.toCodePoint`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :terminal-view:testDebugUnitTest --tests "com.termux.view.ImeTextEncoderTest"`
Expected: 11 tests, 0 failures.

- [ ] **Step 5: Record it in the ledger**

Add under §2.4, in the same format as the neighbouring entries: `[fixed 2026-11-04]` **unpaired surrogate from the IME crashed the session or typed a random character** — a lone low surrogate reached `TerminalSession.writeCodePoint`, which rejects the surrogate range with `IllegalArgumentException`; a high surrogate followed by a non-low char went through `Character.toCodePoint` unvalidated and produced an unrelated code point. `ImeTextEncoder.encode` now emits `U+FFFD` for any unpaired surrogate and leaves the following character intact. Cite the two tests.

- [ ] **Step 6: Full suite and commit**

Run: `./gradlew test` → 0 failures.

```bash
git add terminal-view docs/ledger
git commit -m "Fixed(view): Replace unpaired surrogates in IME text instead of crashing"
```

---

### Task 5: `TerminalGeometry`

**Files:**
- Create: `terminal-view/src/main/java/com/termux/view/TerminalGeometry.java`
- Create: `terminal-view/src/test/java/com/termux/view/TerminalGeometryTest.java`
- Modify: `terminal-view/src/main/java/com/termux/view/TerminalView.java` (`getCursorX`, `getCursorY`, `getPointX`, `getPointY`, around lines 1206–1223)

**Interfaces:**
- Consumes: nothing.
- Produces: `static int TerminalGeometry.cursorColumn(float x, float fontWidth)`, `static int TerminalGeometry.cursorRow(float y, float lineSpacing, int topRow)`, `static int TerminalGeometry.pointX(int column, int columns, float fontWidth)`, `static int TerminalGeometry.pointY(int row, int topRow, float lineSpacing)`.

- [ ] **Step 1: Write the failing test**

```java
@Test public void cursorColumnIsTheTruncatedCell() {
    assertEquals(3, TerminalGeometry.cursorColumn(3.9f * 10, 10f));
}
@Test public void cursorRowIncludesTheFortyPixelOffset() {
    // Preserves the existing -40 in getCursorY; this is not derived, it is pinned.
    assertEquals(2, TerminalGeometry.cursorRow(40 + 2 * 20, 20f, 0));
    assertEquals(0, TerminalGeometry.cursorRow(40 + 2 * 20, 20f, -2));
}
@Test public void pointXClampsToTheColumnCount() {
    assertEquals(200, TerminalGeometry.pointX(20, 80, 10f));
    assertEquals(800, TerminalGeometry.pointX(200, 80, 10f));
}
@Test public void pointXRoundTripsWithCursorColumn() {
    int column = TerminalGeometry.cursorColumn(35.4f, 10f);
    assertEquals(30, TerminalGeometry.pointX(column, 80, 10f));
}
@Test public void pointYIsRelativeToTheTopRow() {
    assertEquals(0, TerminalGeometry.pointY(0, 0, 20f));
    assertEquals(20, TerminalGeometry.pointY(1, 0, 20f));
    assertEquals(0, TerminalGeometry.pointY(0, 1, 20f));
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :terminal-view:testDebugUnitTest --tests "com.termux.view.TerminalGeometryTest"`
Expected: compile failure.

- [ ] **Step 3: Implement `TerminalGeometry`**

Move the four bodies verbatim. `cursorRow` javadoc: "The `- 40` offset is existing behaviour, preserved from `TerminalView.getCursorY`; do not derive or correct it here."

- [ ] **Step 4: Delegate from `TerminalView`**

Keep the four public methods, each one line calling the helper with the current `mRenderer` fields and `mTopRow`. `getPointX` keeps passing `mEmulator.mColumns`.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :terminal-view:testDebugUnitTest --tests "com.termux.view.TerminalGeometryTest"`
Expected: 5 tests, 0 failures.

- [ ] **Step 6: Full suite, app build, commit**

Run: `./gradlew test` → 0 failures.
Run: `./gradlew :app:assembleDebug -PndkVersion=30.0.16248370` → BUILD SUCCESSFUL.

```bash
git add terminal-view
git commit -m "Changed(view): Extract cursor and point coordinates into TerminalGeometry"
```

---

### Task 6: Ledger, device-check note, final verification

**Files:**
- Modify: `docs/ledger/outstanding-work.md` (§3.2)
- Modify: `docs/ledger/manual-checks.md`

**Interfaces:**
- Consumes: the four helpers from Tasks 1–5.
- Produces: documentation only.

- [ ] **Step 1: Update §3.2**

Replace the `[open] TerminalView (1,661 lines)` bullet with a `[done 2026-11-04]` record of the four seams (names, what each owns, test class and case count), plus a narrowed `[open]` for the remaining View-bound half: gesture listener, scroll/viewport, selection delegation, autofill, cursor blinker, session binding — all needing Robolectric View infra, which `:terminal-view` still does not have. State the new line count.

- [ ] **Step 2: Add the deferred device check**

Append to `manual-checks.md` a check in the existing numbered style: after the split, on a device type on the soft keyboard — ASCII, a German umlaut, an emoji, a composed accent, `Ctrl-C`, `Alt-B` (backward word), `Tab`, and Enter — and confirm the shell receives exactly those bytes. Mark it deferred to the device phase.

- [ ] **Step 3: Final verification**

Run: `./gradlew test` → record the method and execution counts.
Run: `git status --short` → only the two ledger files modified before the commit.

- [ ] **Step 4: Commit**

```bash
git add docs/ledger
git commit -m "Changed(docs): Record the TerminalView pure-seam split"
```
