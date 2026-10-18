# Plan: WS5 — OSC 8 Hyperlinks End-to-End + SGR 58 Underline Color

**Spec:** `docs/specs/2026-10-17-osc8-hyperlinks-sgr58-underline-design.md` (approved by user 2026-10-17)
**Program:** `docs/ideas/2026-10-14-terminal-engine-upgrade-idea.md` (workstream 5)
**Branch base:** `309d3df4` (spec commit on master). Work proceeds on a feature branch with one
commit per task.

## Architecture summary

WS1 already stamps per-cell link ids from `OSC 8` (`TerminalRow.mLinkIds`, `TerminalBuffer`
registry with `registerLink`/`getLink`/`getLinkIdAt`), and WS2 already renders SGR `4:n` underline
styles. WS5 closes the loop in three vertical slices, all consuming existing infrastructure:

1. **SGR 58 storage** — `TerminalEmulator.mUnderlineColor` is already parsed (`CSI 58;5;n` /
   `CSI 58;2;r;g;b`, `CSI 59`) but never stored per cell and never rendered. A lazily-allocated
   `int[] mUnderlineColors` side array on `TerminalRow` (mirroring `mLinkIds` exactly) carries it;
   `TerminalBuffer.setChar` grows a 6th parameter; the emulator's single main-write call site passes
   its state explicitly (keeping the buffer free of emulator-state coupling).
2. **Renderer** — `TerminalRenderer.render()`'s run loop gains per-run link-id and underline-color
   tracking (run-break on change); `drawTextRun` draws a single underline for link cells (foreground
   color) and draws *all* underlines manually in the resolved color when a stored SGR-58 color
   exists (`drawUnderline` gains an `UNDERLINE_STYLE_SINGLE` case).
3. **Tap hit-test** — `TerminalView`'s `onSingleTapUp` resolves the tapped cell through
   `getLinkIdAt` → `getLink` → `LinkUris.sanitize` (new allow-list helper) and dispatches the new
   `default void onLinkClicked(String)` on `TerminalViewClient`.

## Key facts for the implementer (verified against the tree at `309d3df4`)

- Java 8 source, 4-space indent, LF, final newline. No formatter configured.
- Commit messages: capitalized Conventional Commits — `Added`, `Changed`, `Fixed`, …
- `TerminalRow.setLinkId` (line ~359) / `clearLinkIds` (~370) / `getLinkId` (~351) and the
  `copyInterval` link threading (line 88) are the patterns the underline-color side array mirrors,
  including the lazy-allocation rule and the existing WS1 tests in `TerminalRowTest` (lines 456–504)
  that the new tests sit next to.
- `TerminalBuffer`: 4-arg `setChar` (line 623) delegates to 5-arg with `mCurrentLinkId`;
  `getLinkIdAt` (664) / `setLinkAt` (670) / `registerLink` (685) / `getLink` (706) /
  `getCurrentLinkId` (715) exist. Line ~440–480 is the resize/rebuild loop that threads
  `linkIdAtCol`; line ~966 is the column-shrink fill (`keepVisualAttributes ? getStyleAt : style,
  0`); line ~1749 is an erase fill.
- `TerminalEmulator`: `mUnderlineColor` field (line 419) is **never initialized** (int default 0 =
  palette black) — real bug WS5 fixes; SGR 58/59 already parse (lines ~2552–2600); main write is
  `mScreen.setChar(column, mCursorRow, codePoint, getStyle())` (line 3469); `getStyle()` (3066)
  does not encode underline color (and must not — side array, not style word); `reset()` (3527)
  initializes `mForeColor`/`mBackColor` at line ~3535; DECSC/DECRC save `mUnderlineStyle` at
  lines 2118/2132 via `SavedScreenState`.
