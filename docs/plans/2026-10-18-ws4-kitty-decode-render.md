# WS4 completion — kitty graphics decode, placement and rendering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish WS4 so a kitty-graphics client's images are actually decoded, stored, placed and drawn — instead of being answered `OK` and dropped.

**Architecture:** Keep the spec's single-registry model: `TerminalBuffer.mTerminalBitmaps` maps a `bitmapNum` to a `TerminalBitmap` that stamps `+` placeholder cells and carries `imageId` plus sub-cell origin offsets. A new `mImagePlacements` index (`imageId` → `bitmapNum`s) drives `a=p` and `a=d`. Decoding is behind a package-private-injectable seam so the only Android graphics calls sit at the edge; `getSixelRect` becomes offset-aware and clamped, so `:terminal-view` needs no change.

**Tech Stack:** Java 8, Android (Bitmap/BitmapFactory), Gradle 9.2.1 / AGP 8.13.2, JUnit 3 fixture style plus JUnit 4, Robolectric 4.10 for the Bitmap-touching tests.

**Spec:** [`../specs/2026-10-18-ws4-graphics-placement-design.md`](../specs/2026-10-18-ws4-graphics-placement-design.md) — §4.2 chunking, §4.3 decode seam and limits, §4.4 storage/placement/delete, §5 replies, §6 module changes, §7 testing. Read §9 (non-goals) before adding anything.

**Context:** the previous plan ([`2026-10-18-ws4-graphics-placement-plan.md`](2026-10-18-ws4-graphics-placement-plan.md)) landed its Tasks 1–2 (parser, scaffolding) and stopped. Tasks 3–5 never shipped; this plan is those tasks.

**Status:** executed 2026-10-18 — `1fdcb66e` (T1), `cdb86841` (T2), `fcb244c7` (T3), `0709dd2f`
(T4), docs close-out (T5). Full `./gradlew test` green; the §8 device checks remain open. Per-task
progress and deviations are in the (gitignored) SDD ledger.

## Global Constraints

- Java 1.8 source/target, `minSdk 21`. No `java.util.Base64` (API 26) — use `android.util.Base64`.
- No new third-party dependency in `implementation`/`api`. Test-only additions are allowed in `testImplementation`.
- No changes to `:app` production code, none to `:termux-shared`, no new public method on `TerminalSessionClient` / `TerminalViewClient` (spec §6, §9).
- Sixel and iTerm2 behaviour must be byte-identical in effect; their tests must keep passing untouched.
- Subset only: `a=t|T|p|q|d`, `t=d`, `f=24|32|100`, keys `a,q,f,t,s,v,i,p,X,Y,c,r,x,y,w,h,C,m,d`. Everything else is rejected or ignored per spec §4.2/§9. No animation, no `U`, no shared memory/files, no `z`, no `I`, no compression, no placement groups.
- Reply codes: `OK`, `EINVAL`, `ENOENT`, `ENOSPC` only (spec §5.2). Quiet levels `q=0/1/2` per §5.3.
- Commit messages: Conventional Commits with capitalized types — `Added(terminal):`, `Changed(terminal):`, `Fixed(terminal):`.

## Review Focus

Inputs and conditions the spec implies but does not spell out; each gets a test in the task that owns the code.

1. **A chunked transfer that never terminates** (client sends `m=1` and then unrelated output, or dies) — must not corrupt the screen, leak memory, or swallow later commands. (T2, T4)
2. **A declared size the payload cannot satisfy** (`s=10,v=20,f=32` with 40 bytes instead of 800) — must reply `EINVAL` and store nothing, never throw or create a garbage image. (T1, T4)
3. **Absurd declared dimensions** (`s=100000,v=100000` with a 100-byte payload) — must be refused with `ENOSPC` *before* allocating or accumulating, not after an OOM. (T4)
4. **Deleting something already gone** (`a=d,d=i,i=77` after GC or after a re-transmit replaced it) — must reply `ENOENT` and leave every other image and cell untouched. (T3, T4)
5. **Placement at the last row with sub-cell offsets** — must auto-scroll like sixel does, clamp the source rect to the bitmap, and move the cursor exactly as `C=0`/`C=1` say. (T3, T4)

