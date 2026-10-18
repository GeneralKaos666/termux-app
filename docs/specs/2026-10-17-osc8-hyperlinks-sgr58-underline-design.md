# Design: WS5 — OSC 8 Hyperlinks End-to-End + SGR 58 Underline Color

**Date:** 2026-10-17
**Status:** Draft — awaiting user review (brainstorming path: architectural, pre-plan)
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
`onSingleTapUp` client forward does not fire for that gesture). Selection and mouse-tracking
behavior are unchanged: if `isSelectingText()` is true the existing early-return runs first; if
mouse tracking is active, `onUp` already consumed the gesture for mouse events before
`onSingleTapUp` runs, so links never hijack drag/selection.

### 4.2 Coordinate math

```
column = (int)(event.getX() / mRenderer.getFontWidth())          // 0-based
row    = (int)(event.getY() / mRenderer.mFontLineSpacing)        // 0-based visible-screen row
```

This mirrors how the renderer positions runs (cell grid origin at the view's top-left, no extra
top margin offset). Bounds are checked against `mEmulator.mColumns` / `mEmulator.mRows`; taps
outside the grid are treated as non-links.

### 4.3 Resolution

```java
int linkId = mEmulator.getScreen().getLinkIdAt(row, column);
if (linkId != 0) {
    String uri = mEmulator.getScreen().getLink(linkId);   // WS1 registry lookup, null if unknown
    if (uri != null) { sanitize → dispatch or drop }
}
```

`getLinkIdAt` never allocates (returns 0 for null rows), so a tap on scrollback/blank area costs
nothing.

### 4.4 Sanitization (security boundary)

The URI arrives from untrusted terminal output (a remote shell, a `curl | …` script, tmux
passthrough). It is treated as untrusted input at this boundary.

- **Allow-list:** `http://`, `https://`, `ftp://`, `mailto:` — case-insensitive prefix match on
  the trimmed URI.
- **Everything else** (`file:`, `intent:`, `javascript:`, `data:`, no-scheme relative strings,
  anything with embedded control characters): dropped, with a `logWarn` on the client. Never
  dispatched to `onLinkClicked`.
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

### 6.4 Reset semantics

- `SGR 59` → stored value back to `COLOR_INDEX_FOREGROUND` (default → renderer falls back to
  foreground color).
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
- **Wide characters:** hit-test uses column math; a link covering a double-width cell spans both
  columns in WS1's stamping, so either column resolves the same id.

## 8. Testing

| Area | Test | Type |
|---|---|---|
| Sanitization helper | allow-list accepts 4 schemes (any case), rejects `file:`/`intent:`/`javascript:`/`data:`/no-scheme variants, trims whitespace, null/empty safety | pure unit (`:terminal-view`, next to `LinkUris`) |
| Side array | laziness (stays null until first write), overwrite, default row never allocates, copy/clear/resize carry values, GC of unreferenced ids (mirrors WS1's `TerminalRowTest` patterns) | unit (`:terminal-emulator`) |
| SGR 58 parse → storage | `CSI 58;5;n m` and `CSI 58;2;r;g;b m` store index/24-bit; `CSI 59 m` resets to default | unit (`TerminalTestCase`) |
| Hit-test math | column/row conversion + bounds for taps at grid edges | unit (Robolectric or pure helper) |
| Renderer link underline | link cell without SGR underline → underline drawn; with SGR underline → no double-draw (as feasible in Robolectric) | Robolectric (`:app` or `:terminal-view` test) |
| Full gate | `./gradlew test` green; `:terminal-view`/`:terminal-emulator` suites unchanged otherwise | CI-style run |

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