- `TerminalRenderer`: run loop starts line 105; run-break condition line 155
  (`style != lastRunStyle || insideCursor != … || insideSelection != … || fontWidthMismatch ||
  lastRunFontWidthMismatch`); the sixel-bitmap fast path (lines ~140–149) resets run state;
  `drawTextRun` (line 209) decides `paintUnderline` (single → `Paint.setUnderlineText`, drawn in
  text color) vs `customUnderline` (decorated → `drawUnderline(canvas, left, right, y, foreColor,
  underlineStyle)`, line 318, which computes `underlineY = baseline + mFontAscent * -0.12f` clamped
  and has **no** `UNDERLINE_STYLE_SINGLE` case today); two `drawTextRun` call sites (in-loop line
  ~169 and tail-of-row line ~196).
- `TerminalView`: `onSingleTapUp` listener body lines 175–184 (`mEmulator == null` guard →
  `isSelectingText()` early-return → `requestFocus()` → `mClient.onSingleTapUp(event)` →
  `return true`); `getColumnAndRow(event, relativeToScroll)` (public, line ~589) is the
  established, scroll-aware cell conversion (`row = (y - mFontLineSpacingAndAscent)/mFontLineSpacing
  + mTopRow` when `relativeToScroll`), used by selection and mouse reporting.
- `:terminal-view` tests are JUnit-3-style `junit.framework.TestCase` (see `RowInvalidationTest`,
  `RowInvalidationBufferTest`), plain JVM, no Robolectric dependency. `TerminalBuffer` can be
  constructed directly (`new TerminalBuffer(5, 10, 3)`), and a full `TerminalEmulator` is
  constructible in plain JVM with a stub `TerminalOutput` (mirror `TerminalTestCase.MockTerminalOutput`).
- `:terminal-emulator` tests extend `TerminalTestCase` (`withTerminalSized(cols, rows)`, `enterString`,
  `assertLineIs`, `assertForegroundColorAt(row, col, idx)` — `ControlSequenceIntroducerTest` is the
  home of the existing SGR tests, e.g. `testUnderlineVariants`).
- `:app` tests use Robolectric (`includeAndroidResources = true`); `./gradlew test` is the gate
  (`:app` suite ~2–3 min).

## Spec deviations (approved-intent corrections; folded back into the spec in the final task)

1. **Hit-test coordinate math (spec §4.2):** the spec wrote `row = y / mFontLineSpacing`, which
   ignores both the descent offset (`mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent`)
   **and the scroll offset `mTopRow`** — tapping a link while scrolled back would hit the wrong
   row. The plan uses the established `getColumnAndRow(event, true)` instead (same conversion
   selection and mouse reporting use). Spec §4.2 amended in the final task.
2. **`reset()` init (spec §6.4):** `mUnderlineColor` was never initialized; `reset()` now sets it
   to `TextStyle.COLOR_INDEX_FOREGROUND` alongside `mForeColor`/`mBackColor`.
3. **DECSC/DECRC (spec §6.4):** `SavedScreenState` gains `mSavedUnderlineColor`, saved/restored
   next to `mSavedUnderlineStyle` (2-line symmetry; without it, restore leaves the underline color
   out of sync with the restored fg/bg).
4. **App-side dispatch (spec §8 vs §9):** the spec's manual test says "confirm browser opens", but
   §9 left the app without an override. Task 6 adds a small `onLinkClicked` override in
   `TermuxTerminalViewClient` that launches `ACTION_VIEW` so the feature (and the manual test) is
   real end-to-end.

## Tasks

Order is strict for 2 → 3 → 4 (storage → plumbing → rendering); 1 and 5 are independent and may
interleave; 6 depends on 5; 7 is last.

---

### Task 1 — `LinkUris` scheme allow-list helper (`:terminal-view`)

**Files**
- new `terminal-view/src/main/java/com/termux/view/LinkUris.java`
- new `terminal-view/src/test/java/com/termux/view/LinkUrisTest.java`

