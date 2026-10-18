# Design: WS5 — OSC 8 Hyperlinks End-to-End + SGR 58 Underline Color

**Date:** 2026-10-17
**Status:** Approved — implemented (2026-10-17; spec corrected below where execution diverged)
**Program:** `docs/ideas/2026-10-14-terminal-engine-upgrade-idea.md` (workstream 5)
**Depends on:** WS1 (cell model: `docs/specs/2026-10-17-terminal-engine-cell-model-design.md`),
WS2 (renderer dirty-region: `docs/specs/2026-10-17-renderer-dirty-region-design.md`)

---

## 1. Goal

Deliver hyperlinks end-to-end: WS1 already parses `OSC 8` and stamps per-cell link ids, and WS2
already renders underline styles — but link ids are still invisible (no underline shown for link
cells) and unreachable by the user (no tap hit-test, no client callback). This workstream closes
that loop and, as a bundled small item, makes `SGR 58` (underline color) actually affect rendering.

## 2. Scope

**In scope:**
1. Hyperlink hit-test on tap in `TerminalView`.
2. A new source-compatible `onLinkClicked(String uri)` default method on `TerminalViewClient`.
3. Scheme sanitization before dispatch (allow-list only).
4. Underline rendering for link cells (underline-only; no color change; no double-draw when SGR
   already set an underline style).
5. `SGR 58` underline color: per-cell storage (parallel side array) + renderer uses it.

**Out of scope (deliberately deferred to a future conformance workstream):**
- Focus events (DECSET 1004), ANSI DECRQM, colon-form `38:2:r:g:b`, OSC 7, XTVERSION, DECID,
  mouse-mode gaps (1003/1005/1015), SGR 51/52/53/55, OSC 4/52 query forms, blink rendering.
- Link hit-testing on non-touch input paths (mouse-tracking mode keeps its existing behavior).
- Any `:termux-shared` public API change; any Java/language level change (stays Java 8 source).

## 3. Constraints (from the program)

- Java 1.8 source/target, `minSdk 21`, core library desugaring.
- `TerminalViewClient` / `TerminalSessionClient` extension points must stay **source-compatible**
  for plugin apps → new callback must be a `default` method with a no-op body.
- No `:termux-shared` API break; `commons-io` stays `2.5`.
- Follows WS1/WS2 test conventions (`TerminalTestCase`, Robolectric where UI logic needs it).

## 4. Hyperlink click flow

### 4.1 Where it hooks

`TerminalView`'s `GestureAndScaleRecognizer.Listener.onSingleTapUp(MotionEvent)` already:
- returns early if selecting text (stops selection mode instead),
- requests focus, then forwards to `mClient.onSingleTapUp(event)`.

A link hit-test is inserted **after** the selection check and focus request, **before** the
`mClient.onSingleTapUp` forward. If the tap lands on a link, the tap is consumed (the
`onSingleTapUp` client forward does not fire for that gesture); if the resolver returns `null`
(no link, or a link whose scheme was dropped) the tap falls through to `mClient.onSingleTapUp` as
before. Selection and mouse-tracking behavior are unchanged: if `isSelectingText()` is true the
existing early-return runs first; if mouse tracking is active, `onUp` already consumed the gesture
for mouse events before `onSingleTapUp` runs, so links never hijack drag/selection.

### 4.2 Coordinate math

```
int[] cell = getColumnAndRow(event, true);   // { column, row }
```

`getColumnAndRow(event, true)` is the scroll-aware conversion `TerminalView` already uses for
selection and mouse reporting: it subtracts the descent offset (`mFontLineSpacingAndAscent`) from
`getY()`, divides by the font line spacing, and adds `mTopRow`, so the resolved row is the screen
row under the finger even when the view is scrolled. (The first draft divided `getY()` directly,
which ignored both the descent offset and the scroll offset.) `column` is still
`(int)(getX() / mFontWidth)`. Bounds are checked against `mEmulator.mColumns` / `mEmulator.mRows`;
taps outside the grid are treated as non-links.

### 4.3 Resolution

The hit-test is a package-private static resolver, pure and unit-testable without a `MotionEvent`:

```java
static String getLinkUriAt(TerminalEmulator emulator, int row, int column) {
    // bounds → null; linkId 0 → null; registry lookup → sanitize → uri or null
}
```

