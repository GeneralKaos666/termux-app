# Terminal engine upgrade — workstream 1: the cell model (design spec)

**Date:** 2026-10-17
**Status:** approved 2026-10-17. Implementation plan:
[`../plans/2026-10-17-terminal-engine-cell-model.md`](../plans/2026-10-17-terminal-engine-cell-model.md).
**Premise note (2026-10-17):** `AGENTS.md` now states this app is **standalone** — never merging back to upstream
`termux/termux-app` — so the idea file's "*upstream-compatible*" constraint is superseded. The binding constraints
are now companion-app interoperability (package identity, `TermuxConstants`, the client extension points) plus
`minSdk 21` / Java 1.8. §7 and §8 are written against this.
**Origin:** user request — "*let's do this Terminal engine upgrade*" — resuming
[`../ledger/outstanding-work.md`](../ledger/outstanding-work.md) (§2, the terminal engine program), whose
brainstorming had stopped before approach selection.
**Scope:** **workstream 1 only — the cell model** — plus the two parsing halves that make its new storage live
(`SGR 4:n`, `OSC 8`). The idea file decomposes the program into six workstreams
(1 cell model, 2 rendering, 3 text/shaping, 4 graphics, 5 conformance + remaining OSC 8/underline work, 6
`:termux-shared` shims) and recommends speccing 1 first, then 2, then 3–5 as separate cycles. This spec covers
workstream 1 and defines the interfaces the later workstreams consume. Everything else is §9.

**Decisions taken by the user:**
- **Approach B — substrate first.** The cell model is built before any feature work, so console graphics, shaping and
  conformance land later as small consumers.
- **Underline style** lives in 3 of the style word's four unused bits (11–13) — no extra memory.
- **Link ids** live in a lazily-allocated per-row side array plus a buffer-level registry with GC.
- **Grapheme clusters get no new storage.** The invariant "one column = one grapheme cluster" is enforced in the
  write path instead. This is an accepted, deliberate, user-visible layout change for emoji.
- **Dirty rows** are a per-row flag plus a buffer-level change counter.
- **The image-placement layer is not refactored.** The existing bitmap registry *is* the placement layer; kitty
  placements join it in workstream 4.
- **Workstream 1 touches no file outside `:terminal-emulator`.**
- **Scope expanded 2026-10-17 (user request):** the two new cell attributes get **live producers** in this
  workstream, so neither ships as unused storage — `SGR 4:n` underline-style wiring (the parser already parses the
  sub-parameter and discards the style value) and `OSC 8` hyperlink parsing with per-cell link stamping. The items
  that stay out, and why, are listed in §9.

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

**What is missing today:** no underline *style* (single/double/curly/dotted/dashed) — the parser already handles the
`4:n` sub-parameter in `selectGraphicRendition` (lines 2487–2500), but it keeps only the boolean
`CHARACTER_ATTRIBUTE_UNDERLINE` and throws the style number away; no hyperlink storage (`doOsc`'s `switch (value)`
has no `case 8`); no dirty state on `TerminalRow` or `TerminalBuffer`, so `TerminalRenderer.onDraw` repaints every
visible row; and grapheme clusters are only approximated — each code point is measured on its own, so a ZWJ emoji
sequence fans out across several columns.

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

Underline style and link id both get **live producers in this workstream** (`SGR 4:n` and `OSC 8`, §3.5), so neither
ships as unused storage. The dirty flag's only consumer is the workstream-2 renderer, so its contract is fixed here
in §4 and tested even though nothing reads it yet.

---

## 3. Components

### 3.1 `TextStyle`
- `UNDERLINE_STYLE_NONE/SINGLE/DOUBLE/CURLY/DOTTED/DASHED` constants occupying bits 11–13, plus
  `decodeUnderlineStyle(long)`.
- Writes go through a dedicated `encodeUnderline(long style, int underlineStyle)` rather than widening `encode`, so
  the 9-bit effect mask and the truecolor flag bits (9–10) are untouched. **Trap:** `TerminalBuffer.setOrClearEffect`
  (DECCARA/DECSED) re-encodes whole words through `TextStyle.encode`, which masks effects to 9 bits and would
  silently strip underline style; it must carry the existing bits 11–13 across the re-encode explicitly. This
  round-trip is tested.

### 3.2 `TerminalRow`
- `boolean mDirty`.
- `int[] mLinkIds`, lazily allocated; a null array or cell `0` means "no link".
- **Overwrite rule:** `TerminalBuffer.setChar` stamps the cell with the buffer's current link id (§3.4) — `0`
  whenever no OSC 8 hyperlink is open — so a rewritten cell can never carry a stale link, and the
  clear-on-overwrite case falls out of the same mechanism rather than needing a special rule. `TerminalRow` itself
  only stores and returns ids.
