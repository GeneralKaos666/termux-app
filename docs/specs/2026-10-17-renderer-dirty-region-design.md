# Terminal engine upgrade — workstream 2: renderer dirty-region drawing (design spec)

**Date:** 2026-10-17
**Status:** implemented (`e8e165a8`..`67ec4de3`); see
[`../plans/2026-10-17-renderer-dirty-region.md`](../plans/2026-10-17-renderer-dirty-region.md). One
limitation found during review is recorded in the outstanding-work ledger §7 (palette changes with no
other output).
**Origin:** user request — "*lest start ws2*" — continuing
[`../ideas/2026-10-14-terminal-engine-upgrade-idea.md`](../ideas/2026-10-14-terminal-engine-upgrade-idea.md)
(§4's workstream 2: "Rendering: frame snapshot + dirty-region + image layer"), after workstream 1 shipped.
**Scope:** **workstream 2 only — the renderer.** `:terminal-view` only; `:terminal-emulator` gets **zero diff**
(WS1's dirty/underline APIs are consumed, not changed) and `:termux-shared` gets **zero diff**. The "image layer"
half of the idea's title is **excluded** (user decision, see §1) — the existing sixel/iTerm bitmap drawing is
untouched and kitty placements arrive in workstream 4.

**Decisions taken by the user (2026-10-17 brainstorm):**
- **Success criterion = architectural headroom only.** No observed jank symptom exists; WS2 must establish the
  frame/dirty-region structure WS3/WS4 build on and make per-frame draw work proportional to dirty rows. Evidence
  is structural (tests on the decision logic), not a user-visible speed fix.
- **Renderer only** — no image-layer seam in this spec.
- **Underline styles render distinctly in WS2** (`double`/`curly`/`dotted`/`dashed`), so WS1's stored style stops
  degrading to plain underlines. Link ids remain **unrendered** (WS5).
- **Approach A — targeted invalidation + row-range rendering** over an offscreen frame bitmap (B) or retained
  per-row hardware layers (C). No new per-frame memory; the seam can absorb B later if scattered-dirty workloads
  ever justify it.

---

## 1. Background (grounded in code)

**Today's redraw path.** All terminal output funnels through `TermuxTerminalSessionActivityClient` →
`TerminalView.onScreenUpdated()` (`TerminalView.java:459`), which ends in a bare `invalidate()` (`:498`). That
schedules `onDraw` (`:1022`), which calls `TerminalRenderer.render(...)` (`:1032`,
`TerminalRenderer.java:61`) — a loop over **every visible row**, each row parsed into style runs and drawn with
`drawTextRun`. The view is hardware-accelerated by default (no `LAYER_TYPE_*`/`hardwareAccelerated` overrides
exist anywhere in `:app` or `:terminal-view`), so Android recomposes only the invalidated rect and preserves the
compositor's pixels outside it. Ten other `invalidate()` call sites in `TerminalView` exist for view-overlay
concerns (selection, cursor blink, text size, IME) that the buffer knows nothing about.

**What WS1 shipped (the substrate consumed here).** On `TerminalBuffer`: `isRowDirty(externalRow)` (false for a
null row), `getChangeCounter()` (starts 0, monotonic, never reset; ticks on scroll, resize, transcript clear),
`clearDirtyState()` (clears every non-null row's flag; does **not** touch the counter), `markAllDirty()`.
On `TerminalRow`: `isDirty()`/`markDirty()`/`clearDirty`. Marks are set where content is *written*. On
`TextStyle`: `decodeUnderlineStyle(long)` reading bits 11–13 (`NONE=0, SINGLE=1, DOUBLE=2, CURLY=3, DOTTED=4,
DASHED=5`); out-of-range values were already clamped to SINGLE at SGR parse time, so consumers see only 0–5.

**What is missing.** The renderer ignores all of it: every frame redraws every row, and underline styles all
paint as plain underlines (`Paint.setUnderlineText`), because `drawTextRun` only reads the boolean
`CHARACTER_ATTRIBUTE_UNDERLINE` effect bit.

**Compatibility surface.** `:termux-shared` (published) does not import `TerminalRenderer`; the only external
reads of renderer-adjacent state are `TerminalView`'s existing public methods (`getText`, `getSelectedText`).
`TerminalRenderer.render(...)` is `public final` — its signature change must be **additive** (see §3).

---

## 2. Architecture — the dirty-state protocol

The buffer owns truth; the view is the single consumer. Three moving parts:

1. **Reader state in `TerminalView`:** `mLastSeenChangeCounter` (a `long`, reset to `-1` whenever the session or
   emulator instance changes — e.g. `setTerminalSession`, `updateSize` — so the first draw after any rebind is a
   full repaint). No per-row bookkeeping in the view: the buffer's `isRowDirty(row)` is queried live.
2. **Invalidate decision in `onScreenUpdated()`** (replaces the bare `invalidate()` at `:498`), evaluated in
   this order:
   - `screen.getChangeCounter() != mLastSeenChangeCounter` → **full** `invalidate()`. Covers scroll, resize and
     transcript clear — everything whose effect on screen the row flags alone cannot describe.
   - `mTopRow` changed anywhere earlier in the same `onScreenUpdated()` call → **full** `invalidate()` (the
     viewport moved; row flags describe buffer rows, not view bands).
   - otherwise, any visible screen row `0..mRows-1` with `isRowDirty(row) == true` → **targeted**
     `invalidate(0, top, getWidth(), bottom)` where the Y band is the dirty span mapped through the viewport
     (§4) and clamped to the view.
   - the cursor cell (`getCursorRow()`/`getCursorCol()`), cursor visibility (`shouldCursorBeVisible()`), cursor
     shape (`getCursorStyle()`), or the reverse-video flag differs from what the last
     draw painted → **full** `invalidate()`. **Added 2026-10-17 during execution** (the original text said a lone
     cursor move was a no-repaint case — that was wrong): the renderer draws the cursor *into* the row band, so a
     cursor move that dirties no row would leave the old block cursor painted; and `CSI ? 5 h/l` (DECSCNM),
     `CSI ? 25 h/l` (DECTCEM) and `DECSCUSR` flip state without marking rows dirty or ticking the counter. The view
     tracks `mLastCursorRow`/`mLastCursorCol`/`mLastCursorVisible`/`mLastCursorStyle`/`mLastReverseVideo`,
     captured in `onDraw()` next to the counter capture. **Known limitation:** an OSC 4/10/11/12 palette change
     with no other output is *not* detected here. The app's `onColorsChanged` normally repaints the view in full
     (it re-sets the decor background), but `updateBackgroundColor()` early-returns in wallpaper/custom-image
     mode, so a pure palette OSC can stay stale until the next dirty repaint. Not fixed in WS2 because a robust
     signal (a palette version) would either add a per-update palette comparison or touch `:app`; recorded in
     `docs/ideas/2026-10-16-outstanding-work.md`.
   - no dirty visible row and no cursor/reverse-video change → **no invalidation** (output that changed nothing
     drawn, e.g. an OSC 8 link registration or a mode bit no one renders).
3. **Post-draw consumption in `onDraw()`:** after `render(...)` returns:
   `mLastSeenChangeCounter = screen.getChangeCounter(); screen.clearDirtyState();`

**Why clearing happens at draw time, not update time.** `invalidate()` only *schedules* a draw; Android coalesces
N updates into one draw. Clearing at update time would drop the flags of updates whose draw hasn't run yet. At
draw time the canvas is already clipped to the union of all pending invalidate rects, so **what is rendered is
exactly what is cleared** — mutations and draws serialize on the main thread, so no update can slip between the
render and the clear.

**Why the counter is compared, not trusted alongside flags.** Row flags say *which* rows changed; the counter
says *whether content moved*. A scroll rotates rows (flags follow their rows), but the view band they land in is
entirely different — only a full repaint is correct. The counter jump is that signal, and it doubles as the
safety net: any future write path that forgets a row mark still repaints correctly as soon as anything scrolls;
between scrolls, forgetting a mark can under-repaint, which is exactly what the tests in §8 pin down.

---

## 3. Components

| Component | Responsibility | Files |
|---|---|---|
| `RowInvalidation` (new, package-private, pure Java) | The decision function of §2 as testable logic: inputs = last/current counter, the visible rows' dirty flags, `mTopRow`, font line spacing, view height; output = `FULL` / `RANGE(top, bottom)` / `NONE`. All int/long/boolean math — no Android types — so it runs under plain JUnit. | `terminal-view/src/main/java/com/termux/view/RowInvalidation.java` |
| `TerminalView` | Owns `mLastSeenChangeCounter`; calls `RowInvalidation.decide(...)` from `onScreenUpdated()`; converts `RANGE` to `invalidate(...)`; passes clip-derived row bounds + resets counter + clears flags in `onDraw()`. | `TerminalView.java` |
| `TerminalRenderer` | `render(...)` gains `firstScreenRow`/`lastScreenRowExclusive`: the existing row loop walks only that band. **Trap:** `heightOffset` is a running accumulator seeded *before* the loop, so the overload must seed it with `mFontLineSpacingAndAscent + (firstScreenRow - topRow) * mFontLineSpacing` — otherwise a band that starts below the top draws its glyphs in the wrong pixel rows. **Public API stays:** the current `render(...)` signature is kept as an overload delegating with the full range. Draws distinct underline styles (§6). | `TerminalRenderer.java` |
| Test source set (new for this module) | `junit` **test-scope** dependency added to `terminal-view/build.gradle` — the module has no test sources today. No main-source dependency changes in any module. | `terminal-view/build.gradle`, `terminal-view/src/test/...` |

No new public API. No change to `:terminal-emulator`, `:termux-shared`, the client interfaces, or any XML.

---

## 4. Data flow (one frame)

1. Output → emulator mutates `TerminalBuffer` → rows marked dirty, counter possibly ticked (WS1).
2. Main-thread handler → `TerminalView.onScreenUpdated()` → scroll/autoscroll bookkeeping exactly as today →
   `RowInvalidation.decide(...)` → full / targeted / no `invalidate()`. Accessibility
   `setContentDescription(getText())` still runs unconditionally (it is a read, not a draw concern).
3. Draw (possibly coalesced across several updates): `onDraw` reads `canvas.getClipBounds(...)` → derives
   `firstRow..lastRow` intersected with the visible band (clip is the union of pending invalidates, so every
   pending dirty row is inside it; unbounded/empty clip falls back to the full range) → `render(..., firstRow,
   lastRow)` draws that band → counter captured → `clearDirtyState()`.
4. Overlays (selection, cursor blink, text size, IME) keep their existing `invalidate()` sites unchanged; they
   repaint their full or self-computed rects. Correct by oversimplification — tightening them is out of scope.

**Viewport math (named risk, explicit):** screen row → view Y is `(row - mTopRow) * mFontLineSpacing`,
clamped to `[0, height]`. Wrong sign on `mTopRow` repaints the wrong band — hence `RowInvalidation` owns this
arithmetic and its unit tests pin it (including `mTopRow < 0`, history scrolled up).

---

## 5. Edge cases and failure handling

- **Viewport changed inside `onScreenUpdated`** (autoscroll aborted, history clamp, selection stop) → full
  `invalidate()`; the targeted path never runs after a viewport move.
- **Transcript rows outside the visible band** → ignored by the targeted path. Any real buffer scroll ticks the
  counter first, which routes to full repaint anyway.
- **Counter decreases** (impossible per WS1's monotonicity promise) → treated as a jump: full repaint. One
  defensive comparison.
- **Session/emulator rebind or resize** → `mLastSeenChangeCounter = -1` → next decision is always full.
- **Dirty flags set while a draw is pending** → coalesced into the same clip union, rendered and cleared
  together (§2's draw-time clearing argument).
- **Empty clip** → Android doesn't invoke `onDraw`; unbounded clip → full row range.
- **Null emulator/renderer** → existing early-return guards untouched.
- **`isOpaque()` stays `true`**; the renderer never draws outside the clip — partial invalidation relies on the
  compositor keeping pixels outside the recomposed band.

---

## 6. Underline-style rendering

In `drawTextRun`, after the fore color is fully resolved (bold/bright, reverse, dim), driven by
`TextStyle.decodeUnderlineStyle(textStyle)`:

- **`SINGLE` (1) and `NONE` + underline bit (0)** → today's `mTextPaint.setUnderlineText(true)` — the common case
  stays on Android's fast path, and a plain underline from DECCARA renders exactly as before.
- **`DOUBLE` (2)** → two solid strokes below the baseline (offsets derived from `mFontLineSpacing`).
- **`CURLY` (3)** → zigzag `Path` across the run width, fixed amplitude/wavelength (~3 px/~6 px).
- **`DOTTED` (4)** → same path with a `DashPathEffect` (~1 px dots).
- **`DASHED` (5)** → `DashPathEffect` (~4 px dashes / 4 px gaps).
- Any value outside 0–5 (defensive; parse-time clamping means it shouldn't occur) → treat as `SINGLE`.

Custom-style paths draw **after** the text, with a stroke `Paint` in the run's foreground color, inside the
existing `canvas.save()/scale()` block so width-mismatched fonts scale the shape with the run;
`setUnderlineText(false)` is set for custom styles so Paint's underline doesn't double up. `PathEffect`,
`DashPathEffect` are API 1 — fine for minSdk 21. **Hyperlink ids stay unrendered** (no link underline/coloring —
that pairs with click handling in WS5).

---

## 7. Compatibility

- `:termux-shared` and the plugin apps: zero visible change. `TerminalRenderer.render(...)`'s current signature
  survives as a delegating overload; `TerminalView`'s public surface is untouched.
- Java 8 source level in `:terminal-view` (no `var`, etc.); 4-space indent, LF, final newline per
  `.editorconfig`.
- Companion-app behavior (Termux:X11/Widget/Float hosts) is unaffected: they embed the same `TerminalView`.

---

## 8. Testing & acceptance

- **`:terminal-emulator`: zero diff** — its 208 tests are the regression net for the flag/counter semantics this
  spec consumes.
- **`RowInvalidation` unit tests (new `:terminal-view` test source, plain JUnit):**
  counter-equal + no dirty → `NONE`; counter-jump → `FULL` regardless of flags; contiguous dirty span →
  `RANGE` with correct top/bottom; scattered dirty rows → span (first..last); the viewport-relative index → pixel
  band mapping (index 2, spacing 10 → 20..30; index 0 → 0..10) pinned by a test that the band cannot shift with
  the scroll offset; `mTopRow` changed → `FULL`; counter decrease → `FULL`; dirty span clamped to view bounds;
  non-positive line spacing or view height → `FULL`/unbounded as specified; `mLastSeenChangeCounter` reset
  semantics make a rebind yield `FULL`.
- **Buffer-backed mapping test (added 2026-10-17):** `RowInvalidation.dirtyByIndex(TerminalBuffer, topRow, rows)`
  is tested against a real `TerminalBuffer` (needs `testOptions { unitTests.returnDefaultValues = true }` in
  `terminal-view/build.gradle`, test-scope only) — a fresh buffer reads all-clean; dirtying rows 1 and 2 makes
  indexes 1 and 2 true; with two transcript rows present, `topRow = -2` reads the transcript band and not the
  screen band. This is the test that pins spec §10's sign-error risk at its new home (the caller's mapping).
- **Cursor / reverse-video repaint (added 2026-10-17):** no unit test is possible (`TerminalView` is an Android
  `View` with no Robolectric harness); it is exercised by device checklist item 7.
- **Protocol integration reasoning** (documented in the plan, not Robolectric-pixel-tested): draw-time clearing
  means a coalesced update pair renders once with the union clip; Robolectric does not rasterize and the repo has
  no instrumented-test infrastructure, so pixel assertions are deliberately not attempted.
- **Full gate:** `./gradlew test` green (`:terminal-emulator` + `:app` + the new `:terminal-view` tests).
- **Device visual checklist (acceptance — recorded in the plan's branch gate):**
  1. `yes | head -100000` — no stale/ghost pixels, no flicker bands.
  2. Full-screen redraw app (`htop`-style) — rows repaint completely.
  3. Idle scrollback drag — content shifts cleanly (viewport path), no smearing.
  4. Text selection start/extend **while** output runs — highlight and output both correct.
  5. One line each of `4:1`…`4:5` underlines — visibly distinct: solid, double, wave, dots, dashes.
  6. Font-size change and rotation/resize — full repaint, nothing stale.
  7. Cursor state with no output: move (`\033[10;20H` in `bash`), reverse video (`printf '\033[?5h'` /
     `'\033[?5l'`), hide/show (`printf '\033[?25l'` / `'\033[?25h'`), and a shape change (DECSCUSR, e.g.
     `printf '\033[5 q'`) — no ghost cursor, no stale cursor shape or stale video state.

---

## 9. Out of scope (do not start here)

- **Image layer / kitty graphics** — workstream 4 (existing sixel/iTerm bitmap drawing untouched).
- **Shaping / ligatures** — workstream 3; it will consume the row-range `render` seam.
- **Link click handling, hyperlink rendering, remaining conformance** — workstream 5.
- **`:termux-shared` shims** — workstream 6.
- **Overlay invalidation tightening** (cursor blink/selection rects) — the current full invalidates for overlays
  are accepted as-is.
- **Offscreen frame bitmap / dirty-row blit cache** — approach B, deliberately not taken; may be added later
  *behind* this spec's row-range seam without API change.
- **Scroll-during-output frame scheduling** (Choreographer-paced output flush) — no symptom; YAGNI.

---

## 10. Risks

- **`mTopRow` sign/offset error** → repaints the wrong band. Mitigation: the *pixel* arithmetic lives in the
  tested helper; the `index → external row = topRow + index` mapping lives in `RowInvalidation.dirtyByIndex`,
  which has its own buffer-backed test (spec §8).
- **Cursor ghost / reverse-video not repainting** (found during execution) → mitigated by the cursor-cell and
  reverse-video comparison added to the protocol (spec §2); these are viewed as device-checklist item 7 because
  `TerminalView` is not unit-testable in this repo.
- **A future write path that forgets to mark a row** → under-repaint between scrolls. Mitigation: WS1 already
  marks every write site (its task 1 test net); the counter provides eventual full-repaint recovery on any
  scroll/resize.
- **Coalesced-invalidate assumption** — the design relies on `onDraw` seeing the union clip of all pending
  invalidates. This is Android's documented behavior; the fallback (full range on unbounded clip) degrades to
  today's behavior, never to a missed repaint.
- **New test dependency in `:terminal-view`** — test-scope `junit` only; if the module's build ever publishes
  test artifacts this needs revisiting (it doesn't today).
