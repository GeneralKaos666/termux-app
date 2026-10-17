# Terminal engine upgrade — workstream 1: the cell model (design spec)

**Date:** 2026-10-17
**Status:** awaiting user review (pre-planning)
**Origin:** user request — "*let's do this Terminal engine upgrade*" — resuming
[`../ideas/2026-10-14-terminal-engine-upgrade-idea.md`](../ideas/2026-10-14-terminal-engine-upgrade-idea.md), whose
brainstorming had stopped before approach selection.
**Scope:** **workstream 1 only — the cell model.** The idea file decomposes the program into six workstreams
(1 cell model, 2 rendering, 3 text/shaping, 4 graphics, 5 conformance + OSC 8 + underline styles, 6
`:termux-shared` shims) and recommends speccing 1 first, then 2, then 3–5 as separate cycles. This spec covers
workstream 1 and defines the interfaces the later workstreams consume. Everything else is §11.

**Decisions taken by the user:**
- **Approach B — substrate first.** The cell model is built before any feature work, so console graphics, OSC 8,
  shaping and conformance all land later as small consumers.
- **Underline style** lives in the style word's four unused bits (11–14) — no extra memory.
- **Link ids** live in a lazily-allocated per-row side array plus a buffer-level registry with GC.
- **Grapheme clusters get no new storage.** The invariant "one column = one grapheme cluster" is enforced in the
  write path instead. This is an accepted, deliberate, user-visible layout change for emoji.
- **Dirty rows** are a per-row flag plus a buffer-level change counter.
- **The image-placement layer is not refactored.** The existing bitmap registry *is* the placement layer; kitty
  placements join it in workstream 4.
- **Workstream 1 touches no file outside `:terminal-emulator`.**

---

## 1. Background (what exists today, grounded in code)

`TextStyle` packs each cell into a **64-bit word** stored in `TerminalRow.mStyle` (one `long` per column). The
layout is: bits 0–10 effects (11 of 16 flags used), bits 16–39 background, bits 40–63 foreground. Bits **11–14 are
unused**, and bit 15 is `TERMINAL_BITMAP`. When `TERMINAL_BITMAP` is set the word is reinterpreted: bits 16–27
bitmap number, 32–43 y, 48–59 x (`TextStyle.encodeTerminalBitmap`). `encode` masks effects to 9 bits; `decodeEffect`
masks 11 (bits 9–10 are the truecolor flags), so **bits 11–14 pass through both untouched** — they are free.

`TerminalRow` (290 lines) stores one row as `char[] mText` (packed UTF-16; a column's text is the base code point
followed by its combining characters, capped by `MAX_COMBINING_CHARACTERS_PER_COLUMN = 15`), `long[] mStyle`, and
`mSpaceUsed`, `mLineWrap`, `mHasNonOneWidthOrSurrogateChars`, `mHasTerminalBitmap`. Columns are located by walking
`mText` and summing `WcWidth.width(codePoint)` (`findStartOfColumn`).

`TerminalBuffer` (769 lines) is a circular buffer of `TerminalRow`s holding both the screen and the transcript. It
already owns a **cell-anchored image registry**: `HashMap<Integer, TerminalBitmap> mTerminalBitmaps`, with
`getFreeTerminalBitmapNum`, a 30 s `doTerminalBitmapsGC` sweep over live rows, and `removeScrolledOutTerminalBitmaps`.
That is the pattern the link registry mirrors.

**What is missing today:** no underline style (single/double/curly/dotted/dashed) — only the boolean
`CHARACTER_ATTRIBUTE_UNDERLINE`; no hyperlink storage (OSC 8 is unimplemented, workstream 5); no dirty state on
`TerminalRow` or `TerminalBuffer`, so `TerminalRenderer.onDraw` repaints every visible row; and grapheme clusters
are only approximated — each code point is measured on its own, so a ZWJ emoji sequence fans out across several
columns.

**Compatibility surface** (`:termux-shared` is published to JitPack and consumed by the plugin apps):
- `termux-shared/…/shell/ShellUtils.java:52` — the only consumer. It calls `terminalSession.getEmulator()`,
  `terminalEmulator.getScreen()`, then `getTranscriptTextWithFullLinesJoined()` /
  `getTranscriptTextWithoutJoinedLines()`. Nothing else in `:termux-shared` imports the terminal modules' internals.