`getLinkIdAt` never allocates (returns 0 for null rows), so a tap on scrollback/blank area costs
nothing. The emulator is never null at the call site (`onSingleTapUp` returns early on a null
emulator), so the resolver does not re-check it.

### 4.4 Sanitization (security boundary)

The URI arrives from untrusted terminal output (a remote shell, a `curl | …` script, tmux
passthrough). It is treated as untrusted input at this boundary.

- **Allow-list:** `http://`, `https://`, `ftp://`, `mailto:` — case-insensitive prefix match on
  the trimmed URI.
- **Everything else** (`file:`, `intent:`, `javascript:`, `data:`, no-scheme relative strings,
  anything with embedded control characters): dropped, with a warning logged by `TerminalView`
  (`Log.w(LOG_TAG, "Dropped OSC 8 hyperlink with disallowed scheme: …")`). Never dispatched to
  `onLinkClicked`. (The draft said "logWarn on the client"; the drop happens inside `TerminalView`,
  before the client is involved, so it logs there directly.)
- Rationale: the callback's contract is "a URI this app may hand to an `ACTION_VIEW` intent".
  Keeping the allow-list *before* the callback (not in each consumer) means every implementor —
  app, plugins — gets the safe set by construction.

Sanitization is factored into a small, pure, static helper — a new `LinkUris` utility class in
`com.termux.view` (`:terminal-view`) — so it is unit-testable without a `MotionEvent`.

### 4.5 Client callback

```java
// TerminalViewClient.java
/** Called when the user taps an OSC 8 hyperlink whose scheme is in the allowed set. */
default void onLinkClicked(String uri) {
}
```

**App-side dispatch (deviation 4, added during execution).** The app must actually open the link,
so `TermuxTerminalViewClient` overrides the callback:

```java
@Override
public void onLinkClicked(String uri) {
    ShareUtils.openUrl(mActivity, uri);   // ACTION_VIEW; system chooser when nothing handles it
}
```

`ShareUtils.openUrl` is the app's existing URL-opening path (already used by the word-URL tap
feature): it builds `Intent(ACTION_VIEW, Uri.parse(url))`, falls back to the system chooser on
`ActivityNotFoundException`, and logs other failures. Delegating to it keeps the override to a
one-liner instead of duplicating error handling. **Open question (unresolved):** the existing
word-URL tap is gated by the `open terminal transcript URL on click` property; OSC 8 links are
dispatched unconditionally. The approved plan specified no gating, so that is what shipped.