**Test first** (`LinkUrisTest extends junit.framework.TestCase`, style of `RowInvalidationTest`):
- `testAcceptsAllowedSchemes` — `http://`, `https://`, `ftp://`, `mailto:` each pass through.
- `testSchemeMatchIsCaseInsensitive` — `"HTTPS://EXAMPLE.COM"`, `"MailTo:a@b.c"` accepted.
- `testTrimsSurroundingWhitespace` — `"  http://example.com \n"` → `"http://example.com"`.
- `testRejectsDisallowedSchemes` — `file:///etc/passwd`, `intent://…`, `javascript:alert(1)`,
  `data:text/html,…` → null.
- `testRejectsSchemelessStrings` — `"example.com"`, `"/etc/passwd"` → null.
- `testRejectsEmbeddedControlCharacters` — `"http://exam\tple.com"` (embedded tab), a string with
  an embedded newline → null.
- `testNullAndEmpty` — null, `""`, `"   "` → null.

**Implementation**
```java
package com.termux.view;

/** Allow-list sanitization for URIs arriving from untrusted terminal output (OSC 8). */
public final class LinkUris {
    private static final String[] ALLOWED_PREFIXES = {"http://", "https://", "ftp://", "mailto:"};

    private LinkUris() {}

    /** @return the trimmed URI if its scheme is allowed, otherwise {@code null}. */
    public static String sanitize(String uri) { … }
}
```
Behavior: null/blank → null; `trim()`; reject if any char `< 0x20` or `== 0x7f` remains after
trim; accept on case-insensitive `startsWith` against the four prefixes; else null. No logging
here (the caller logs). Note: `trim()` does not remove embedded control chars, so the
control-character check runs on the trimmed string.

**Commit:** `Added(terminal-view): Add LinkUris scheme allow-list helper`

---

### Task 2 — `TerminalRow` underline-color side array (`:terminal-emulator`)

**Files**
- `terminal-emulator/src/main/java/com/termux/terminal/TerminalRow.java`
- `terminal-emulator/src/test/java/com/termux/terminal/TerminalRowTest.java` (additions)

**Test first** (next to WS1's link tests, lines 456–504; sentinel is
`TextStyle.COLOR_INDEX_FOREGROUND`, a public constant):
- `testUnderlineColorsAreLazy` — fresh row: `mUnderlineColors` null, `getUnderlineColor(0)` is
  the sentinel; `setUnderlineColor(2, 7)` → array non-null, `[2] == 7`, `[1] == sentinel`.
- `testSettingDefaultOnFreshRowDoesNotAllocate` — `setUnderlineColor(0, sentinel)` → array still
  null, value reads sentinel.
- `testOverwriteWithDefaultResetsTheColor` — set 7, then sentinel → reads sentinel.
- `testSetCharStampsUnderlineColor` — `row.setChar(0, 'a', 0L, 0, 9)` → `getUnderlineColor(0) == 9`;
  the 4-arg `setChar(…, linkId)` writes the sentinel.
- `testCopyIntervalCarriesUnderlineColors` — mirror `testCopyIntervalCarriesLinkIds` (line 499).
- `testClearResetsUnderlineColors` — set colors, `clear()` → all cells read sentinel (array may
  stay allocated).

**Implementation**
- Field `int[] mUnderlineColors;` (package-private, next to `mLinkIds`, javadoc: lazily
  allocated, `null` = all-default, sentinel `TextStyle.COLOR_INDEX_FOREGROUND` = per-cell default).
- `public int getUnderlineColor(int column)` — null array → sentinel.
- `void setUnderlineColor(int column, int color)` — bounds-check like `setLinkId`
  (`IllegalArgumentException`); if array null and color is the sentinel → no-op; allocate
  `new int[mColumns]` **filled with the sentinel** on first write; store.
- `void clearUnderlineColors()` — `Arrays.fill(mUnderlineColors, sentinel)` if allocated.
- Extend `setChar`: existing `setChar(int columnToSet, int codePoint, long style, int linkId)`
  (line 202) delegates to new `setChar(int columnToSet, int codePoint, long style, int linkId,
  int underlineColor)`; inside, stamp `setUnderlineColor(columnToSet, underlineColor)` under the
  exact condition `setLinkId` is stamped (line ~223: `if (!newIsCombining && baseWidth > 0)`).