---

### Task 1: Pure payload plumbing — base64 and size arithmetic

**Files:**
- Create: `terminal-emulator/src/main/java/com/termux/terminal/kitty/KittyBase64.java`
- Modify: `terminal-emulator/src/main/java/com/termux/terminal/kitty/KittyGraphicsCommand.java`
- Test: `terminal-emulator/src/test/java/com/termux/terminal/KittyBase64Test.java` (new)
- Test: `terminal-emulator/src/test/java/com/termux/terminal/KittyGraphicsSizeTest.java` (new)

**Interfaces:**
- Produces: `KittyBase64.decode(String payload) -> byte[]` (`null` when the input is not valid base64); `KittyGraphicsCommand.expectedPayloadBytes() -> long` (`w*h*3` for `f=24`, `w*h*4` for `f=32`, `-1` when unknown/PNG, `-1` when `w<=0||h<=0`).

- [ ] **Step 1: Write the failing tests**

`KittyBase64Test` (JUnit 4, plain — no Robolectric):
- `decodesStandardAlphabet()` — `"AAAA"` → 3 zero bytes.
- `decodesWithPadding()` — `"QQ=="` → `{'A'}`.
- `returnsNullOnInvalidCharacter()` — `"A!A="` → `null`.
- `returnsNullOnImpossibleLength()` — `"A"` → `null`.
- `returnsNullOnNull()` — `null` → `null`.
- `decodesEmptyToEmpty()` — `""` → zero-length array.

`KittyGraphicsSizeTest` (JUnit 4, plain):
- `rgbSize()` — parse `"Ga=T,f=24,s=10,v=20;"` → `expectedPayloadBytes() == 600`.
- `rgbaSize()` — `f=32,s=10,v=20` → `800`.
- `pngSizeUnknown()` — `f=100,s=10,v=20` → `-1`.
- `unknownWhenDimensionsMissing()` — `f=32;` (no `s`/`v`) → `-1`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :terminal-emulator:test --tests "com.termux.terminal.KittyBase64Test" --tests "com.termux.terminal.KittyGraphicsSizeTest"`
Expected: compilation failure — `KittyBase64` and `expectedPayloadBytes()` do not exist.

- [ ] **Step 3: Implement `KittyBase64` and `expectedPayloadBytes()`**

`KittyBase64` is a small pure-Java decoder (standard alphabet, `=` padding, whitespace rejected) returning `null` for any invalid input; it exists because `android.util.Base64` would make the parser tests need Robolectric. `expectedPayloadBytes()` on `KittyGraphicsCommand` reads the already-parsed `format`, `width` and `height` fields — no new state, no Android types.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :terminal-emulator:test --tests "com.termux.terminal.KittyBase64Test" --tests "com.termux.terminal.KittyGraphicsSizeTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add terminal-emulator/src/main/java/com/termux/terminal/kitty/KittyBase64.java \
        terminal-emulator/src/main/java/com/termux/terminal/kitty/KittyGraphicsCommand.java \
        terminal-emulator/src/test/java/com/termux/terminal/KittyBase64Test.java \
        terminal-emulator/src/test/java/com/termux/terminal/KittyGraphicsSizeTest.java
git commit -m "Added(terminal): Add kitty payload base64 decoding and size arithmetic"
```

### Task 2: Chunk-transfer state machine

**Files:**
- Create: `terminal-emulator/src/main/java/com/termux/terminal/kitty/KittyGraphicsTransfer.java`
- Test: `terminal-emulator/src/test/java/com/termux/terminal/KittyGraphicsTransferTest.java` (new)

