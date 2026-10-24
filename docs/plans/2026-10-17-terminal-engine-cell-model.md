# Terminal engine upgrade — workstream 1: the cell model — implementation plan

**Date:** 2026-10-17
**Status:** implemented. The storage, dirty rows, underline styles, per-cell link ids, grapheme
clusters and the `SGR 4:n`/`OSC 8` parsing halves all ship on `master` and are consumed by WS2, WS3
and WS5.
**Path:** bounded. The storage being changed already exists (`TerminalRow`, `TerminalBuffer`, `TextStyle`); this
plan widens the per-cell model in place rather than introducing a new one.
**Origin:** [`../ledger/outstanding-work.md`](../ledger/outstanding-work.md) (§2, the terminal engine program)
→ [`../specs/2026-10-17-terminal-engine-cell-model-design.md`](../specs/2026-10-17-terminal-engine-cell-model-design.md)
(approved 2026-10-17). This plan is the execution artifact for that spec.
**Scope:** `:terminal-emulator` **only** — dirty rows, underline styles, per-cell link ids, grapheme clusters,
plus the `SGR 4:n` and `OSC 8` parsing halves that make the new storage live. `:app`, `:terminal-view` and
`:termux-shared` get **zero diff**.

> **For agentic workers:** follow the Superpowers execution flow for this plan
> (`subagent-driven-development` if you want a fresh implementer + review per task, or `executing-plans` if you
> are implementing it inline). Steps use checkbox syntax. Each task is a self-contained TDD cycle that ends
> green and ends in a commit. Do not start a task before the previous task's test gate passed.

---

## Global constraints (apply to every task)

- **Java 8 source level.** `terminal-emulator/build.gradle` sets `sourceCompatibility`/`targetCompatibility`
  to `JavaVersion.VERSION_1_8` and Kotlin `jvmTarget = "1.8"`. No `var`, no `List.of`, no `String.repeat`,
  no `Map.of` in **main** sources. (Test sources in this module already use `List.of` and do compile, but new
  main code must stay Java 8.)
- **No new dependency.** The module only has `androidx.annotation` + `junit` (+ the app's Compose/test deps
  are not visible here).
- **No file outside `:terminal-emulator`.** Verify with `git diff --stat` after every task:
  no `terminal-emulator` is expected to be the *only* directory touched. `:app`, `:terminal-view`,
  `:termux-shared` must show no changes.
- **Rendering contract is untouched.** `mText` stays a packed `char[]` and `mStyle` stays one `long` per
  column; `TerminalRenderer` (`:terminal-view`) keeps reading `getStyle()` / `getText()` as it does today.
  Nothing in this plan requires a renderer change; underline styles degrade to plain underlines until WS2.
- **Underline style lives in bits 11–13** of the style word (bits 0–8 = effects, 9–10 = `TRUECOLOR_*`,
  bit 14 = unused, bit 15 = `TERMINAL_BITMAP`, 16–39 = background, 40–63 = foreground). Values are kitty's
  `SGR 4:n` numbering: `NONE=0, SINGLE=1, DOUBLE=2, CURLY=3, DOTTED=4, DASHED=5`. This is the *only* place those
  bits are written; `TextStyle.encode(...)` keeps its 9-bit effect mask and must keep producing bit-clean words.
- **Fill/erase is unlinked.** Any path that writes blanks (`blockSet`, `blockClear`, EL/ED, DECALN) must leave
  link id `0` on the cells it writes, even while a hyperlink is open.
- **Counters:** cap the link registry at **4096** live ids, and cap the OSC 8 argument buffer at **4096**
  bytes at parse time.
- **Style:** main sources use 4-space indentation; test sources use tabs. LF line endings, final newline
  (`.editorconfig`). Match the file you are editing.
- **Commits:** Conventional Commits with **capitalized** types per `AGENTS.md`, e.g.
  `Added(terminal): ...`, `Changed(terminal): ...`, `Fixed(terminal): ...` — capital first letter,
  present-tense description. Sign every commit (`git commit -S`); if the key is locked run
  `sh ~/.config/opencode/skills/gpg-commit/scripts/unlock-gpg.sh`.
- **Test gate for every task:** `./gradlew :terminal-emulator:test` (fast). Final gate: `./gradlew test`.
  Do **not** run `./gradlew clean` — it deletes the cached `bootstrap-*.zip` files and forces a ~115 MB
  re-download.

---

## Review focus (the five inputs most likely to bite a user, in order)

Each line is a condition the spec implies but no task's tests naturally exercise; the owning task must add the
test named here.

1. **Emoji ZWJ sequences** (`👨‍👩‍👧`, `🧑‍🚀`, flag + ZWJ) — a user expects **one glyph in 2 columns**.
   Today they fan out to 4–6 columns and the cursor drifts past them. → *Task 6*, test
   `testZwjFamilyOccupiesOneCluster`.