- `copyInterval` (line 74): add local `int underlineColorAtCol = line.getUnderlineColor(...)`
  alongside `linkIdAtCol` (line 88, same zero-width rule) and pass it into the same `setChar`
  call(s) that already receive `linkId`.
- `clear()` (line ~190): call `clearUnderlineColors()` next to `clearLinkIds()`.

**Commit:** `Added(terminal-emulator): Add TerminalRow underline-color side array`

---

### Task 3 — `TerminalBuffer` + `TerminalEmulator` wiring (`:terminal-emulator`)

**Files**
- `terminal-emulator/src/main/java/com/termux/terminal/TerminalBuffer.java`
- `terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java`
- `terminal-emulator/src/test/java/com/termux/terminal/ControlSequenceIntroducerTest.java` (additions)

**Implementation**
- `TerminalBuffer`:
  - New real overload `public void setChar(int column, int row, int codePoint, long style,
    int linkId, int underlineColor)` → bounds checks as today → `allocateFullLineIfNecessary(row)
    .setChar(column, codePoint, style, linkId, underlineColor)`. The 5-arg delegates with
    `TextStyle.COLOR_INDEX_FOREGROUND`; the 4-arg keeps delegating with `mCurrentLinkId`.
  - New `public int getUnderlineColorAt(int externalRow, int column)` mirroring `getLinkIdAt`
    (line 664): null row → sentinel.
  - Resize/rebuild loop (~lines 440–480): thread `underlineColorAtCol` exactly like `linkIdAtCol`
    (read at line ~453, stamped into the `setChar` writes).
  - Column-shrink fill (line ~966): extend to the 6-arg form — `linkId` stays `0` as today;
    underline color is `keepVisualAttributes ? mScreen.getUnderlineColorAt(row, col) :
    TextStyle.COLOR_INDEX_FOREGROUND`.
  - Erase fill (line ~1749): no change (5-arg → sentinel).
- `TerminalEmulator`:
  - `reset()` (line ~3535): `mUnderlineColor = TextStyle.COLOR_INDEX_FOREGROUND;` next to
    `mForeColor`/`mBackColor` (fixes the uninitialized-field bug).
  - `SavedScreenState` gains `int mSavedUnderlineColor;`; `saveCursor()` (~2118) and
    `restoreCursor()` (~2132) save/restore it next to `mSavedUnderlineStyle`; `reset()`'s
    zeroing chain (~3547) also resets it to the sentinel (that chain sets ints to 0, so this needs
    a separate assignment — 0 is palette black, not the sentinel).
  - Main write (line 3469): `mScreen.setChar(column, mCursorRow, codePoint, getStyle(),
    mScreen.getCurrentLinkId(), mUnderlineColor);` (uncontended `synchronized` read per char —
    acceptable; do not add an unsynchronized accessor without a benchmark).
  - No change to SGR 58/59 parsing (already correct) or `getStyle()` (underline color is not in
    the style word).

**Test first** (`ControlSequenceIntroducerTest`, mirroring `testUnderlineVariants` /
`assertUnderlineStyleAtCell` style; read per-cell values via
`mTerminal.getScreen().getUnderlineColorAt(row, col)`; sequences written as
`"\\u001b[58;5;9mX"` etc.):
- `testUnderlineColorIndexedIsStoredPerCell` — `CSI 58;5;9 m` + `X` → cell (0,0) reads `9`; a
  cell written before the SGR reads the sentinel.
- `testUnderlineColorRgbIsStoredPerCell` — `CSI 58;2;10;20;30 m` + `X` → `0xff0a141e`.
- `testSgr59ResetsToDefaultForSubsequentCells` — set color, write `X`, `CSI 59 m`, write `Y` →
  `X` keeps its color, `Y` reads the sentinel.