**Interfaces:**
- Produces:
  - `KittyGraphicsTransfer.accept(KittyGraphicsCommand cmd) -> KittyGraphicsTransfer.Result`
  - `KittyGraphicsTransfer.Result` with `boolean complete`, `KittyGraphicsCommand command`, `String errorCode`, `String errorMessage`, and statics `pending()`, `complete(KittyGraphicsCommand)`, `error(String code, String message)`.
  - `KittyGraphicsTransfer.reset()` and `KittyGraphicsTransfer.isActive()`.

Behaviour: non-chunked command (`moreChunks == 0`) completes immediately with itself. `m=1` stores the first command; subsequent chunks must carry no keys other than `m`/`q` and, when the accumulated payload exceeds the first command's `expectedPayloadBytes()` (when that is ≥ 0), the transfer ends with `EINVAL`. `m=0` completes with the first command's fields but the concatenated payload. A chunk arriving with no transfer active and `m != 1` completes immediately. `reset()` is called when a chunk-violating command arrives.

- [ ] **Step 1: Write the failing tests**

`KittyGraphicsTransferTest` (JUnit 4, plain):
- `nonChunkedCompletesImmediately()`.
- `twoChunksConcatenatePayload()` — `"Ga=T,i=1,s=1,v=1,f=32,m=1;AAA"` then `"Gm=0;AAA"` → complete, `payload == "AAAAAA"`, `imageId == 1`, `action == 'T'`.
- `laterChunkWithKeysOtherThanMOrQErrors()` — second chunk `"Ga=T,i=1,m=0;AA"` → `errorCode == "EINVAL"`, `!complete`.
- `laterChunkWithDifferentImageIdErrors()`.
- `oversizedAccumulatedPayloadErrors()` — `f=32,s=1,v=1` (expects 4 bytes → 8 base64 chars) then a chunk pushing it over → `"EINVAL"`.
- `strayFinalChunkCompletesImmediately()` — `"Gm=0;AA"` with nothing active → complete with payload `"AA"`.
- `resetClearsActiveTransfer()` — after `m=1`, `isActive()` is true; `reset()` then `isActive()` false.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :terminal-emulator:test --tests "com.termux.terminal.KittyGraphicsTransferTest"`
Expected: compilation failure — `KittyGraphicsTransfer` does not exist.

- [ ] **Step 3: Implement `KittyGraphicsTransfer`**

Single active transfer at a time (a second `m=1` while active is an error and resets). Keep the first command's object as the template so action/format/ids survive; accumulate the payload string. Validate the accumulated length against `expectedPayloadBytes()` after each chunk when that value is ≥ 0.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :terminal-emulator:test --tests "com.termux.terminal.KittyGraphicsTransferTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add terminal-emulator/src/main/java/com/termux/terminal/kitty/KittyGraphicsTransfer.java \
        terminal-emulator/src/test/java/com/termux/terminal/KittyGraphicsTransferTest.java
git commit -m "Added(terminal): Add kitty chunked transfer state machine"
```

### Task 3: Placement registry, offsets and deletion in the buffer

**Files:**
- Modify: `terminal-emulator/src/main/java/com/termux/terminal/TerminalBitmap.java` (new build overload + offset setters)
- Modify: `terminal-emulator/src/main/java/com/termux/terminal/TerminalBuffer.java` (`mImagePlacements`, `placeTerminalBitmap`, rewritten `deleteTerminalBitmaps`, offset-aware `getSixelRect`, drop the dead `mPlacements`)
- Modify: `terminal-emulator/build.gradle` (`testImplementation "org.robolectric:robolectric:4.10"`, `testOptions.unitTests.includeAndroidResources = true`)
- Test: `terminal-emulator/src/test/java/com/termux/terminal/TerminalBufferPlacementTest.java` (new, JUnit 4 + `@RunWith(RobolectricTestRunner.class)`)