- `terminal-view/…/TerminalRenderer.java` — `lineObject.mText` (public field, line 88), `lineObject.getStyle(column)`
  (line 105), `getSixelBitmap(style)` (107), `getSixelRect(style)` (111).
- `terminal-view/…/TerminalView.java` and `TextSelectionCursorController.java` — `getActiveTranscriptRows`,
  `getActiveRows`, `getSelectedText`, `getWordAtLocation`.

Consequence: `mText`'s char-based packing and `getStyle(column): long` must keep working, and the transcript methods
must keep returning byte-for-byte the same text. Everything else on `TerminalRow` is internal.

---

## 2. Architecture — where each new piece of cell state lives

| New state | Storage | Cost |
|---|---|---|
| Underline style (none/single/curly/dotted/dashed/double = 3 bits) | 3 of the 4 free bits (11–13) of the existing `long` style word | Zero |
| Link id (OSC 8) | Lazily-allocated `int[] mLinkIds` on `TerminalRow` (null until a link is written); buffer-level `id → URI` registry with GC, mirroring `mTerminalBitmaps` | Only linked rows |
| Grapheme clusters | **No new storage.** Write-path absorption keeps one cluster per column | Zero; visible layout change |
| Dirty rows | `boolean mDirty` per row (follows the `mHasTerminalBitmap` precedent) + buffer-level change counter that ticks on scroll/resize | ~10 KB at 10 000 rows |

The change counter is required because rows rotate through the ring buffer: a per-row flag alone cannot tell the
renderer that the whole screen shifted.

---

## 3. Components

### 3.1 `TextStyle`
- `UNDERLINE_STYLE_NONE/ SINGLE / DOUBLE / CURLY / DOTTED / DASHED` constants occupying bits 11–13, plus
  `decodeUnderlineStyle(long)`.
- Writes go through a dedicated `encodeUnderline(long style, int underlineStyle)` rather than widening `encode`, so
  the 9-bit effect mask and the truecolor flag bits (9–10) are untouched. **Trap:** `TerminalBuffer.setOrClearEffect`
  (DECCARA/DECSED) re-encodes whole words through `TextStyle.encode`, which masks effects to 9 bits and would
  silently strip underline style; it must carry the existing bits 11–13 across the re-encode explicitly. This
  round-trip is tested.

### 3.2 `TerminalRow`
- `boolean mDirty`.
- `int[] mLinkIds`, lazily allocated; a null array or cell `0` means "no link".
- `setChar` **clears** the cell's link id when it overwrites the cell, so a rewritten cell can never carry a stale
  link.
- `copyInterval`, `clear` and the resize copy path carry underline style and link id along with text and style.
- Accessors: `getUnderlineStyle(column)`, `getLinkId(column)`.

### 3.3 `GraphemeClusters` (new, small)
A helper owning the absorption rules. Before a code point starts a new column, the write path asks whether it
continues the cluster stored in the previous column. Rules are deliberately few and explicit:
- ZWJ continuations (previous stored text ends in U+200D),
- regional-indicator pairs (two RI code points form one 2-column flag),
- variation selectors and skin-tone modifiers (absorbed into the base column),
- existing zero-width combining characters (behaviour unchanged).

Cluster growth stays bounded by the existing 15-combining-character cap; no new unbounded path.

### 3.4 `TerminalBuffer`
- **Dirty API:** internal `markDirty` on every mutation path (`setChar`, `blockSet`, `blockCopy`,
  `scrollDownOneLine`, `resize`, `clearTranscript`, `setOrClearEffect`), plus `markAllDirty()` (for alt-screen
  switches), `isRowDirty(externalRow)`, `getChangeCounter()`, `clearDirtyState()`.
- **Link registry:** `registerLink(String uri) → int`, `getLink(int id)`, a GC sweep of ids no live row references,
  and a cap (4 096 ids).
- **Column API:** `setLinkAt(column, row, id)`, `getLinkIdAt(externalRow, column)`, `getUnderlineStyleAt(...)`.

### 3.5 Untouched in this workstream
`TerminalEmulator` (its parser sets no underline style and no link id until workstream 5), `:terminal-view`,
`:termux-shared`, `:app`. The alt-screen switch will call `markAllDirty()` when workstream 2 consumes the flag.

---

## 4. Data flow