- **Refinement (user decision 2026-10-17):** the *row* decides the exact column the id lands on, because only it
  knows about the combining-character redirect to the wide char's base column and about the neighbour cells it
  blanks while overwriting half a wide char. So `setChar` gains a link-id parameter: a genuine overwrite stamps
  the id, a zero-width continuation appended to an existing cell leaves that cell's link untouched, and blanked
  neighbours take `0`. Erase/fill paths (`blockSet`, EL/ED, DECALN) pass `0` explicitly. The buffer still always
  supplies its current id, so the overwrite rule above is unchanged.
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
- **Current-link state:** `setCurrentLinkId(int)`, stamped by `setChar`. The OSC 8 handler (§3.5) sets it; `0` means
  "no hyperlink".

### 3.5 `TerminalEmulator` — narrow producer wiring
Two small, additive edits, both inside the existing parsers. No state machine, APC or DCS work.

- **`SGR 4:n` → underline style.** `selectGraphicRendition` already detects the sub-parameter; it must keep the value
  (`0` = none … `5` = dashed) in a new `mUnderlineStyle` field instead of discarding it, and clear that field for
  `24` (and for the reset `0`). The single style-composition point `mStyle()` (line 3026) then emits the underline
  bits. `SGR 21` (legacy double underline; xterm treats it as double, some terminals as bold-off) is included with
  that ambiguity documented. DECSC/DECRC save-restore (line 2123) carries the new field alongside `mSavedEffect`.
- **`OSC 8` → hyperlink id.** `doOsc`'s `switch (value)` gains `case 8`: split the payload on the first `;` into
  params and URI; an **empty URI closes** the hyperlink (current link id back to `0`); a non-empty URI is registered
  once per unique string via `registerLink` and becomes the current link id, so all subsequently written cells carry
  it. `id=`/other params are accepted and ignored — grouping is deliberately not modelled. `setOscTypeVariables`
  gains a `case 8` length cap (§5).

### 3.6 Untouched in this workstream
`:terminal-view`, `:termux-shared`, `:app` — zero diff. In particular the renderer keeps drawing a plain underline
for any styled underline until workstream 2 draws the styles; that graceful degradation is intentional and means the
parser wiring can land with no visual regression. The alt-screen switch will call `markAllDirty()` when workstream 2
consumes the flag. Link *click handling* (hit-testing, `onLinkClicked`) and scheme sanitization remain workstreams 5/6.

---

## 4. Data flow

- **Write path** (entry points unchanged): emulator → `buffer.setChar` → `row.setChar`, with a dirty mark at the
  buffer layer and the buffer's current link id stamped into the cell. Bulk operations mark every row they touch.
  Scrolling rotates `TerminalRow` objects through the ring buffer, so each dirty flag travels with its content, and
  the change counter ticks to signal the shift.
- **Underline flow (live in this workstream):** `SGR 4:n` → `mUnderlineStyle` → `mStyle()` emits the bits → the cell
  word carries them → workstream 2 reads `getUnderlineStyle(column)` to draw the style. Until then the renderer draws
  a plain underline (it keys off `CHARACTER_ATTRIBUTE_UNDERLINE`), so nothing regresses.
- **Link flow (parsing here, clicking later):** `OSC 8` payload → URI registered once via `registerLink` → current
  link id → stamped into subsequent cells → hit-test in workstream 6 reads `getLinkIdAt` → registry resolves the URI
  → new `default` no-op `onLinkClicked` on the client interfaces. An empty URI closes the hyperlink.
- **Renderer consumption** (workstream 2; the API is shaped for it here): read `getChangeCounter()` — a tick means
  full invalidation; otherwise repaint only dirty screen rows; then `clearDirtyState()`.

---

## 5. Error handling

- Registry stores URIs verbatim; **scheme sanitization belongs to click handling** in workstream 5, so the registry
  is never an open-redirect surface. Recorded here as a designed boundary.
- Registry cap: when full, force GC; if still full, register nothing, log, and return "no link" — the existing
  bitmap-cap failure behaviour (`getFreeTerminalBitmapNum` + `Logger.logError`).
- Accessors follow existing conventions: reads are unvalidated like `getStyleAt`; writes throw
  `IllegalArgumentException` like `setChar`.
- **OSC 8 payload bound:** `setOscTypeVariables` gains a `case 8` length cap (as `case 52` already does), so an
  unbounded URI cannot grow the terminal-control buffer without limit.
- Unknown `SGR 4:n` values clamp to "single underline" rather than being dropped.
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
- Parser wiring (new cases in the existing suites): `SGR 4:1..4:5` → expected `getUnderlineStyle` on the cell, `4:0`
  and `24` clear it, `21` sets double, an unknown `4:n` clamps to single, and DECSC/DECRC round-trips the style;
  `OSC 8` stamps written cells, an empty URI closes the hyperlink, an oversized payload is ignored, and repeated
  identical URIs share one registry entry.