**Interfaces:**
- Consumes: nothing (this is the storage layer the emulator will call in T4).
- Produces:
  - `TerminalBuffer.placeTerminalBitmap(Bitmap bitmap, int imageId, int col, int row, int cellW, int cellH, int originOffsetX, int originOffsetY) -> int[]` returning `{bitmapNum, scrollLines}`; reuses the existing stamping/auto-scroll.
  - `TerminalBuffer.deleteTerminalBitmaps(int imageId, int placementId, char deleteSpec, int cursorCol, int cursorRow)`; blank cells carrying the removed `bitmapNum`s, drop registry entries and index entries. `imageId == 0` matches every image.
  - `TerminalBuffer.getImagePlacements(int imageId) -> int[]` (bitmapNums, empty when absent) — for `a=p` lookup and tests.
  - `TerminalBitmap.buildOrThrow(TerminalBuffer, int bitmapNum, Bitmap, int x, int y, int cellW, int cellH, int imageId, int originOffsetX, int originOffsetY)` (package-private) plus `setOriginOffsets(int, int)`.
- Removal: `TerminalBuffer.Placement` and `mPlacements` are deleted (nothing ever added to them; `mImagePlacements` replaces their one real use).

- [ ] **Step 1: Write the failing tests**

`TerminalBufferPlacementTest` (Robolectric; `@ConscryptMode(OFF)` not needed here — no cryptography — but keep `@RunWith(RobolectricTestRunner.class)`):
- `placeRegistersImageAndReturnsBitmapNum()` — a `Bitmap.createBitmap(20, 40, ARGB_8888)` at col 1 row 0 on a 10×4 buffer → `int[0] >= 0`, `int[1] == 0`, `getStyleAt(0,1)` is a terminal-bitmap style, `getImagePlacements(5)` contains the bitmap num.
- `offsetRectsAreClampedToTheBitmap()` — place with `originOffsetX=5, originOffsetY=5`, then `getSixelRect(style)` for cell (1,0) → `Rect(1*20, 0*40, 2*20, 1*40)` is *not* expected: assert `left == 1*cellW - 5`, `top == 0*cellH - 5`, and that `left/top` are never negative (clamped to 0).
- `zeroOffsetsMatchTheOldBehaviour()` — with offsets 0 the rect equals `(x*cellW, y*cellH, (x+1)*cellW, (y+1)*cellH)`.
- `deleteByImageIdBlanksPlaceholderCells()` — after placing, `deleteTerminalBitmaps(5, 0, 'i', 0, 0)`; then `getStyleAt(0,1)` is not a bitmap style, the cell character is `' '`, `getImagePlacements(5)` is empty.
- `deleteAllClearsEveryImage()` — two images, `'a'` → both gone, all placeholder cells blank.
- `deleteAtCursorHitsOnlyIntersectingPlacements()` — image A covering cells (0..1, 0) and image B covering (5, 0); `deleteTerminalBitmaps(0, 0, 'c', 0, 0)` removes A and keeps B.
- `deleteMissingImageIsANoOp()` — `deleteTerminalBitmaps(99, 0, 'i', 0, 0)` leaves the screen and registry untouched (the caller decides the `ENOENT` reply).
- `placementAtLastRowScrolls()` — on a 3-row buffer, place at row 2 an image 2 cells tall → `scrollLines == 1` and the transcript grew by one row.
- `transcriptScanFindsStampedCellsInHistory()` — place, scroll the image into history with `scrollDownOneLine`, then delete by image id → history cells blank too.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :terminal-emulator:test --tests "com.termux.terminal.TerminalBufferPlacementTest"`
Expected: compilation failure — `placeTerminalBitmap`, `getImagePlacements` and the new `deleteTerminalBitmaps` signature do not exist.

- [ ] **Step 3: Implement the registry and offset-aware rects**

- Add `private final HashMap<Integer, Set<Integer>> mImagePlacements` and keep it in sync in `placeTerminalBitmap`, `deleteTerminalBitmaps`, `clearTerminalBitmaps` and `removeScrolledOutTerminalBitmaps`.
- `placeTerminalBitmap` stamps via the new `TerminalBitmap` build overload (which is the existing `buildOrThrow(Bitmap...)` with `imageId`/offsets stored on the record) and returns `{bitmapNum, scrollLines}`.
- Deletion scans `mLines` for stamps whose `TextStyle.getTerminalBitmapNum(style)` is in the removal set (same traversal `doTerminalBitmapsGC` already uses), blanks those cells with `setChar(col, row, ' ', TextStyle.NORMAL, 0, 0)`, then removes the registry and index entries.
- `getSixelRect` subtracts the record's origin offsets and clamps each edge into `[0, bitmap width/height]`, returning `null` if the clamped rect is empty.
- Delete the `Placement` class and `mPlacements` list with their dead branches.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :terminal-emulator:test --tests "com.termux.terminal.TerminalBufferPlacementTest"`
Expected: PASS. Then run the whole module to prove sixel/iTerm2 are unaffected: `./gradlew :terminal-emulator:test` → PASS.