- `testDefaultTerminalNeverAllocatesUnderlineColorArray` — write plain text; the screen row's
  `mUnderlineColors` stays null.
- `testResetRestoresDefaultUnderlineColor` — set `CSI 58;5;9 m`, then `CSI 0 m` (or RIS) → next
  cell sentinel and `mTerminal.mUnderlineColor` is the sentinel.
- `testDecscDecrcRestoresUnderlineColor` — `CSI 58;5;9 m` + DECSC (ESC 7), `CSI 58;5;3 m`, DECRC
  (ESC 8) → next written cell reads `9`.

**Commit:** `Added(terminal-emulator): Wire SGR 58 underline color into per-cell storage`

---

### Task 4 — Renderer: link underline + colored underline (`:terminal-view`)

**Files**
- `terminal-view/src/main/java/com/termux/view/TerminalRenderer.java`
- new `app/src/test/java/com/termux/app/ui/TerminalRendererUnderlineTest.java` (Robolectric; best
  effort — see fallback)

**Implementation**
- `render()` run loop:
  - Per column read `final int linkId = lineObject.getLinkId(column);` and
    `final int underlineColor = lineObject.getUnderlineColor(column);` (both O(1), no allocation).
  - New run state `int lastRunLinkId = 0; int lastRunUnderlineColor =
    TextStyle.COLOR_INDEX_FOREGROUND;` reset in the sixel-bitmap fast path (~line 146) next to
    `lastRunStyle = 0`, updated wherever the other `lastRun*` are (~line 164), and added to the
    run-break condition (line 155): `… || linkId != lastRunLinkId || underlineColor !=
    lastRunUnderlineColor`.
  - Pass `lastRunLinkId, lastRunUnderlineColor` to **both** `drawTextRun` call sites (in-loop and
    the end-of-row tail).
