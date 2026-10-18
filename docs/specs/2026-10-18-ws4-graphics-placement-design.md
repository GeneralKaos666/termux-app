# Terminal engine upgrade — workstream 4: graphics placement (design spec)

**Date:** 2026-10-18
**Status:** draft (not implemented). Implementation plan: to be written after this spec is approved.
**Premise note:** `AGENTS.md` states this app is standalone — never merging back to upstream `termux/termux-app` — so
the binding constraints are companion-app interoperability (package identity, `TermuxConstants`), `minSdk 21`,
Java 1.8, and no new third-party dependency.
**Origin:** the 2026-10-18 outstanding-work review selected workstreams 3 and 4 as the next work; this is workstream 4.
**Scope:** **workstream 4 only** — unify sixel + iTerm2 + kitty as one placement layer. The selected scope is:
unify + kitty subset (direct/PNG/RGB transmit, `a=T`/`a=t`, placement at the cursor with cell pixel offsets, image ids,
and `a=d` delete). No animation, unicode placeholders, shared memory, z-index, or the `I` (image-number) form.
**Acknowledgement:** the reply path for kitty graphics is required (full ack + `a=q` query), with quiet levels honoured.

**Decisions taken by the user (2026-10-18):**
- **Scope:** unify + kitty subset (Recommended). Sixel and iTerm2 keep behaving exactly as today.
- **Reply path:** full ack + `a=q` query, honouring `q=0/q=1/q=2` quiet levels.
- **Architecture:** A — one registry + one placement layer, cell-stamping retained as the render expression; sub-cell
  origin offsets added for kitty while sixel/iTerm2 remain unchanged. Text under an image remains the placeholder
  (stamped) behaviour, preserving sixel/iTerm2 selection/copy behaviour.

## 1. Background (what exists today, grounded in code)

- **Bitmap registry:** `TerminalBuffer.mTerminalBitmaps` is a `HashMap<Integer, TerminalBitmap>` keyed by `bitmapNum`
  in `TERMINAL_BITMAP__NUM_START .. TERMINAL_BITMAP__NUM_END - 1` (e.g. 0x1000000 and 0x11000000). Allocation uses
  `getFreeTerminalBitmapNum()`.
- **Entry points:**
  - Sixel: `TerminalBuffer.sixelStart/Resize/SetColor/SetRGBColor/ReadData`, `sixelEnd(x, y, cellW, cellH)` allocates
    a `bitmapNum`, calls `TerminalBitmap.build(this, bitmapNum, mTerminalSixel, x, y, cellW, cellH)`, stores the
    entry and returns `mScrollLines`. (`TerminalEmulator` drives DCS `q`.)
  - iTerm2 OSC 1337: `addTerminalBitmapForImage(byte[], x, y, cellW, cellH, w, h, shouldPreserveAspectRatio)` returns
    `int[] {scrollLines, cursorColumns}` (via `terminalBitmap.mCursorDelta`) and stores the bitmap. (`TerminalEmulator`
    handles OSC 1337 file/multipart.)
- **Placement (stamping):** `TerminalBitmap.buildOrThrow` computes `width = min(cols-x, ceil(bitmapWidth/cellWidth))`,
  `height = ceil(bitmapHeight/cellHeight)`; for each covered cell it calls
  `terminalBuffer.setChar(x+j, y+i-s, '+', TextStyle.encodeTerminalBitmap(bitmapNum, j, i), 0)` and records
  `mScrollLines = height - s` (auto-scrolling the buffer if it hits bottom). `TextStyle` encodes `num,x,y` in the
  upper bits (`TERMINAL_BITMAP`, `(num<<16)|(y<<32)|(x<<48)`).
- **Renderer:** `TerminalRenderer.drawTextRun` (lines ~159–176 in the current code) detects `TextStyle.isTerminalBitmap(style)`,
  fetches `mEmulator.getScreen().getSixelBitmap(style)` and `getSixelRect(style)` (derived from `x*cellW, y*cellH, (x+1)*cellW, (y+1)*cellH`)
  and draws a clipped cell slice. No sub-cell offset.
- **GC/lifecycle:** `removeScrolledOutTerminalBitmaps(row)` inspects row `r` and `r+1` for bitmap styles and removes
  those entries from `mTerminalBitmaps`; `clearTerminalBitmaps()` on reset; `doTerminalBitmapsGC(timeDelta)` throttled.