- [ ] **Step 5: Commit**

```bash
git add terminal-emulator/build.gradle \
        terminal-emulator/src/main/java/com/termux/terminal/TerminalBitmap.java \
        terminal-emulator/src/main/java/com/termux/terminal/TerminalBuffer.java \
        terminal-emulator/src/test/java/com/termux/terminal/TerminalBufferPlacementTest.java
git commit -m "Changed(terminal): Register kitty placements with offsets and real deletion"
```

### Task 4: Decoder seam, dispatch, replies and limits

**Files:**
- Create: `terminal-emulator/src/main/java/com/termux/terminal/kitty/KittyGraphicsImageDecoder.java`
- Create: `terminal-emulator/src/main/java/com/termux/terminal/kitty/AndroidKittyGraphicsImageDecoder.java`
- Create: `terminal-emulator/src/main/java/com/termux/terminal/kitty/KittyImageSizing.java` (pure)
- Modify: `terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java` (`doApc`, transfer field, decoder field + package-private setter, dispatch, limits, cursor policy)
- Test: `terminal-emulator/src/test/java/com/termux/terminal/TerminalEmulatorKittyTest.java` (new, JUnit 4 + Robolectric)
- Test: `terminal-emulator/src/test/java/com/termux/terminal/KittyImageSizingTest.java` (new, plain JUnit 4)

**Interfaces:**
- Consumes: `KittyBase64.decode`, `KittyGraphicsCommand.expectedPayloadBytes()` (T1); `KittyGraphicsTransfer.accept(...)` (T2); `TerminalBuffer.placeTerminalBitmap(...)`, `deleteTerminalBitmaps(...)`, `getImagePlacements(...)` (T3).
- Produces:
  - `interface KittyGraphicsImageDecoder { Bitmap decode(int format, int width, int height, byte[] data); }` — `null` means "cannot decode".
  - `TerminalEmulator.setKittyGraphicsImageDecoder(KittyGraphicsImageDecoder)` (package-private; production default is `AndroidKittyGraphicsImageDecoder`).
  - `KittyImageSizing.targetPixels(KittyGraphicsCommand cmd, int cellW, int cellH, int imageW, int imageH) -> int[] {width, height}` — pure, returns the pixel size the bitmap must be scaled to, or `{imageW, imageH}` unchanged.