2. **Regional-indicator flags** (`🇦🇹`, an odd RI, or 3 RIs in a row) — a user expects `🇦🇹` to occupy
   **2 columns** and a lone RI to occupy 2 columns without corrupting the next cell. → *Task 6*, tests
   `testFlagPairOccupiesOneCluster` and `testLoneRegionalIndicatorWrapsAsWidthTwo`.
3. **Skin-tone modified and VS16 emoji** (`👨🏽`, `☀️`) — expect **2 columns**, not 4 (modifier) or 2 (VS16
   should not consume a column at all). → *Task 6*, test `testEmojiModifierAndVs16DoNotAddColumns`.
4. **A hyperlink left open across erase / scroll / resize** — a user expects erased cells to be click-free
   and surviving text to keep its link. Today a naive stamp puts the open URI on blanks, and stale ids
   survive on cells that were blanked while a link was open. → *Task 3*, tests
   `testEraseClearsLinks`, `testScrollKeepsLinksWithTheirRows`, `testResizeCarriesLinks`.
5. **`DECCARA`/`DECSED` over cells carrying an underline style** — a user expects the style to survive the
   rectangular attribute change (bits 11–13 are silently stripped by any `TextStyle.encode` round-trip).
   → *Task 2*, test `testRectangularChangeAttributesPreservesUnderlineStyle`.

---

## File structure

**Add (main):**
- `terminal-emulator/src/main/java/com/termux/terminal/GraphemeClusters.java` — cluster rules (task 6).

**Add (test):**
- `terminal-emulator/src/test/java/com/termux/terminal/DirtyTrackingTest.java` (task 1)
- `terminal-emulator/src/test/java/com/termux/terminal/LinkRegistryTest.java` (task 3)
- `terminal-emulator/src/test/java/com/termux/terminal/GraphemeClustersTest.java` (task 6)

**Modify (main):**
- `TextStyle.java` — underline constants + encode/decode (task 2).
- `TerminalRow.java` — dirty flag (1), underline accessor (2), `mLinkIds` + stamping + `copyInterval`/`clear`
  (3), cluster-aware walks + effective width (6).
- `TerminalBuffer.java` — dirty API + marks (1), `getUnderlineStyleAt` + `setOrClearEffect` preservation (2),
  link registry + GC + cap + current-link id + `setChar` overloads + erase/fill link `0` + resize link copy
  (3), resize cluster walk + `getWidthForWrite` (6).
- `TerminalEmulator.java` — `mUnderlineStyle` + `SGR 4:n` + `SGR 21/24/0` + `mStyle()` + DECSC/DECRC (4);
  `OSC 8` case + argument cap (5); print-path width (6).

**Modify (test):**
- `TextStyleTest.java` (2), `TerminalRowTest.java` (2, 3, 6), `RectangularAreasTest.java` (2),
  `ControlSequenceIntroducerTest.java` (4), `OperatingSystemControlTest.java` (5),
  `UnicodeInputTest.java` (6), `ResizeTest.java` (6), `TerminalTestCase.java` (6).

---

## Delivery (user decision 2026-10-17 — overrides spec §7's "six PRs")

The spec's ordering — dirty → underline → links → `SGR 4:n` → `OSC 8` → clusters — is kept. The **PR shape
changes**: do not open six PRs. Instead:

1. Work on one integration branch: `git checkout -b feature/terminal-engine-cell-model` (off `master`).
2. One commit per task on that branch (six commits).
3. When all six tasks and the branch gate are green: push the branch and open **one** PR from
   `feature/terminal-engine-cell-model` → `master`. (If per-task branches are used instead, merge each into
   the integration branch first, then PR from it — never PR a per-task branch straight to `master`.)

Commit the plan document itself (this file) plus the spec §7 amendment on `master` before branching.

---

## Task 1 — Dirty-row tracking

**Files:** modify `TerminalRow.java`, `TerminalBuffer.java`; add `DirtyTrackingTest.java`.

**Interfaces (exact):**

```java
// TerminalRow — field next to mHasTerminalBitmap, methods near the end of the class
boolean mDirty;                    // package-private, false for a fresh row
public boolean isDirty();
public void markDirty();
public void clearDirty();

// TerminalBuffer — public API
public boolean isRowDirty(int externalRow);   // false when the row is null
public long getChangeCounter();               // starts at 0, monotonic, never reset
public void markAllDirty();
public void clearDirtyState();                // clears every non-null row's flag; does NOT touch the counter

// TerminalBuffer — internal
private void markDirty(int internalRow);                    // null-safe no-op
private void markRowsDirty(int firstExternalRow, int lastExclusive);
```

**Rules:**
- The change counter ticks on **scroll** (`scrollDownOneLine`), **resize** (both the fast and the slow path)
  and **`clearTranscript`**. It is *not* reset by `clearDirtyState()` — the renderer stores the last value it
  saw, so a jump means "repaint everything".
