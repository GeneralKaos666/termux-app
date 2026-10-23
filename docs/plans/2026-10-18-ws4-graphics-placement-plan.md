# Terminal engine upgrade — workstream 4: graphics placement (implementation plan)

**Date:** 2026-10-18
**Spec:** [`../specs/2026-10-18-ws4-graphics-placement-design.md`](../specs/2026-10-18-ws4-graphics-placement-design.md)
**Status:** partially executed — tasks 1–2 landed here (`b6f33685`, `82b54a25`, `05cbf9c6`,
`01269d32`, `aa07194f`); tasks 3–5 never shipped from this plan and were completed by
[`2026-10-18-ws4-kitty-decode-render.md`](2026-10-18-ws4-kitty-decode-render.md)
(`1fdcb66e`, `cdb86841`, `fcb244c7`, `0709dd2f`). Its task 3 (a `TerminalRenderer` change) turned out
unnecessary: the offset-aware, clamped source rect lives in `:terminal-emulator`'s
`TerminalBuffer.getSixelRect`, which the renderer already consumes unchanged. Spec status is now
`implemented`; §8 device checks deferred. Executed directly on `master`, one commit per task, each
gated on a green `./gradlew test`. No PR.
**Premise:** follow the same process as WS3 (self-review, then implementation). Sixel/iTerm2 behaviour preserved; kitty subset + full ack/a=q.

## 1. Task 1 — Kitty graphics parser (pure, host-testable)

**Goal:** Add `KittyGraphicsCommand` and `KittyGraphicsParser` with no Android dependencies. Parse `G<control data>;<payload>`, support only the subset keys (`a,q,f,t,s,v,i,p,X,Y,c,r,x,y,w,h,C,m,d`), reject/ignore as specified, handle chunking, and format replies.

**Files to create:**
- `terminal-emulator/src/main/java/com/termux/terminal/kitty/KittyGraphicsCommand.java` (package-private)
- `terminal-emulator/src/main/java/com/termux/terminal/kitty/KittyGraphicsParser.java` (package-private)