Dispatch rules to implement in `doApc()` (spec §4.4):
- `q` → decode through the decoder only (no store) → reply `OK`/error.
- `t` → decode, store under `i` (replacing an existing same-`i` image **and all its placements first**) → reply.
- `T` → as `t`, then place at the cursor with `X,Y` offsets and the `c,r,w,h` sizing → reply.
- `p` → place the stored image `i`; `ENOENT` if unknown, `EINVAL` if `i == 0`.
- `d` → delegate to `TerminalBuffer.deleteTerminalBitmaps(imageId, placementId, spec, cursorCol, cursorRow)`; `ENOENT` when the `i` form matches nothing.
- Limits: reject with `ENOSPC` when `expectedPayloadBytes()` or the decoded pixel size exceeds `TerminalBitmap.MAX_BITMAP_SIZE`; reject with `EINVAL` when the decoded byte length disagrees with `expectedPayloadBytes()`. All checks run before decode where the values allow it.
- Cursor policy: `C=1` leaves the cursor unchanged; `C=0` (default) moves it to column 0 of the row below the placement.
- Replies only when `imageId != 0`, honouring `q=1`/`q=2` exactly as `doApc()` does today.

- [ ] **Step 1: Write the failing tests**

`KittyImageSizingTest` (plain JUnit 4): `cellSpanOnly()` (`c=2,r=1` on 10×20 cells → `{20,20}`), `pixelSizeWins()` (`w=7,h=9` → `{7,9}`), `unchangedWhenNoSizeKeys()` (`{imageW,imageH}`), `zeroNeverDivides()` (`c=0,r=0` → unchanged).

`TerminalEmulatorKittyTest` (JUnit 4 + Robolectric, extends nothing; builds `new TerminalEmulator(mockOutput, 10, 4, 10, 20, 4, null)` and calls `setKittyGraphicsImageDecoder(...)` with a fake returning `Bitmap.createBitmap(1, 1, ARGB_8888)` or `null`):
- `queryRepliesOkWhenDecodeSucceeds()` — `"\033_Ga=q,i=31,s=1,v=1,f=32;AAAA\033\\"` → output `"\033_Gi=31;OK\033\\"`.
- `queryRepliesErrorWhenDecodeFails()` — fake returns `null` → `EINVAL` reply, nothing stored (`getImagePlacements(31)` empty).
- `queryDoesNotStore()` — success reply but `getImagePlacements(31)` empty and no bitmap styles on screen.
- `storeThenPlaceDrawsPlaceholders()` — `a=t,i=7` then `a=T,i=7` → the screen has at least one terminal-bitmap style and `getImagePlacements(7)` is non-empty.
- `placeUnknownIdRepliesEnoent()` — `"\033_Ga=p,i=42\033\\"` → `"\033_Gi=42;ENOENT:..."` and no style on screen.
- `quietSuppressesOkButNotErrors()` — `q=1` success → empty output; `q=1` failure → error emitted; `q=2` → empty in both cases.
- `oversizeDeclaredSizeRepliesEnospcBeforeDecoding()` — `s=100000,v=100000,f=32` with a 4-byte payload → `ENOSPC`, and the fake decoder records **zero** calls.
- `wrongPayloadLengthRepliesEinval()` — `f=32,s=2,v=2` (expects 16 bytes) with 4 bytes → `EINVAL`, decoder not called.
- `chunkedTransferStoresAtTheFinalChunk()` — two chunks as in T2 with a valid size → one `OK` reply and one stored image.
- `chunkInterruptedByUnrelatedOutputLeavesNoState()` — `m=1` chunk, then plain text `"hello"` and another APC `a=q` → no crash, no stored image, and the text is on screen.
- `deleteByImageIdClearsPlaceholdersAndRepliesOk()` — place, then `a=d,d=i,i=7` → `OK`, no bitmap styles left, `getImagePlacements(7)` empty.
- `deleteMissingImageRepliesEnoent()`.
- `cursorPolicyLeavesCursorUnchangedWhenCIsOne()` — `C=1` → `assertCursorAt(0, 0)` unchanged; default (`C=0`) → cursor at column 0 below the placement.
- `retransmitSameIdReplacesPreviousPlacement()` — `a=T,i=7` twice → exactly one placement for id 7 (`getImagePlacements(7).length == 1`).
- `nonKittyApcIsStillIgnored()` — `"\033_some\023\033_\\apc#end\033\\"` → unchanged screen, empty output (guards the `ApcTest` behaviour).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :terminal-emulator:test --tests "com.termux.terminal.TerminalEmulatorKittyTest" --tests "com.termux.terminal.KittyImageSizingTest"`
Expected: compilation failure — the decoder interface, setter and sizing helper do not exist.

- [ ] **Step 3: Implement the decoder, sizing, and `doApc()` dispatch**

`AndroidKittyGraphicsImageDecoder`: `f=100` → `BitmapFactory.decodeByteArray`; `f=32` → read `w*h` ARGB ints and `Bitmap.createBitmap(pixels, w, h, ARGB_8888)`; `f=24` → same with `0xff` alpha; return `null` on any failure or `OutOfMemoryError` (call `System.gc()` first, mirroring `TerminalBitmap`). `TerminalEmulator` keeps one `KittyGraphicsTransfer` field and resets it when a command arrives with `moreChunks == 0`. Placement goes through `getScreen().placeTerminalBitmap(...)`; scaling through `Bitmap.createScaledBitmap` using `KittyImageSizing.targetPixels(...)` when the target differs.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :terminal-emulator:test`
Expected: PASS, including the untouched `ApcTest` and `KittyGraphicsParserTest`.