`TerminalTestCase` escape-sequence tests for the visible change: ZWJ family emoji occupies 2 columns, a
regional-indicator flag 2, skin-tone modifiers absorbed — plus regression runs of the existing wide-char and
combining suites.

Gates: full `./gradlew test` green; zero diffs in the `:app`, `:terminal-view` and `:termux-shared` test suites;
memory assertion that `mLinkIds` stays null for untouched rows.

---

## 7. Delivery sequence (PR shape)

Additive and invisible first, so each step lands on its own and stays bisectable. (Upstream mergeability is **not** a
constraint — per `AGENTS.md` (2026-10-17) this app is standalone and divergence is deliberate — so the ordering is
about review and blame, not about what upstream would accept.)

1. **Dirty tracking** — flag + counter + accessors, no consumer. Invisible.
2. **Underline-style storage** — bits 11–13 + accessors + DECCARA preservation. Invisible until step 4.
3. **Link registry + side array** — storage, GC, cap, accessors. Invisible until step 5.
4. **`SGR 4:n` wiring** — the parser stores the style instead of discarding it; renders as a plain underline until
   workstream 2, so no visual regression.
5. **`OSC 8` wiring** — hyperlinks parsed and stamped per cell; no click handling yet.
6. **Grapheme absorption** — the only change to *text layout*; ships with its own escape-sequence tests.

Every step leaves `./gradlew test` green and `:app` `/` `:terminal-view` `/` `:termux-shared` untouched.

**Delivery shape changed 2026-10-17 (user):** the six-step order above stands, but the six steps are **not** six
PRs. All six land on one integration branch (`feature/terminal-engine-cell-model`), one commit per step; when the
branch gate is green it is pushed and a **single** PR is opened from that branch to `master`. Per-task branches
(if used) merge into the integration branch first. The ordering's purpose — bisectable, always-green steps — is
unchanged.

---

## 8. Risks and things to verify during implementation

- **No upstream-merge constraint.** Per `AGENTS.md` (2026-10-17) the app is standalone, so these modules may be
  changed freely. The cell model is still on the hot path for every terminal byte, so steps stay additive and
  test-gated rather than being bundled into one rewrite.
- **Companion-app compatibility** replaces the old upstream constraint: the package identity and
  `TermuxConstants` package names must not change, and the client extension points the companion apps
  (`Termux:Styling`, `Termux:API`, …) build against stay source-compatible. Workstream 1 changes no interface they
  touch.
- **`SGR 21` is ambiguous** (double underline in xterm, bold-off in some terminals). We follow xterm and document it;
  the same ambiguity exists in the code today (it is currently ignored).
- **Underline styles are stored but drawn as plain underlines** until workstream 2 — intentional graceful
  degradation, verified by the absence of rendering changes.
- **Emoji layout change** is deliberate and visible. It matches kitty/Windows Terminal behaviour and fixes genuinely
  broken rendering today, but it can move wrapped lines and selection boundaries for emoji-heavy output.
- **Existing `TerminalRowTest` assertions** encode today's per-code-point width behaviour and may need updating, not
  just extending — confirm each changed expectation is a genuine improvement.
- **`mText` stays char-packed.** The renderer reads it directly, so cluster handling must not change the storage
  format; only width/absorption decisions change.
- `TextStyle.encode`'s 9-bit effect mask and DECCARA's re-encode are the most likely source of silent attribute loss.

---

## 9. Non-goals (this workstream)

Assessment of the four deferred workstreams against a 2026-10-17 user request to pull in whatever fits. Two items
were pulled in (§3.5); the rest stay out for the reasons given:

| Deferred item | Verdict | Why |
|---|---|---|
| `SGR 4:n` underline-style parsing | **in (step 4)** | The parser already parses the sub-parameter and discards the value; ~10 lines that make the new storage live |
| `OSC 8` hyperlink parsing + per-cell stamping | **in (step 5)** | Contained `case 8` in the existing `doOsc`, and it keeps the link registry from being dead storage |
| Kitty APC graphics | out (workstream 4) | A full decode + placement pipeline — new image decoding, which is workstream 4's core |
| Renderer, frame snapshot, dirty-region drawing | out (workstream 2) | Needs cursor-blink, selection, image and theme invalidation handled together; a naive dirty-row consumer produces visual artifacts, and drawing is the least unit-testable surface here |
| Shaping / ligatures | out (workstream 3) | Requires run-level whole-string drawing, i.e. the renderer |
| Image-placement unification (sixel/iTerm2 refactor) | out (workstream 4) | Refactors working, shipped code for no user benefit, against the recorded "don't regress sixel/iTerm2" risk |
| Link click handling (`onLinkClicked`, hit-testing, scheme sanitization) | out (workstreams 5/6) | Crosses into `:terminal-view` and the published client interfaces, which need Java 8 `default` methods |

Also out: no `:termux-shared` public-API change and no `:terminal-emulator` module split; no Kotlin rewrite; no
packaging change; no `targetSdk` change; no new third-party dependency.