**Key rules (from spec):**
- Actions: `a=t|T|p|q|d`. Ignore `z,N,S,O,U,f/a/c` keys. Reject `I`, `t=f|t|s`, both `i` and `I` together, unsupported delete specs (`d` only `a|A|i|c|C` in scope), malformed values.
- Chunking: `m=1` more, `m=0` final. Subsequent chunks may carry only `m`/`q`.
- Reply formatting: `ESC _ G` + `i=<id>` (+`,p=<id>`) + `;` + `OK` or `CODE:message` + `ESC \`. Only emitted if `i` (or resolved id) present; `q=1` suppresses OK only, `q=2` suppresses both.
- Query without `i` → `EINVAL`.

**Tests:**
- `terminal-emulator/src/test/java/com/termux/terminal/KittyGraphicsParserTest.java` (new). Cases: `a=T,f=100,s=10,v=20`; chunked sequence; `a=q,i=5` ok/error; `q=1/q=2`; reject `I`, reject `t=f`, reject `d=Z`; both `i` and `I` rejected.

**Commit:** `Added(terminal): Add kitty graphics parser for subset commands and replies` (after tests pass locally).

## 2. Task 2 — Unified placement model in buffer/bitmap (image-vs-placement, offsets)

**Goal:** Introduce placement records so sub-cell origin offsets work while keeping stamping. Keep sixel/iTerm2 behaviour identical.

**Files to change:**
- `terminal-emulator/src/main/java/com/termux/terminal/TerminalBitmap.java`
- `terminal-emulator/src/main/java/com/termux/terminal/TerminalBuffer.java`

**Changes (grounded in existing code):**
- `TerminalBitmap`: add `mImageId` (int), `mOriginOffsetX` (int), `mOriginOffsetY` (int), `mColumn` (int), `mRow` (int) (or keep existing fields; do not break existing ctors/build paths). Existing `buildOrThrow` must still work for sixel/iTerm2 (offsets 0).
- `TerminalBuffer`: add internal placement structure (array/list of placements) tracking `(bitmapNum, imageId, col, row, originOffsetX, originOffsetY, coveredCols, coveredRows)`. 
- Unify placement: create a single stamp+register helper used by both sixel end and iTerm image add. Return a single result shape `{scrollLines, cursorCols, bitmapNum}` (or add helper). For kitty placements, offsets come from command (`X,Y`).
- `getSixelRect` already derives from cell index; renderer will be offset-aware (Task 3) — buffer just stores offsets in placement/image record. The spec states renderer change is confined to source rect calculation using offsets.
- Add `deleteTerminalBitmaps(imageId, placementIdOrSpec, ...)` supporting `d=a|A`, `d=i` (with optional placement id), `d=c|C`. For deletion, clear covered cells of matching placements (set to blank) and remove placements/images as appropriate. Re-transmit of same `i` deletes old placements/images first.
- Keep `removeScrolledOutTerminalBitmaps`, `clearTerminalBitmaps`, `doTerminalBitmapsGC` behaviour unchanged (they scan stamped cells to identify bitmaps to remove).

**Tests (unit, seam-friendly):**
- `TerminalBufferPlacementTest.java` (new): kitty placement with offsets produces correct covered span; sixel/iTerm2 offsets 0 unchanged; delete by image id clears cells; retransmit deletes old.

**Commit:** `Changed(terminal): Unify placement model with sub-cell offsets and deletion`

## 3. Task 3 — Renderer offset-aware bitmap rects

**Goal:** Make bitmap drawing offset-aware so sub-cell origins work; no change to glyph stage.

**Files to change:**
- `terminal-view/src/main/java/com/termux/view/TerminalRenderer.java`

**Changes:**
- When drawing a terminal bitmap cell, the source rect is currently `(x*cellW, y*cellH, (x+1)*cellW, (y+1)*cellH)`. It must subtract the placement's origin offset: `srcX = x*cellW - originOffsetX`, `srcY = y*cellH - originOffsetY` (using offsets from the placement/image record). Since buffer owns the record, renderer can get offsets via `mEmulator.getScreen()` (add a small accessor if needed, or extend `getSixelRect`/bitmap lookup to include offsets). Keep API minimal and internal.

**Tests:**
- Update/add renderer test covering offset-aware rects (recording canvas seam or verifying rect values). Existing sixel/iTerm2 tests must still pass.

**Commit:** `Changed(view): Make bitmap rects offset-aware for kitty sub-cell placement`

## 4. Task 4 — TerminalEmulator APC wiring + kitty dispatch with ack

**Goal:** Wire `ESC _ ... ESC \` to `doApc()` and implement kitty graphics dispatch with full ack/a=q, honouring quiet levels. Replies via `mSession.write`.

**Files to change:**
- `terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java`

**Changes:**
- Call `doApc()` in `receiveApcEsc` when `ESC \` terminates (currently commented). Implement `doApc()` to dispatch: if starts with `G`, parse with `KittyGraphicsParser` and handle actions `t/T/p/q/d` against buffer/emulator state. 
- Chunked transfer: accumulate payload across consecutive `m=1` chunks; final `m=0` executes. Cursor position for placement is at final chunk.
- Decode seam: use existing `TerminalBitmap.build` paths for `f=24|32|100` (direct medium only). RGB/RGBA need `s,v` and exact length; PNG via `BitmapFactory`. No compression.
- Replies: format via parser/responder, write with `mSession.write(...)`. `q=1` suppresses OK, `q=2` suppresses both; only when `i` present. Query without `i` → `EINVAL`. 
- On retransmit of existing `i`, delete old placements/images first. `a=q` must attempt load, reply, then not store (and may clean up transient state).

**Tests:**
- `TerminalEmulatorKittyTest.java` (new): ack formatting emitted, `a=q` detection path, quiet levels, chunked finalization, error codes.

**Commit:** `Added(terminal): Wire kitty APC dispatch with subset support and ack path`

## 5. Task 5 — Integration & docs close-out

**Goal:** Wire everything end-to-end, update docs status, run full suite.

**Files to change:**
- `docs/specs/2026-10-18-ws4-graphics-placement-design.md` (status → implemented, reference commits)
- `docs/ledger/outstanding-work.md` (update §7 WS4 status if present; record deferred checks)

**Verification:**
- `./gradlew :terminal-emulator:test :terminal-view:test :app:testDebugUnitTest` all green
- `./gradlew test` green
- `./gradlew assembleDebug` green

**Commit:** `Changed(docs): Record WS4 graphics placement implementation and status`

## Notes (from spec/decisions)
- Sixel/iTerm2 unchanged. Selection/copy remains placeholder-based.
- No animation, U, I, t!=d, o=z, z-index, relative placements.
- Replies synchronous at final chunk.