- `default` → every existing implementor (`TermuxTerminalViewClientBase` and subclasses, plugin
  apps) compiles unchanged (Java 8 default methods, already the project's source level).
- Deliberately placed on `TerminalViewClient` only, not `TerminalSessionClient`: links are a
  view/input concern, the session has no gesture context. One new method, one place.

## 5. Link rendering

### 5.1 Behavior

For a drawn run, a cell with a non-zero link id is rendered with an underline — the standard
"this is clickable" affordance — **unless** SGR already gave that cell an underline:

| Cell state | Rendering |
|---|---|
| link id set, no SGR underline | plain single underline, cell's foreground color |
| link id set, SGR underline (any style) | SGR underline as-is (WS2 already draws it) — no second line |
| no link id | unchanged from WS2 |

Text color, background, and style are never altered for links. No accent color, no tooltip, no
long-press preview (user decision: underline + tap-to-open only).

### 5.2 Data path

The renderer already iterates per-run with the cell's `TextStyle` word. Link id is *not* in that
word (it lives in `TerminalRow`'s side array, WS1). The renderer therefore needs the row handle
available while building runs — it already has the line being drawn (WS2's row-range render walks
`TerminalRow`s directly), so link lookup is `row.getLinkId(column)` alongside
`row.getUnderlineStyle(column)`. No new storage; read-only.

Per-cell granularity: underline drawing for links follows the same path WS2 established for
decorated underlines (`drawUnderline`), so per-cell variation within a run works the same way it
already does for underline styles.

### 5.3 Dirty-state interaction

WS1's `setLinkAt` already marks the row dirty when a link id is written, and `OSC 8` stamping
goes through `setChar` (content mutation → dirty). No additional invalidation work is needed:
link appearance changes ride the existing dirty-row protocol.

## 6. SGR 58 underline color

### 6.1 The storage problem

`TextStyle` packs effects, fg, bg, and underline style into one 64-bit word; the audit confirmed
**no contiguous free bits remain** for an underline color (11 effect bits 0–10, underline-style
bits 11–13, bitmap bit 15, bg bits 16–39, fg bits 40–63 — only bit 14 free). Options considered:

- **A. Parallel side array (chosen):** `TerminalRow` gains a lazily-allocated
  `int[] mUnderlineColors`, mirroring `mLinkIds` exactly — `null` until the first non-default
  SGR-58 color is written, allocated on first write, carried through `copyInterval`, `clear`,
  `resize` copies, and erased with the row. Scales per-row (most rows never pay for it), reuses a
  pattern this codebase already reviews and tests.
- **B. Render-time global:** use `mEmulator.mUnderlineColor` when drawing any underline — wrong
  for scrollback/history (every cell shows the *last* SGR 58, not the one it was written with).
- **C. Widen the style word to 128 bits:** doubles per-cell memory and touches every consumer of
  `TerminalRow.mStyle`; unjustified for a rarely-used attribute.

### 6.2 Encoding

Stored value follows the same convention as fg/bg: a color index `[0, 258]` (9 bits of meaning)
or a 24-bit `0xff000000 | rgb` value. `TextStyle.COLOR_INDEX_FOREGROUND` is the "default /
inherit foreground" sentinel (matches `SGR 59`, which already resets `mUnderlineColor` to that
index).

Emulator side: `getStyle()` (which builds the word written by `setChar`) must also write the
current `mUnderlineColor` into the row's new array — this means the cell-write path
(`TerminalEmulator.getStyle()` + `TerminalBuffer.setChar`) grows an underline-color parameter or
the buffer reads it from emulator state. **Chosen:** pass it explicitly alongside the existing
style word at the `setChar` call sites (mirrors how link id is already passed), keeping
`TerminalBuffer` free of emulator-state coupling.

### 6.3 Rendering

In `TerminalRenderer`'s underline drawing (both the Paint-based single underline and the
`drawUnderline` path for decorated styles): resolve the cell's underline color —
`index → palette[index]` (with the same bold→bright adjustment foreground uses for indices 0–7)
or the 24-bit value directly. Fall back to the run's foreground color when the cell has no
stored underline color (the current behavior). Underline *style* selection is unchanged (WS2).

**As implemented (execution detail):**

- A link cell with no SGR underline of its own is drawn through the paint's built-in single
  underline (`Paint.setUnderlineText(true)`), i.e. the same mechanism and position as an
  `SGR 4` single underline — a link never routes through `drawUnderline`.
- An underline whose color is set (`SGR 58` in effect) is always stroked manually by
  `drawUnderline`, which gained an explicit `UNDERLINE_STYLE_SINGLE` case (a single
  `canvas.drawLine`). This is what lets the colored line use its own `Paint` color.
- `SGR 58` without an underline attribute paints nothing (the stored color only shows on an
  actual underline) — matches xterm.
- Run breaking in the renderer treats a link-id or underline-color change as a run boundary
  (in addition to style/cursor/selection/font-width), so adjacent cells with different link ids
  or underline colors are drawn as separate runs.

### 6.4 Reset semantics

- `SGR 59` → stored value back to `COLOR_INDEX_FOREGROUND` (default → renderer falls back to
  foreground color).
- **Full `SGR 0` reset also restores the underline color to the default.** The pre-existing
  `SGR 58` parse was never cleared by a full reset (xterm resets the underline color; the reset
  block already restored fg, bg and underline style).
- **`reset()`** (emulator reset) initializes `mUnderlineColor` to `COLOR_INDEX_FOREGROUND`
  alongside the other style state.
- **DECSC/DECRC** save and restore it: `SavedScreenState` gained `mSavedUnderlineColor`, so
  `CSI s` / `CSI u` round-trip it like the rest of the cell style.
- Erase/scroll/copy operations treat "default" like any other value: the array slot is written
  through, no special-casing needed beyond the lazy-allocation rule (an all-default row keeps
  `null`).

## 7. Error handling & edge cases

- **Registry cap already handled (WS1):** `registerLink` GCs, then refuses → id 0 → cell
  carries no link → hit-test finds nothing. No new work.