- `resize` and `clearTranscript` call `markAllDirty()` and tick the counter.
- Marks are added where content is written, never where it is read: `allocateFullLineIfNecessary` and
  `getStyleAt`/`getTranscriptText`/`getSelectedText` must not mark anything dirty.

**Steps:**

- [ ] 1. Add `DirtyTrackingTest.java` (tabs, `extends TerminalTestCase`, buffer-level like `ScreenBufferTest`):

```java
public void testFreshBufferIsClean() {
    TerminalBuffer screen = new TerminalBuffer(5, 3, 3);
    for (int row = 0; row < 3; row++) assertFalse(screen.isRowDirty(row));
    assertEquals(0, screen.getChangeCounter());
}
public void testSetCharMarksOnlyItsRow() {
    TerminalBuffer screen = new TerminalBuffer(5, 3, 3);
    screen.setChar(0, 1, 'a', 0);
    assertFalse(screen.isRowDirty(0)); assertTrue(screen.isRowDirty(1)); assertFalse(screen.isRowDirty(2));
    screen.clearDirtyState();
    assertFalse(screen.isRowDirty(1)); assertEquals(0, screen.getChangeCounter());
}
public void testBlockSetAndBlockCopyMarkTouchedRows() { /* setChar via blockSet marks [sy, sy+h) */ }
public void testScrollTicksTheCounterAndMarksTheRegion() {
    TerminalBuffer screen = new TerminalBuffer(5, 3, 3);
    screen.setChar(0, 0, 'a', 0); screen.setChar(0, 2, 'c', 0); screen.clearDirtyState();
    screen.scrollDownOneLine(0, 3, 0);
    assertEquals(1, screen.getChangeCounter());
    for (int row = 0; row < 3; row++) assertTrue(screen.isRowDirty(row));
}
public void testResizeMarksAllAndTicks() { /* buffer resize(...) → all rows dirty, counter == 1 */ }
public void testClearTranscriptMarksAllAndTicks() { /* clearTranscript() → counter == 1 */ }
public void testSetOrClearEffectMarksRows() { /* buffer.setOrClearEffect(...) on a sub-rect */ }
public void testSetLineWrapMarksTheRow() { /* setLineWrap(1) → row 1 dirty, others not */ }
public void testReadsDoNotMarkDirty() { /* getStyleAt / getTranscriptText / allocate path → no marks */ }
```

- [ ] 2. Run `./gradlew :terminal-emulator:test` — expect compile failure on the missing methods.
- [ ] 3. Implement the `TerminalRow` flag/methods and the `TerminalBuffer` API + marks:
  - `scrollDownOneLine`: after rotating/blanking, `markRowsDirty(topMargin, bottomMargin)` and `mChangeCounter++`.
  - `blockCopy`: mark the destination rows `[dy, dy + h)`. (`copyInterval` writes via the destination row, so
    an explicit mark on the destination row before the copy loop is enough.)
  - `blockSet`/`setChar`: mark the target row (marks from the per-cell `setChar` are fine; no double work needed).
  - `resize`: `markAllDirty(); mChangeCounter++;` at the end of the method, covering both the fast and slow path.
  - `clearTranscript`: `markAllDirty(); mChangeCounter++;`.
  - `setOrClearEffect`: mark each row as the loop visits it.
  - `setLineWrap` / `clearLineWrap`: mark the `TerminalRow` the method already resolves before setting `mLineWrap`.
  - `mChangeCounter` is a `long` field initialised to `0`.
- [ ] 4. Run `./gradlew :terminal-emulator:test` — expect all green.
- [ ] 5. `git diff --stat` — only `TerminalRow.java`, `TerminalBuffer.java`, `DirtyTrackingTest.java`.
- [ ] 6. Commit (`Added(terminal): Add dirty-row tracking to the terminal cell model`).

---

## Task 2 — Underline style storage

**Files:** modify `TextStyle.java`, `TerminalRow.java`, `TerminalBuffer.java`; modify tests `TextStyleTest.java`,
`RectangularAreasTest.java`, `TerminalRowTest.java`.

**Interfaces (exact):**

```java
// TextStyle — public constants + helpers (bits 11..13)
public static final int UNDERLINE_STYLE_NONE = 0;
public static final int UNDERLINE_STYLE_SINGLE = 1;
public static final int UNDERLINE_STYLE_DOUBLE = 2;
public static final int UNDERLINE_STYLE_CURLY = 3;
public static final int UNDERLINE_STYLE_DOTTED = 4;
public static final int UNDERLINE_STYLE_DASHED = 5;
static final int UNDERLINE_STYLE_MASK = 0b111 << 11;        // 0x3800

public static int decodeUnderlineStyle(long style);          // (style >>> 11) & 0b111
static long encodeUnderline(long style, int underlineStyle); // (style & ~MASK) | ((underlineStyle & 0b111) << 11)

// TerminalRow
public int getUnderlineStyle(int column);                    // TextStyle.decodeUnderlineStyle(mStyle[column])

// TerminalBuffer
public int getUnderlineStyleAt(int externalRow, int column);
```