- `drawTextRun(...)` — new trailing params `int linkId, int underlineColor`:
  - `final boolean hasLink = linkId != 0;`
  - Underline decision: `underline` (effect bit, unchanged) OR `hasLink`. Underline *style*:
    from the effect bit when set (WS2 semantics, including the existing "underline bit without
    style" fallbacks), otherwise `UNDERLINE_STYLE_SINGLE` for link-only cells.
  - `paintUnderline` (single → `Paint.setUnderlineText`, drawn in text color) applies only when
    **no stored color** (`underlineColor == TextStyle.COLOR_INDEX_FOREGROUND`) and the style is
    one of today's paint-eligible ones. Link-only + no stored color therefore renders exactly as
    spec §5.1 row 1: plain single underline in the cell's foreground color, via the existing path.
  - `customUnderline` otherwise (decorated styles as today, **plus** any style when a stored color
    exists). Resolve the stroke color after the existing fore/back color resolution:
    ```java
    int underlineColorToUse = foreColor; // fallback = run foreground (current behavior)
    if (underlineColor != TextStyle.COLOR_INDEX_FOREGROUND) {
        int c = underlineColor;
        if ((c & 0xff000000) != 0xff000000) {           // palette index, same test fg/bg use
            if (bold && c >= 0 && c < 8) c += 8;         // bold → bright, like foreground
            c = palette[c];
        }
        underlineColorToUse = c;                         // 24-bit 0xffRRGGBB passes through
    }
    ```
    and pass `underlineColorToUse` (not `foreColor`) to `drawUnderline`.
  - `drawUnderline` gains `case TextStyle.UNDERLINE_STYLE_SINGLE: default:` → one
    `canvas.drawLine(left, underlineY, right, underlineY, mTextPaint)` at the existing
    `underlineY` (its javadoc should say it now also strokes single underlines that carry an
    explicit color; the `default` keeps any out-of-range style visible rather than dropped).
  - Known accepted edge: 24-bit white `0xffffffff` equals the `-1` sentinel and reads as "no
    stored color" → falls back to foreground; matches the fg/bg encoding collision that already
    exists, never crashes.

**Test** (Robolectric, `:app` — `:terminal-view` has no Robolectric dependency):
`TerminalRendererUnderlineTest` renders the same small `TerminalEmulator` twice into
`Bitmap`-backed `Canvas`es via `TerminalRenderer` and compares pixels:
- content with an OSC 8 link cell vs. identical content without the link → pixels below the link
  cell's text are drawn (bitmap differs) and the text row itself is otherwise identical;
- content with `CSI 4 m` + `CSI 58;5;9 m` vs. `CSI 4 m` alone → underline pixels differ (color
  changed). Use loose assertions (any non-background pixel in the underline band of the target
  column) — Robolectric font metrics differ from device metrics, so exact pixel geometry is not
  asserted. **Fallback:** if Robolectric's `Canvas`/`Paint` measurement makes the comparison
  meaningless in practice, drop this class and record in the ledger update that renderer coverage
  for WS5 is the spec §8 device-sanity check instead (the emulator-side storage is still fully
  unit-tested).

**Commit:** `Added(terminal-view): Render link underlines and SGR 58 underline colors`

---

### Task 5 — Tap hit-test + `onLinkClicked` (`:terminal-view`)

**Files**
- `terminal-view/src/main/java/com/termux/view/TerminalViewClient.java`
- `terminal-view/src/main/java/com/termux/view/TerminalView.java`
- new `terminal-view/src/test/java/com/termux/view/TerminalViewLinkHitTest.java`

**Implementation**
- `TerminalViewClient` — new default method with javadoc:
  ```java
  /** Called when the user taps an OSC 8 hyperlink whose scheme is in the allowed set. */
  default void onLinkClicked(String uri) {
  }
  ```
  Source- and binary-compatible for every existing implementor (Java 8 default method).
- `TerminalView` — package-private static resolver (pure, unit-testable without a `MotionEvent`,
  same rationale the spec gives for `LinkUris`):
  ```java
  /** @return the sanitized link URI at the given screen cell, or {@code null} when there is no
      dispatchable link there (no link, out of bounds, or disallowed scheme). */
  static String getLinkUriAt(TerminalEmulator emulator, int row, int column) {
      if (row < 0 || row >= emulator.mRows || column < 0 || column >= emulator.mColumns) return null;
      int linkId = emulator.getScreen().getLinkIdAt(row, column);
      if (linkId == 0) return null;
      String uri = emulator.getScreen().getLink(linkId);
      String sanitized = LinkUris.sanitize(uri);
      if (sanitized == null) {
          Log.w(LOG_TAG, "Dropped OSC 8 hyperlink with disallowed scheme: " + uri);
          return null;
      }
      return sanitized;
  }
  ```
  (`android.util.Log` — `:terminal-view` has no `Logger`; add `private static final String LOG_TAG
  = "TerminalView";` if not already present.)
- `onSingleTapUp` (lines 175–184) — insert per spec §4.1, after `requestFocus()`, before the
  client forward; a link hit consumes the gesture:
  ```java
  requestFocus();
  int[] columnAndRow = getColumnAndRow(event, true);   // scroll-aware: includes mTopRow
  String linkUri = getLinkUriAt(mEmulator, columnAndRow[1], columnAndRow[0]);
  if (linkUri != null) {
      mClient.onLinkClicked(linkUri);
      return true;
  }
  mClient.onSingleTapUp(event);
  return true;
  ```
  A dropped (disallowed-scheme) link falls through to the normal `onSingleTapUp` forward — it
  behaves as if there were no link. Selection early-return and mouse-tracking `onUp` consumption
  are untouched and still win.

**Test** (`TerminalViewLinkHitTest extends TestCase`, plain JVM): build a minimal stub
`TerminalOutput` (copy the shape of `TerminalTestCase.MockTerminalOutput` — `write`,
`titleChanged`, `onCopyTextToClipboard`, `onPasteTextFromClipboard`, `onBell`, `onColorsChanged`),
construct `new TerminalEmulator(stub, 10, 5, 10, 20, 10, null)`, and feed sequences through
`processInputBytes(...getBytes(StandardCharsets.UTF_8))` (this is what
`TerminalTestCase.enterString` does). Cases:
- `testLinkCellResolvesToSanitizedUri` — OSC 8 `OSC 8 ; ; http://example.com BEL` + `X` →
  `getLinkUriAt(…, 0, 0)` is `"http://example.com"`.
- `testNonLinkCellIsNull` — same content, a column past the link → null.
- `testOutOfBoundsIsNull` — row `-1`, row `mRows`, column `-1`, column `mColumns` → null (never
  throws — `getLinkIdAt` would index out of bounds without the guard).
- `testDisallowedSchemeIsNull` — `OSC 8 ; ; file:///etc/passwd BEL` + `X` → null (and the tap
  falls through to the normal client path in production).
- `testLinkEndsAtOsc8Reset` — OSC 8 link + `X`, then `OSC 8 ; ; BEL` + `Y` → link at column of
  `X`, null at column of `Y`.

**Commit:** `Added(terminal-view): Add hyperlink tap hit-test and onLinkClicked callback`

---

### Task 6 — App-side `ACTION_VIEW` dispatch (`:app`) *(spec deviation 4)*

**File:** `app/src/main/java/com/termux/app/terminal/TermuxTerminalViewClient.java`

Override next to the class's existing `onSingleTapUp` (~line 192), using the `TermuxActivity`
reference the client already holds:
```java
@Override
public void onLinkClicked(String uri) {
    try {
        mTermuxActivity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)));
    } catch (ActivityNotFoundException e) {
        Logger.logError(LOG_TAG, "No activity found to open link: " + uri);
    }
}
```
(Adjust the activity field/`LOG_TAG`/`Logger` names to the class's actual members; inputs are
already sanitized by Task 5, so no re-validation here.)

**Commit:** `Added(app): Open OSC 8 hyperlinks with ACTION_VIEW`

---

### Task 7 — Gate + docs

1. `./gradlew test` — full gate green (`:terminal-emulator`, `:terminal-view`, `:app` Robolectric
   suites; no regressions elsewhere). Run with the workspace's `JAVA_HOME` (Java 21, per repo
   convention; Gradle daemon already configured).