- **Write path** (entry points unchanged): emulator → `buffer.setChar` → `row.setChar`, with a dirty mark at the
  buffer layer. Bulk operations mark every row they touch. Scrolling rotates `TerminalRow` objects through the ring
  buffer, so each dirty flag travels with its content, and the change counter ticks to signal the shift.
- **Renderer consumption** (workstream 2; the API is shaped for it here): read `getChangeCounter()` — a tick means
  full invalidation; otherwise repaint only dirty screen rows; then `clearDirtyState()`.
- **Link resolution** (workstreams 5/6): OSC 8 → `registerLink(uri)` once → id per cell → hit-test reads
  `getLinkIdAt` → registry resolves the URI → new `default` no-op `onLinkClicked` on the client interfaces.

---

## 5. Error handling

- Registry stores URIs verbatim; **scheme sanitization belongs to click handling** in workstream 5, so the registry
  is never an open-redirect surface. Recorded here as a designed boundary.
- Registry cap: when full, force GC; if still full, register nothing, log, and return "no link" — the existing
  bitmap-cap failure behaviour (`getFreeTerminalBitmapNum` + `Logger.logError`).
- Accessors follow existing conventions: reads are unvalidated like `getStyleAt`; writes throw
  `IllegalArgumentException` like `setChar`.
- Absorption cannot grow a column without bound (15-character cap).

---

## 6. Testing

Plain JUnit in `:terminal-emulator` (no JNI; `unitTests.returnDefaultValues = true`):
- `TextStyleTest` — underline encode/decode round-trip, including the DECCARA preservation case.
- `TerminalRowTest` — link array laziness (stays null until the first `setLinkAt`), overwrite clears the link,
  copy/clear carry underline + link; underline style survives `copyInterval`.
- `TerminalBuffer`/`ScreenBufferTest` — dirty marking table-driven across every mutation path; change counter ticks
  on scroll and resize; `markAllDirty`; registry GC and cap behaviour.
- `GraphemeClusters`/`WcWidthTest` — rule table: sequence → expected width/absorption.

`TerminalTestCase` escape-sequence tests for the visible change: ZWJ family emoji occupies 2 columns, a
regional-indicator flag 2, skin-tone modifiers absorbed — plus regression runs of the existing wide-char and
combining suites.

Gates: full `./gradlew test` green; zero diffs in the `:app`, `:terminal-view` and `:termux-shared` test suites;
memory assertion that `mLinkIds` stays null for untouched rows.

---

## 7. Delivery sequence (PR shape)

Additive and invisible first, so each step is reviewable upstream on its own:

1. **Dirty tracking** — flag + counter + accessors, no consumer. Invisible.
2. **Underline-style storage** — bits 11–13 + accessors + DECCARA preservation. Invisible until workstream 5.
3. **Link registry + side array** — storage, GC, cap, accessors. Invisible until workstream 5.
4. **Grapheme absorption** — the only user-visible change; ships with its own escape-sequence tests.

Every step leaves `./gradlew test` green and `:app` `/` `:terminal-view` `/` `:termux-shared` untouched.

---

## 8. Risks and things to verify during implementation

- **Upstream appetite** for touching `TerminalBuffer`/`TerminalRow` at all. Mitigation is the PR shape above:
  three invisible additive PRs, then one behaviour PR with tests.
- **Emoji layout change** is deliberate and visible. It matches kitty/Windows Terminal behaviour and fixes genuinely
  broken rendering today, but it can move wrapped lines and selection boundaries for emoji-heavy output.
- **Existing `TerminalRowTest` assertions** encode today's per-code-point width behaviour and may need updating, not
  just extending — confirm each changed expectation is a genuine improvement.
- **`mText` stays char-packed.** The renderer reads it directly, so cluster handling must not change the storage
  format; only width/absorption decisions change.
- `TextStyle.encode`'s 9-bit effect mask and DECCARA's re-encode are the most likely source of silent attribute loss.

---

## 9. Non-goals (this workstream)

No parser changes (no CSI 4:x, no OSC 8 parsing, no kitty APC) — workstream 5. No renderer, frame-snapshot or
dirty-region drawing — workstream 2. No shaping/ligatures — workstream 3. No new image decoding and no change to
the sixel/iTerm2 behaviour that ships today — workstream 4. No `:termux-shared` public-API change and no
`:terminal-emulator` module split. No Kotlin rewrite, no packaging change, no `targetSdk` change. No new
third-party dependency.