- **APC:** `ESC _ ... ESC \` collected in `mTerminalControlArgs`; `receiveApcEsc` does not call `doApc()` (commented),
  `doApc()` is empty. `mSession.write(...)` is the existing response path (DSR/DA/ReportCellSize).

## 2. Design principles

- **Single placement layer.** Treat the bitmap registry as the one placement source; all three protocols feed it via a
  unified `place()` path.
- **Behaviour preserved.** Sixel/iTerm2 are bit-for-bit unchanged in user-visible behaviour (selection/copy yields '+'
  placeholders, cells stamped, cursor deltas unchanged).
- **Minimal renderer change.** Only the source rect calculation becomes offset-aware.
- **Host-testable.** Pure parse/dispatch logic, a seam for decode, and no Android calls in the new parser.
- **Exact ack semantics.** Match kitty's reply conditions and quiet levels; replies are synchronous and use the existing
  `mSession.write` path.

## 3. Unified placement layer (Section 1 implementation details)

Introduce the image-vs-placement split so that `a=t` (store) and `a=p` (place) are separable without breaking the current
single-step paths.

**3.1 Placement/image model**
- `TerminalBitmap` remains the *image* record: `mBitmapNum`, `mBitmap`, `mCellWidth`, `mCellHeight`, `mImageId` (default 0),
  plus any metadata needed (size). Its placement geometry is expressed via placements, not by the fact that every cell
  was stamped except via lookup.
- A new internal placement record (package-private in `TerminalBuffer`): stores `bitmapNum`, `imageId`, `col`, `row`,
  `originOffsetX`, `originOffsetY`, `coveredCols`, `coveredRows` and enough to map cells to source rects.
- Cells remain stamped with `TextStyle.encodeTerminalBitmap(bitmapNum, j, i)`. The source rect lookup becomes:
  `srcX = cellIndexX*cellW - placement.originOffsetX` (etc.), i.e. subtract the placement's sub-cell origin when present.
  For sixel/iTerm2, `originOffsetX=originOffsetY=0` and the rects are identical to today.

**3.2 One stamp-and-register path**
- Replace the split return shapes with a single result: `{scrollLines, cursorCols, bitmapNum}`.
- Sixel/iTerm2 paths unchanged in effect (still create one placement that covers their raster). Kitty can create a
  placement with non-zero origin offsets (from cell+pixel placement).
- `removeScrolledOutTerminalBitmaps` continues to GC entries whose stamped cells have scrolled off (behaviour unchanged).
  `deleteTerminalBitmaps(imageId, placementId, spec)` supports deletion: for `d=i` (and variants) it clears the covered
  cells of the matching placement(s) (set to blank) and removes those placements/images as appropriate. `d=a|A` clears
  all visible placements.

## 4. Kitty graphics subset (Section 2)

**4.1 APC dispatch**
- In `TerminalEmulator`, when `ESC _` completes (`ESC \` terminator) call a new `doApc()` that dispatches on the first
  command byte(s): if the buffer starts with `G` (after optional whitespace), parse a kitty graphics command;
  otherwise ignore as today. The buffer is the exact content between `ESC _` and `ESC \`.

**4.2 Parser (pure)**
- A new host-testable `KittyGraphicsCommand` and parser class (in `:terminal-emulator`, no Android dependencies). Parse
  `G<control data>;<payload>`: control data is comma-separated `key=value`; payload is base64 (possibly chunked).
- Supported keys (only): `a,q,f,t,s,v,i,p,X,Y,c,r,x,y,w,h,C,m,d`.
- Ignored: `z,N,S,O,U,f/a/c animation keys`. Rejected (return error): `I`, `t=f|t|s`, both `i` and `I`, unknown
  unsupported values, or delete specifiers other than `d=a|A|i|c|C` (as per scope).
- Actions: `a=t` (store), `a=T` (store+place), `a=p` (place), `a=q` (query), `a=d` (delete).
- Chunking: `m=1` while more data follows, `m=0` final; subsequent chunks may carry only `m`/`q` (and for non-final must
  be consistent). The cursor position used for placement is the cursor position at the final chunk.
- Validation: payload length checks for RGB/RGBA when `s,v` provided; base64 decode is the last step before passing to
  the decode seam.

**4.3 Decode seam**
- Decoding (PNG via `BitmapFactory.decodeByteArray`, RGB/RGBA via `Bitmap.createBitmap`) goes through the existing
  `TerminalBitmap.build` paths behind an injectable interface (test seam) so host tests never call Android graphics.
- `f=24|32|100` supported. Compression (`o=z`) is **not** in this subset (we ignore `o`); if present, the command is
  rejected with `EINVAL` per the restricted key set above. The subset explicitly excludes compressed transfer.
- Limits: reuse `MAX_BITMAP_SIZE` and existing resize/crop via `resizeBitmapConstrained`.

**4.4 Storage and placement**
- `a=q` with `i`: attempt to decode/load into a transient context, emit ack based on success/failure, do not store.
- `a=t`: decode and store image under `i` (replacing any existing image with same `i`). No placement created.
- `a=T`: decode/store image under `i`, then place at cursor (respecting `X,Y,c,r,x,y,w,h,C` as in scope).
- `a=p`: place an existing stored image `i` at cursor (error `ENOENT` if not found).
- `a=d`: honour `d=a`, `d=A`, `d=i` (with optional `p`), `d=c`, `d=C` as specified; other `d` values → `EINVAL`.
- Re-transmitting an existing `i` deletes that image and all its placements first.

## 5. Reply path (Section 3)

**5.1 Format**
Replies are strings written via `mSession.write(String)`: `ESC _ G` + fields + `;` + code/message + `ESC \`.
Format: `i=<id>` included if command carried `i` (or the resolved id). `p=<placementId>` included if command carried `p`
and placement was created/referenced. Only emitted when there is an `i` (or `I`, but `I` is excluded). A query without
`i` is rejected (`EINVAL`).

**5.2 Codes**
- `OK` — success and data loaded (as per kitty: when OK, `data_loaded` must be true).
- `EINVAL` — malformed/unsupported key/value/action/medium, `i` and `I` together, query without id, payload decode/length
  mismatch.
- `ENOENT` — requested image/placement not found (`a=p`, or delete referencing missing image).
- `ENOSPC` — size exceeds limits / cannot allocate (reuse existing `MAX_BITMAP_SIZE` rejections).

**5.3 Quiet levels**
- `q=0`: reply to everything that meets the emission conditions.
- `q=1`: suppress `OK`, still emit error responses.
- `q=2`: suppress both `OK` and errors.

**5.4 Timing**
Synchronous at command completion (final chunk of chunked transmit, or immediately for non-chunked). This preserves the
detection property (`a=q` followed by `ESC [c` must receive the `a=q` reply first).

## 6. Changes to code modules

| Module | Files | Changes |
|---|---|---|
| `:terminal-emulator` | `TerminalEmulator.java` | Implement `doApc()` and wire `ESC _ ... ESC \` to it; add kitty graphics command parsing/dispatch, reply emission via `mSession.write`, and the decode seam. No change to existing sixel/iTerm2 behaviour. |
| `:terminal-emulator` | `TerminalBuffer.java` | Extend placement model: one stamp/register path, support for origin offsets, `deleteTerminalBitmaps(...)`, helpers to place images with sub-cell offsets; keep existing APIs for sixel/iTerm2. |
| `:terminal-emulator` | `TerminalBitmap.java` | Add fields for `mImageId`, `mOriginOffsetX`, `mOriginOffsetY`, `mColumn`, `mRow` (or keep via placement); adjust build paths to accept offsets. Backwards compatible. |
| `:terminal-view` | `TerminalRenderer.java` | `getSixelRect`/bitmap draw becomes offset-aware (subtract placement origin). No other structural change to the glyph stage. |
| `:terminal-emulator` | New (testable) | `KittyGraphicsCommand`, `KittyGraphicsParser` (pure Java, no Android). |
| Tests | `terminal-emulator/src/test` | Parser tests (keys, chunking, quiet levels, errors), ack formatting, and placement lifecycle tests with injected decoder. |

No changes to `:app`, no changes to `:termux-shared`, no new public methods on `TerminalSessionClient`/`TerminalViewClient`.

## 7. Testing

Host-side unit tests only (no pixel assertions):

- **Parser tests:** valid `a=T,f=100,s=10,v=20`, chunked `m=1/m=0`, `a=q` with `i`, `a=p` with missing image → `ENOENT`,
  `q=1/q=2` behaviour, reject `I`, reject `t=f`, reject unsupported delete specifiers.
- **Ack tests:** `a=q,i=5` → `ESC _ Gi=5;OK ESC \` (if decode succeeds in seam), `a=q,i=5` with decode failure → error,
  `q=1` suppresses OK, `q=2` suppresses both.
- **Placement tests:** kitty placement with `X,Y` sub-cell offsets produces correct source rects; sixel/iTerm2 placements
  have offset 0 and unchanged rects; re-transmit of `i` deletes old placements; `a=d,d=i,i=N` clears covered cells.
- **Integration seam tests:** RGB/RGBA length validation, PNG decode path invoked via seam, limits enforced.

Gates: `./gradlew test` green, no changes to existing sixel/iTerm2 tests.

## 8. Deferred device checks

- [ ] Kitty graphics render with `kitten icat` or a small test script using `a=T` with sub-cell offsets.
- [ ] `a=q` detection works (tool waits for OK before proceeding).
- [ ] `a=d` deletion clears images correctly and leaves no '+' placeholders.
- [ ] Sixel and iTerm2 still behave identically to pre-WS4.
- [ ] Scrolling: images scroll with text and are GC'd when scrolled out.
- [ ] Chunked large images transmit correctly.

## 9. Non-goals

- Animation (`a=f,a=a,a=c`), unicode placeholders (`U=1`), shared memory/files (`t=f,t,s`), z-index (`z`), image numbers
  (`I`), compression (`o=z`), placement groups/relative placements (`P,Q,H,V`).
- Changes to the existing selection/copy semantics (remain placeholder-based).
- New public APIs beyond the internal plumbing.