- [ ] **Step 5: Commit**

```bash
git add terminal-emulator/src/main/java/com/termux/terminal/kitty/ \
        terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java \
        terminal-emulator/src/test/java/com/termux/terminal/TerminalEmulatorKittyTest.java \
        terminal-emulator/src/test/java/com/termux/terminal/KittyImageSizingTest.java
git commit -m "Added(terminal): Decode, store, place and delete kitty graphics images"
```

### Task 5: Close-out — docs, full gate, and the corrected record

**Files:**
- Modify: `docs/specs/2026-10-18-ws4-graphics-placement-design.md` (status + deviations)
- Modify: `docs/plans/2026-10-18-ws4-graphics-placement-plan.md` (Tasks 3–5 recorded as landed here)
- Modify: `docs/ledger/outstanding-work.md` (WS4 entry: what now works, what remains)
- Modify: `docs/ledger/repo-audit.md` (§1.1 resolved; §7 step 1 closed)

**Interfaces:** none — documentation and verification only.

- [ ] **Step 1: Run the full gate**

Run: `./gradlew test`
Expected: all modules PASS, with the new tests included and no reduction in the existing counts.

- [ ] **Step 2: Correct the spec**

Status becomes "implemented (parser `b6f33685`; decode/placement/render completed in this pass)" and a `## Deviations` section records, with reasons: (a) `mPlacements` removed in favour of `mImagePlacements`-style index on the registry; (b) `C=0` cursor interpretation (column 0 below the placement); (c) `ENOSPC` extended to the chunked-accumulation cap; (d) `KittyBase64` written in-repo instead of `android.util.Base64` so the parser stays header-Android-free; (e) Robolectric added as a `:terminal-emulator` **test-only** dependency.

- [ ] **Step 3: Update the old plan, ledger and audit**

Record in the old WS4 plan that Tasks 1–2 landed there and 3–5 landed in this plan (link it). Ledger: WS4's line now reads implemented-and-verified-as-far-as-host-tests-go, with §8 device checks still the only gap. Audit: mark §1.1 resolved, note that D-2 is now runnable, and strike step 1 of §7.

- [ ] **Step 4: Commit**

```bash
git add docs/
git commit -m "Changed(docs): Record the completed kitty graphics implementation"
```

---

## Verification

Host verification is the whole of `./gradlew test` (T5 Step 1). The one thing this plan cannot verify is the WS4 §8 device list, starting with `kitten icat` rendering — that stays open, and the audit's D-2 is the entry point. If `kitten icat` fails on device, the likely suspects in order are: the decoder's `f=100` path, the `c,r` cell-span scaling, and the `C=0` cursor interpretation (all three are recorded as deviations in T5 Step 2).