**Rules:**
- The `CHARACTER_ATTRIBUTE_UNDERLINE` bit remains "an underline is drawn"; the style value refines it.
  `NONE` + bit set = plain underline (this is what a DECCARA `Ps=4` over a plain cell yields, and what WS2
  renders until it understands the styles).
- `TextStyle.encode` keeps its 9-bit effect mask; do **not** widen it — the underline bits are applied
  *after* `encode` by `encodeUnderline`.
- **Carry the bits across every re-encode.** `TerminalBuffer.setOrClearEffect` (the DECCARA/DECSED/DECSERA
  path) rebuilds each word as `TextStyle.encode(fore, back, effect)`; wrap that with
  `TextStyle.encodeUnderline(..., TextStyle.decodeUnderlineStyle(currentStyle))` where `currentStyle` is the
  cell's existing word. This is the trap the spec calls out.

**Steps:**

- [ ] 1. Add failing tests to `TextStyleTest.java` (`testUnderlineStyleRoundTrip`: for each of 1..5, encode
  over a word with `CHARACTER_ATTRIBUTE_UNDERLINE` and assert `decodeUnderlineStyle` returns it while
  `decodeEffect`/`decodeForeColor`/`decodeBackColor` are unchanged; `testUnderlineStyleNone` for `0`;
  `testUnderlineStyleDoesNotDisturbTrueColor`: encode 24-bit fg/bg + `UNDERLINE`, apply `CURLY`, assert all
  four decodes still agree).
- [ ] 2. Run the suite — expect failure. Implement the `TextStyle` constants + `decodeUnderlineStyle` +
  `encodeUnderline`.
- [ ] 3. Add failing test to `RectangularAreasTest.java` (tabs, uses `withTerminalSized` + `enterString`):