2. Spec amendments in `docs/specs/2026-10-17-osc8-hyperlinks-sgr58-underline-design.md`:
   - §4.2: replace the coordinate formula with `getColumnAndRow(event, true)` and the rationale
     (scroll + descent offset; mirrors selection/mouse math).
   - §6.4: add the `reset()` initialization and DECSC/DECRC save/restore bullets.
   - Header `Status:` → `Approved — implemented`; §8: note whether the Robolectric renderer test
     landed or the fallback (device sanity) applies.
3. Ledger `docs/ideas/2026-10-16-outstanding-work.md` §9: WS5 → done, with a one-line note of the
   four deviations and any leftover (e.g. the white-ARGB sentinel edge).
4. Update `.planning/` progress notes.

**Commit:** `Changed(docs): Record WS5 completion and correct spec details`

## Verification (definition of done)

- `./gradlew test` green — including the new `LinkUrisTest`, `TerminalRowTest` additions,
  `ControlSequenceIntroducerTest` SGR-58 additions, `TerminalViewLinkHitTest`, and the Robolectric
  renderer test (or its recorded fallback).
- Manual device sanity (spec §8): `printf '\e]8;;https://example.com\e\\link\e]8;;\e\\'` in a
  shell → tap opens the browser; `file:` link tap does nothing (log only); a colored-underline TUI
  (`CSI 58;2;r;g;b m`) shows the underline in the set color; tap while scrolled back hits the
  right row.
- Docs truthful: spec amendments + ledger updated; nothing in `:termux-shared` touched; mouse
  reporting, selection, and all deferred conformance items unchanged.