- **Taps on scrollback rows:** `getLinkIdAt` returns 0 for allocated-but-linkless and for null
  rows alike — no allocation, no crash.
- **Malformed/oversized OSC 8:** already bounded by WS1's 4096-char payload cap; overlong
  sequences are dropped at parse time.
- **Selection vs. link tap:** selection wins (checked first, unchanged).
- **Mouse-tracking mode:** `onUp` consumes the gesture first (existing code) → links untouched.
- **Wide characters:** rendering is unaffected — the underline spans the run's full column width,
  including the continuation column. The hit-test, however, reads the cell directly under the
  finger, and WS1 stamps the link id on a wide char's **base column only** (the continuation column
  keeps id 0 by design, so a later overwrite/combine cannot inherit a stale link). Tapping the
  second half of a double-width linked glyph therefore resolves `null`; only the first half opens
  the link. This is a known, minor limitation: it cannot affect ASCII links at all, and the
  fallback (reuse the previous base column when the tapped cell is a continuation) is deliberately
  not implemented — it would need a continuation test to avoid treating a genuinely unlinked
  neighbour cell as a link. Candidate follow-up if users report it.

## 8. Testing

| Area | Test | Type / outcome |
|---|---|---|
| Sanitization helper | allow-list accepts the 4 schemes (any case), rejects `file:`/`intent:`/`javascript:`/`data:`/no-scheme variants, trims whitespace, null/empty safety | `LinkUrisTest` (7 tests), pure unit (`:terminal-view`) — landed |
| Side array | laziness (stays null until first write), overwrite, default row never allocates, copy/clear/resize carry values, continuation-column handling | `TerminalRowTest` (36 tests), unit (`:terminal-emulator`) — landed |
| SGR 58 parse → storage | `CSI 58;5;n m` and `CSI 58;2;r;g;b m` store index/24-bit; `CSI 59 m` resets; `SGR 0` resets; DECSC/DECRC save/restore; ED/EL fill; reflow | `:terminal-emulator` suite (222 tests) — landed |
| Hit-test resolution | link cell resolves its URI; non-link, out-of-bounds, disallowed-scheme and post-OSC-8-reset cells resolve `null` | `TerminalViewLinkHitTest` (5 tests), unit (`:terminal-view`) — landed |
| Renderer behavior | link cell gains an underline; `SGR 4` on a link is not doubled; `SGR 58` strokes the underline with the palette color | `TerminalRendererUnderlineTest` (5 tests), Robolectric (`:app`) — **landed via fallback** (below) |
| App-side dispatch | `onLinkClicked` starts an `ACTION_VIEW` intent for the URI | `TermuxTerminalViewClientLinkTest` (1 test), Robolectric (`:app`) — landed |
| Full gate | `./gradlew test` | 411 tests, 0 failures — green |

**Renderer test fallback (execution outcome).** The planned pixel test is impossible on this build
host: Robolectric 4.10's native graphics runtime reports *"not supported on Linux (aarch64)"*, and
its legacy graphics mode rasterizes nothing (a control asserting that two different contents render
differently failed — every `Bitmap` came back blank). The test class therefore observes the
renderer's **draw calls** through a recording `Canvas` subclass — text runs (with the paint's
underline flag and color), lines, paths and rects — and asserts call-sequence equality
("no double underline") and the presence/color of the stroked underline instead of final pixels.
Behavior is covered; pixel-exact appearance remains the manual device check.

Device sanity (manual): tap a real `OSC 8` link from `curl`/`printf` output in a shell, confirm
browser opens; confirm a `file:` link does nothing; confirm a colored-underline TUI renders
SGR-58 underlines in the right color.

## 9. Compatibility & non-goals

- New `default` method on `TerminalViewClient` → source-compatible for all plugin implementors;
  **binary-compatible** too (JVM default methods need no recompile). No `onLinkClicked` is added
  to `TerminalSessionClient` (links are a view concern).
- No `:termux-shared` public API change; `TermuxTerminalViewClientBase` inherits the default and
  needs no override (the empty override, if added, is documentation-only and optional).
- No changes to mouse reporting, focus events, or any other deferred conformance item.
- No UI settings/prefs; behavior is always-on where links exist.