```java
/**
 * DECCARA re-encodes the whole style word for every cell in the rectangle, so the underline style
 * stored in bits 11-13 must be carried across, not dropped.
 */
public void testRectangularChangeAttributesPreservesUnderlineStyle() {
    withTerminalSized(3, 3).enterString("\033[4:3mABC\033[1;1;3;3;4$r");
    for (int column = 0; column < 3; column++) {
        assertEquals(TextStyle.UNDERLINE_STYLE_CURLY,
            TextStyle.decodeUnderlineStyle(getStyleAt(0, column)));
    }
}
```

  (Arg convention is the one the existing DECSERA tests use: `top;left;bottom;right` select the whole 3×3
  screen, then `Ps = 4` means "set underline". If the selected rectangle turns out narrower than the screen
  in this buffer's convention, widen the rectangle — do not weaken the assertion.)
- [ ] 4. Add failing tests to `TerminalRowTest.java`: `testUnderlineStyleIsPerColumn` (`row.setChar(0, 'a',
  TextStyle.encodeUnderline(0, TextStyle.UNDERLINE_STYLE_DOTTED))` → `row.getUnderlineStyle(0) == DOTTED`,
  `row.getUnderlineStyle(1) == NONE`); `testCopyIntervalCarriesUnderlineStyle`.
- [ ] 5. Implement `TerminalRow.getUnderlineStyle`, `TerminalBuffer.getUnderlineStyleAt` (resolve with
  `allocateFullLineIfNecessary(externalToInternalRow(externalRow))` exactly like `getStyleAt`) and the
  `setOrClearEffect` carry.
- [ ] 6. Run `./gradlew :terminal-emulator:test` — expect all green (existing style assertions must be
  unaffected because a default word has `NONE` in bits 11–13).
- [ ] 7. `git diff --stat`; commit (`Added(terminal): Store underline style in the terminal style word`).

---

## Task 3 — Link registry and per-cell link ids

**Files:** modify `TerminalRow.java`, `TerminalBuffer.java`, `TerminalEmulator.java` (erase call sites),
  `TerminalBitmap.java` (bitmap-cell call site); modify tests `TerminalRowTest.java`; add `LinkRegistryTest.java`.

**Interfaces (exact):**

```java
// TerminalRow
int[] mLinkIds;                       // package-private; null until the first non-zero id is stored
public int getLinkId(int column);     // 0 when mLinkIds == null
void setLinkId(int column, int linkId);          // allocates on first non-zero id; 0 on a null array is a no-op
void clearLinkIds();                             // Arrays.fill(mLinkIds, 0) when non-null
public void setChar(int columnToSet, int codePoint, long style);              // delegates with linkId 0
public void setChar(int columnToSet, int codePoint, long style, int linkId);

// TerminalBuffer
private static final int LINK_IDS_MAX = 4096;
int mMaxLinkIds = LINK_IDS_MAX;                  // package-private so tests can lower it
private final HashMap<String, Integer> mLinkIdsByUri = new HashMap<>();
private final HashMap<Integer, String> mLinkUrisById = new HashMap<>();
private int mNextLinkId = 1;                     // monotonic, never reused, 0 reserved for "no link"
private int mCurrentLinkId;                      // 0 = no hyperlink open

public synchronized int registerLink(String uri);   // dedupe by uri; GC then return 0 when full
public synchronized String getLink(int id);         // null for 0 / unknown
public synchronized void setCurrentLinkId(int linkId);
public synchronized int getCurrentLinkId();
public void setLinkAt(int column, int externalRow, int linkId);
public int getLinkIdAt(int externalRow, int column);
public void setChar(int column, int row, int codePoint, long style);                  // stamps mCurrentLinkId
public void setChar(int column, int row, int codePoint, long style, int linkId);
private synchronized void doLinksGC();
```

**Rules (user decision 2026-10-17 — row-level stamping):**

- `TerminalRow.setChar(columnToSet, codePoint, style, linkId)` is the single place that decides which cell
  gets the id:
  - **BMP width-1 fast path** (the early return): `setLinkId(columnToSet, linkId)` — a genuine overwrite.
  - **Non-combining write**: `setLinkId(columnToSet, linkId)` after the wide-char neighbour handling.
  - **Combining/zero-width write** (a continuation appended to an existing cell): do **not** touch
    `mLinkIds` — the cell keeps the link it was created with.
  - **Blanked neighbours** (`setChar(columnToSet ± 1, ' ', style)` inside `setChar`): pass `linkId` `0`.
- `TerminalRow.copyInterval` passes the *source* column's id: `setChar(destinationX, codePoint,
  line.getStyle(sourceX1), line.getLinkId(sourceX1))` — the exact analogue of the style propagation already
  there.
- `TerminalRow.clear(style)` also `clearLinkIds()`.
- Erase/fill paths pass `0` explicitly: `TerminalBuffer.blockSet` (`setChar(sx + x, sy + y, val, style, 0)`),
  the two `mScreen.setChar(...)` fill sites in `TerminalEmulator` (the `fillChar` erase paths) and
  `TerminalBitmap`'s bitmap-cell write. `blockCopyLinesDown` moves whole `TerminalRow` objects, so ids travel
  with their row for free.
- `TerminalBuffer.setChar(column, row, codePoint, style)` = `setChar(..., mCurrentLinkId)`. The trailing
  overload writes the id onto the row via the row's `setChar(..., linkId)`.
- `resize`'s re-wrap loop tracks `linkIdAtCol = oldLine.getLinkId(currentOldCol)` alongside `styleAtCol` and
  passes both into the explicit overload.
- Registry: `registerLink` returns the existing id for a known uri; otherwise allocates `mNextLinkId++`
  (skipping `0` on the theoretical overflow). When `mLinkUrisById.size() >= mMaxLinkIds` it runs
  `doLinksGC()` and, if still full, logs through the same `Logger`/`mClient` path the bitmap cap uses and
  returns `0`. `doLinksGC()` walks every non-null row's `mLinkIds`, collects live ids, and removes every
  registry entry not referenced — no timer, unlike `doTerminalBitmapsGC`, so tests are deterministic.
- Ids are never reused after GC: a stale cell id resolves to `null`, never to a different URI.
- Writes validate like the existing ones (`TerminalRow.setChar` throws
  `IllegalArgumentException` for an out-of-range `columnToSet`, line 157): keep that check on the new overload and
  give `setLinkAt` the same treatment. Reads stay unvalidated like `getStyleAt`.

**Steps:**

- [ ] 1. Add failing `TerminalRowTest` tests (tabs):
  `testLinkIdsAreLazy` (`assertNull(row.mLinkIds)` for a fresh row; a non-zero id allocates; `getLinkId` for
  an untouched column is `0`); `testOverwriteClearsTheLink` (`setChar(..., 5)` then `setChar(..., 0)` →
  `getLinkId == 0`); `testContinuationKeepsTheCellLink` (wide char at column 0 with id 5, then a combining
  char at column 1 with id 7 → column 0 still reports 5, column 1 reports 0);
  `testBlankedNeighbourLosesItsLink` (wide char with a link at columns 0–1, then overwrite column 0 with a
  narrow char → `getLinkId(1) == 0`); `testCopyIntervalCarriesLinkIds`; `testClearDropsLinkIds`.
- [ ] 2. Run — expect failure. Implement `TerminalRow` (`mLinkIds`, `getLinkId`, `setLinkId`,
  `clearLinkIds`, the `setChar` overload + stamping, `copyInterval`, `clear`).
- [ ] 3. Add failing `LinkRegistryTest.java` (tabs, buffer-level):
  `testSetCharStampsTheCurrentLink` (`registerLink("https://example.com")` → `setCurrentLinkId` →
  `setChar(0, 0, 'a', 0)` → `getLinkIdAt(0, 0) == id`, `getLinkIdAt(0, 1) == 0`);
  `testRegisterLinkDeduplicates`; `testGetLinkResolvesAndUnknownIsNull` (`getLink(0)` and an unused id are
  null); `testEraseClearsLinks` (stamp cells, then `blockSet(0, 0, 5, 1, ' ', 0)` → all ids `0`);
  `testCapReturnsZeroWhenFull` (lower `screen.mMaxLinkIds = 2`, reference two ids from cells, third
  registration returns `0` and does not appear in the registry); `testGcFreesUnreferencedIds` (lower the cap,
  register ids never stamped on a row, then register once more → succeeds and the unreferenced ids are gone);
  `testScrollKeepsLinksWithTheirRows` (stamp row 0, `scrollDownOneLine(0, 3, 0)`, the link is now on the
  transcript row and the screen row is unlinked); `testResizeCarriesLinks` (stamp a cell, `resize` to fewer
  columns, the link is still at its new position).
- [ ] 4. Implement the registry, `setCurrentLinkId`/`getCurrentLinkId`, `setLinkAt`/`getLinkIdAt`,
  `doLinksGC`, the `setChar` overloads, the `blockSet`/erase/`TerminalBitmap` `0` arguments, and the `resize`
  link tracking.
- [ ] 5. Run `./gradlew :terminal-emulator:test` — expect all green.
- [ ] 6. `git diff --stat`; commit (`Added(terminal): Add per-cell hyperlink ids to the terminal cell model`).

---

## Task 4 — Wire `SGR 4:n` underline styles

**Files:** modify `TerminalEmulator.java`; modify `ControlSequenceIntroducerTest.java`.

**Interfaces (exact):**

```java
// TerminalEmulator
int mUnderlineStyle;                          // package-private, like mEffect; 0 = NONE
// SavedScreenState: int mSavedUnderlineStyle;
```

**Rules:**
- `mStyle()` is `TextStyle.encodeUnderline(TextStyle.encode(mForeColor, mBackColor, mEffect), mUnderlineStyle)`.
- `SGR 4:n` (already parsed but discarded today): `n == 0` clears `CHARACTER_ATTRIBUTE_UNDERLINE` **and**
  sets `UNDERLINE_STYLE_NONE`; `1..5` sets the underline bit and stores that value; anything else
  (`4:9`, negative) sets the bit and clamps to `UNDERLINE_STYLE_SINGLE`.
- `SGR 21` (currently unhandled) sets the underline bit and `UNDERLINE_STYLE_DOUBLE` (xterm). `SGR 24` clears
  the bit and sets `NONE`. `SGR 0` (reset) sets `NONE` alongside `mEffect = 0`.
- DECSC/DECRC carry the field (`mSavedUnderlineStyle`), and `reset()` zeroes it where it zeroes
  `mSavedEffect` for both saved states. (Do not add an `mEffect`/`mUnderlineStyle` reset to the current
  style — `reset()` doesn't reset `mEffect` today; match that.)
- `4:0` on an unknown cell must not leave an underline bit behind (the attribute bit is the "is drawn" flag).

**Steps:**

- [ ] 1. Rewrite `testUnderlineVariants` in `ControlSequenceIntroducerTest.java` to read the stored style —
  for suffixes `""`, `":1"`…`":5"` and stops `"24"`, `"4:0"`: set `4<suffix>`, assert `mEffect` has
  `CHARACTER_ATTRIBUTE_UNDERLINE`, then stop and assert only `mEffect == 0` and `mUnderlineStyle == NONE`.
  Extend `testManyParameters` with `assertEquals(TextStyle.UNDERLINE_STYLE_DOUBLE, mTerminal.mUnderlineStyle)`
  (the dropped trailing params must not corrupt the stored style). Add
  `testUnderlineStyleIsStoredPerCell` (`4:1`…`4:5`, `4`, `4:0`, `24`, `4:9`, `21`),
  `testResetClearsUnderlineStyle` (`\033[4:3mA\033[0mB`), and
  `testDecscDecrcRoundTripUnderlineStyle` (`\033[4:4m` → save → `\033[24m` → restore, asserting
  `mUnderlineStyle` at each step, following the existing test's `mEffect` idiom).
- [ ] 2. Run — expect failure.
- [ ] 3. Implement the field, `mStyle()`, the `4:n`/`21`/`24`/`0` branches, save/restore, and `reset()`.
- [ ] 4. Run `./gradlew :terminal-emulator:test` — expect all green.
- [ ] 5. `git diff --stat`; commit (`Changed(terminal): Apply SGR 4:n underline styles to cells`).

---

## Task 5 — Wire `OSC 8` hyperlinks

**Files:** modify `TerminalEmulator.java`; modify `OperatingSystemControlTest.java`.

**Rules:**
- `doOsc`'s `switch (value)` gains `case 8`; `textParameter` is everything after the first `;`, i.e.
  `params;URI`. Split on the **first** `;` only — the URI may contain `;`. Params are ignored. `uri.isEmpty()`
  closes the link (`setCurrentLinkId(0)`); otherwise `setCurrentLinkId(mScreen.registerLink(uri))`.
  A payload with no second `;` (e.g. `OSC 8;` or `OSC 8;`) likewise closes.
- `setOscTypeVariables()` gains `case 8` setting `terminalControlArgsMaxLength` to **4096** (a real bound:
  the registry stores the URI verbatim, so 4096 ids × 4096 bytes is the worst case). Note the method's early
  return when the args contain no `:` — a `https://` URI always contains one, so the cap applies in practice.
  Exceeding the cap is handled by `collectTerminalControlArgs`, which *drops the whole sequence* (no link is
  registered and the currently open link is unchanged).
- Per-buffer state: `mScreen` may be the main or the alternate buffer, so the open link and registry are
  naturally per-buffer.

**Steps:**

- [ ] 1. Add failing tests to `OperatingSystemControlTest.java` (tabs, `enterString` with `\033]…\007`):
  `testOsc8StampsCellsWithLink` (`\033]8;;https://example.com\007ab\033]8;;\007cd` → cells 0–1 share an id
  that resolves to that URI, cells 2–3 are `0`); `testOsc8ParamsAreIgnored`
  (`\033]8;id=42;https://example.com\007`); `testOsc8UriKeepsEmbeddedSemicolons`
  (`https://example.com/a;b?c=1` resolves whole); `testOsc8EmptyUriClosesLink`;
  `testOsc8IdenticalUrisShareAnId`; `testOsc8OversizedPayloadIsIgnored` (open a link to
  `https://keep.example`, send `\033]8;;https://` + 5000 filler chars + `\007`, then write a char — the
  filler exceeds the 4096 cap, so the sequence is dropped and the char still carries the *keep* link id).
- [ ] 2. Run — expect failure.
- [ ] 3. Implement `case 8` in `doOsc` and the `setOscTypeVariables` cap.
- [ ] 4. Run `./gradlew :terminal-emulator:test` — expect all green.
- [ ] 5. `git diff --stat`; commit (`Added(terminal): Parse OSC 8 hyperlinks and stamp cells`).

---

## Task 6 — Grapheme-cluster absorption

**Files:** add `GraphemeClusters.java` + `GraphemeClustersTest.java`; modify `TerminalRow.java`,
  `TerminalBuffer.java`, `TerminalEmulator.java`, `TerminalTestCase.java`; modify tests `TerminalRowTest.java`,
  `UnicodeInputTest.java`, `ResizeTest.java`.

**Interfaces (exact):**

```java
// GraphemeClusters (package-private final class)
static final int ZWJ = 0x200D;
static final int VS16 = 0xFE0F;
static boolean isRegionalIndicator(int codePoint);            // 0x1F1E6..0x1F1FF
static boolean isSkinToneModifier(int codePoint);             // 0x1F3FB..0x1F3FF
static int baseWidth(int codePoint);                          // RI -> 2, else WcWidth.width(codePoint)
static boolean isContinuation(int precedingCodePoint, int codePoint);
static int clusterEnd(char[] text, int index, int spaceUsed);  // > index always
static int clusterWidth(char[] text, int index, int spaceUsed); // width of the cluster starting at index

// TerminalRow (private helpers + one query)
int getLastCodePointOfColumn(int column);   // last stored code point of the cluster at that column, or -1

// TerminalBuffer
public int getWidthForWrite(int externalRow, int column, int codePoint);
```

**Rules:**
- **Base + continuations.** A cluster's width is the *base* code point's width. A code point is a
  continuation of the preceding one when the preceding code point is `ZWJ`; or the preceding is a regional
  indicator and this is one too; or this is a skin-tone modifier; or its width is `<= 0`.
- **Write path** (`TerminalEmulator`'s print path): replace `WcWidth.width(codePoint)` with
  `mScreen.getWidthForWrite(mCursorRow, mCursorCol, codePoint)`. `getWidthForWrite` returns `0` when the cell
  before the cursor can absorb the code point, else `GraphemeClusters.baseWidth`. Fast path: when the row is
  null or `!row.mHasNonOneWidthOrSurrogateChars` and the code point is not a regional indicator, no
  continuation is possible — return `baseWidth` directly (this keeps plain-ASCII output allocation-free).
- **Walkers** — `TerminalRow.findStartOfColumn`, `TerminalRow.wideDisplayCharacterStartingAt`,
  `TerminalRow.copyInterval`, `TerminalBuffer.resize`'s re-wrap loop and
  `TerminalTestCase.assertInvariants` must advance by **cluster** width, consuming each cluster's
  continuations in one step. Keep the original termination conditions and bounds of each walker (the existing
  `TerminalRowTest` `findStartOfColumn`/`writeChar`/`getSpaceUsed` tests are the regression net).
- No new storage: a cluster is just code points stored in one column, as combining characters already are.
- Existing behaviour is unchanged for every non-cluster input: `clusterWidth` equals the naive sum except for
  ZWJ sequences, a second regional indicator, a skin-tone modifier and VS16.
- `TerminalRow.setChar`'s fast path already requires `WcWidth.width(codePoint) == 1`, so it needs no change
  (a continuation is never width 1 except a second RI, which is supplementary and takes the slow path).

**Steps:**

- [ ] 1. Add `GraphemeClustersTest.java` (tabs, plain `TestCase`): `baseWidth` for ASCII/wide/combining/`ZWJ`/
  RI/VS16; `isContinuation` for each rule and its negative (RI then ASCII, ASCII then ZWJ, VS16 then emoji);
  `clusterEnd`/`clusterWidth` on `"a"`, `"a" + combining`, `RI + RI`, `emoji + ZWJ + emoji`, `emoji +
  modifier`, and `"a"` followed by a second cluster.
- [ ] 2. Run — expect compile failure. Implement `GraphemeClusters`.
- [ ] 3. Add failing row-level tests to `TerminalRowTest.java`: `testZwjClusterOccupiesOneColumn`,
  `testFlagPairOccupiesOneColumn` (a 2-code-point-pair of RIs leaves column 1 free for the next char),
  `testLoneRegionalIndicatorIsWidthTwo`, `testSkinToneModifierAddsNoColumn`,
  `testGetLastCodePointOfColumn` (returns the trailing continuation; `-1` for an empty column).
- [ ] 4. Add failing emulator-level tests — `UnicodeInputTest.java`:
  `testZwjFamilyOccupiesOneCluster`, `testFlagPairOccupiesOneCluster`,
  `testLoneRegionalIndicatorWrapsAsWidthTwo`, `testEmojiModifierAndVs16DoNotAddColumns`, each asserting the
  stored code points with `assertLineStartsWith(0, …)` or `assertLinesAre(...)`, the cursor with
  `assertCursorAt(...)`, and the fact that `enterString` runs `assertInvariants`. Add the resize case to
  `ResizeTest.java`: `testZwjClusterSurvivesResize` (write the family, then `resize(20, 4)`, then assert the
  cluster is still one column and the cursor did not move past it).
- [ ] 5. Implement: the emulator print-path swap, `TerminalBuffer.getWidthForWrite`,
  `TerminalRow.getLastCodePointOfColumn`, the cluster-aware `findStartOfColumn` /
  `wideDisplayCharacterStartingAt` / `copyInterval`, the cluster-aware `resize` re-wrap walk, and the
  cluster-aware `TerminalTestCase.assertInvariants` (walk clusters, sum `clusterWidth`, assert the total
  equals `mColumns`).
- [ ] 6. Run `./gradlew :terminal-emulator:test` — expect all green (including every pre-existing wide-char,
  combining, wrap and resize test).
- [ ] 7. `git diff --stat`; commit (`Changed(terminal): Absorb grapheme clusters into single terminal columns`).

---

## Branch gate (after all six tasks)

- [ ] `./gradlew test` — the whole repo's unit tests, green (`:terminal-emulator` + `:app`). Do not run
  `clean`.
- [ ] `git diff --stat $(git merge-base master HEAD)..HEAD` — every changed path is under
  `terminal-emulator/`, except the docs commits already on `master`.
- [ ] Memory check on the new storage: `new TerminalRow(...)` does not allocate `mLinkIds` and is not dirty
  (asserted in task 3's `testLinkIdsAreLazy`); the cell word remains one `long` and `mText` one `char[]`.
- [ ] Sanity-run the engine against a real emoji/link session on a device (or in the app) and note anything
  that looks wrong before opening the PR — the renderer still draws plain underlines and ignores link ids,
  which is expected for WS1.
- [ ] Push and open the single PR to `master` (see *Delivery*).

---

## Out of scope (do not start here)

Renderer/dirty-region drawing (WS2), text shaping and ligatures (WS3), kitty graphics and image-placement
unification (WS4), link click handling and the rest of WS5, and every `:termux-shared` shim (WS6). The spec's
§3.2 wording "the buffer stamps the cell" is refined by this plan: the *row* decides the exact column (it
alone knows about wide-char redirects and blanked neighbours), which the user confirmed on 2026-10-17.
