# TerminalView pure-seam split — design

**Date:** 2026-11-04
**Scope:** behaviour-preserving extraction of four pure seams out of `TerminalView`
(1,685 lines, `terminal-view/src/main/java/com/termux/view/TerminalView.java`), plus one
deliberate fix found while extracting the IME encoder.
**Ledger item:** `outstanding-work.md` §3.2 `[open] TerminalView (1,661 lines)`.

## 1. Why this split and not a bigger one

`TerminalView` mixes four kinds of code:

| Kind | Examples | Testable on this host? |
|---|---|---|
| Pure math / encoding | draw-band range, ctrl mapping, IME text encoding, cursor coordinates | yes, plain JUnit |
| View-bound glue | gesture listener, touch/scroll viewport, selection delegation | only with Robolectric View infra |
| Android service glue | autofill, cursor blinker (`Handler`), IME connection shell | only with Robolectric |
| Session binding | `attachSession`, `updateSize`, `setTopRow` | needs emulator + view |

Only the first kind can move without new test infrastructure: `:terminal-view` runs plain JUnit
with `unitTests.returnDefaultValues = true` and has **no Robolectric**. The chosen scope therefore
extracts exactly that kind, in ascending risk order. The rest stays in `TerminalView` and stays
listed as a limitation in the ledger; a later cycle that adds Robolectric can do the View-bound
half, and the seams defined here do not obstruct it.

Non-goals:

- No gesture, scroll/viewport, selection, autofill, cursor-blinker or session-binding extraction.
- No new dependency, no Robolectric, no instrumentation test.
- No public API change: `attachSession`, `updateSize`, `setTopRow`, the selection API, the
  autofill API and the blinker API keep their exact signatures and behaviour.
- No change to `TerminalRenderer`, `TerminalViewClient`, `:terminal-emulator` or `:app`.

## 2. Constraints and rules

- Helpers are package-private classes in `com.termux.view`, the same shape as the existing
  `LinkUris`, `RowInvalidation`, `ShapedRunPolicy` and `RunAdvancesSource`.
- Helpers import no `android.*` types. Callers unwrap `Canvas`, `Rect` and `MotionEvent` and pass
  primitives, so tests need no Android runtime.
- Moves are mechanical: code is cut and pasted, identifiers and comments preserved. A behavioural
  change is allowed only where this spec names one explicitly (only `ImeTextEncoder`).
- One commit per seam, plus one commit for the IME fix and one for the ledger/docs.
- Tests are written before the production move. For an extraction, the red state is the test class
  failing to compile against the not-yet-existing helper; after the move the same cases must pass.
- Full `./gradlew test` is green after every commit. `:app:assembleDebug` runs once after the last
  extraction to prove the app still compiles against `TerminalView`.

## 3. Seam 1 — `RenderBand`

```java
final class RenderBand {
    final int firstRow;          // absolute screen row, >= topRow
    final int lastRowExclusive;  // absolute screen row, <= topRow + screenRows

    static RenderBand forClip(int topRow, int screenRows, int rowHeight,
                              boolean clipKnown, int clipTop, int clipBottom);
}
```

Owns the row range currently computed inline in `onDraw`:

- full visible band by default: `[topRow, topRow + screenRows)`;
- when the clip is known, non-empty and `rowHeight > 0`: one row of slack above and below the
  clip, `firstRow = max(topRow, topRow + floorDiv(clipTop, rowHeight) - 1)`,
  `lastRowExclusive = min(topRow + screenRows, topRow + ceilDiv(clipBottom, rowHeight) + 1)`;
- if that produces an empty or inverted band, fall back to the full visible band;
- `ceilDivInt` moves into this class, its only caller.

`onDraw` keeps `canvas.getClipBounds(mClipBounds) && !mClipBounds.isEmpty()` as the `clipKnown`
argument, so the "do not trust the Rect when `getClipBounds` returns false" comment stays at the
call site where the Rect lives.

Tests: unknown clip, empty clip, `rowHeight <= 0`, clip inside the band (exact rows plus slack),
clip covering more than the screen (clamped), clip entirely above and entirely below the band
(full-band fallback), clip crossing the top and bottom edges.

## 4. Seam 2 — `KeyCodePoints`

```java
final class KeyCodePoints {
    static int applyControl(int codePoint);
    static int normalizeExternalKeyboard(int codePoint);
}
```

`applyControl` is the existing control chain from `inputCodePoint`:

| Input | Result |
|---|---|
| `a`–`z` | 1–26 |
| `A`–`Z` | 1–26 |
| space, `2` | 0 |
| `[`, `3` | 27 |
| `\`, `4` | 28 |
| `]`, `5` | 29 |
| `^`, `6` | 30 |
| `_`, `7`, `/` | 31 |
| `8` | 127 |
| anything else | unchanged |

`normalizeExternalKeyboard` is the bluetooth substitution: `0x02DC` → `~`, `0x02CB` → `` ` ``,
`0x02C6` → `^`, anything else unchanged. The `eventSource > KEY_EVENT_SOURCE_SOFT_KEYBOARD` gate
stays in `inputCodePoint` because it is a property of the event, not of the mapping.

Tests: every row of the table, digits `0`–`9`, unmapped punctuation, unmapped ASCII letters after
the chain, code points above `0x7F` untouched, and the three normalizations plus a pass-through.

## 5. Seam 3 — `ImeTextEncoder`

```java
final class ImeTextEncoder {
    static final class KeyStroke {
        final int codePoint;
        final boolean controlHeld;
    }

    static List<KeyStroke> encode(CharSequence text, boolean shiftDown);
}
```

`sendTextToTerminal` becomes a loop over `encode(text, mClient.readShiftKey())` calling
`inputCodePoint(KEY_EVENT_SOURCE_SOFT_KEYBOARD, stroke.codePoint, stroke.controlHeld, false)`.

Preserved semantics, in the current order:

1. iterate UTF-16 chars; a high surrogate followed by a low surrogate becomes one code point;
2. `shiftDown` → `Character.toUpperCase(codePoint)`;
3. a code point `<= 31` and not `27` sets `controlHeld = true`, replaces `\n` with `\r` first,
   then `31`→`_`, `30`→`^`, `29`→`]`, `28`→`\`, and every other value gets `+ 96` (so the AOSP
   `\n` becomes `'m'` with `controlHeld`, i.e. control-M — the current behaviour, preserved);
4. `ESC` (`27`) is never remapped and never sets `controlHeld`.

### The one intentional behaviour change

The current loop handles unpaired surrogates wrongly:

- a high surrogate at the end of the text becomes `U+FFFD` (correct today), but a high surrogate
  followed by a **non-low** char goes through `Character.toCodePoint` unvalidated, which silently
  produces an unrelated code point (e.g. `0xD800` + `a` → `0x10061`);
- a lone **low** surrogate is treated as an ordinary char and reaches
  `TerminalSession.writeCodePoint`, which throws `IllegalArgumentException` for the surrogate range
  — that is a crash on malformed IME input, and some keyboards do emit lone surrogates during
  composition.

`encode` emits `U+FFFD` for any unpaired surrogate and does not consume the char that failed to
complete a pair. Valid pairs, ASCII, shift, `\n` and the ctrl remapping are unchanged. This fix
gets its own commit so it can be reviewed or reverted independently of the extraction.

Tests: ASCII, shift uppercasing, `\n` → `'m'` with `controlHeld`, each of `28`–`31`, `0x01` → `a`
with `controlHeld`, `ESC` untouched, an emoji surrogate pair, a lone high at the end, a lone low,
a high followed by a non-low (both characters survive: `U+FFFD` then the second char), and empty
text.

## 6. Seam 4 — `TerminalGeometry`

```java
final class TerminalGeometry {
    static int cursorColumn(float x, float fontWidth);
    static int cursorRow(float y, float lineSpacing, int topRow);
    static int pointX(int column, int columns, float fontWidth);
    static int pointY(int row, int topRow, float lineSpacing);
}
```

Moves `getCursorX`, `getCursorY`, `getPointX`, `getPointY` bodies. `getCursorY` subtracts `40`
before dividing; that constant is unexplained in the current code and is preserved verbatim, with
a javadoc stating it is existing behaviour and a test pinning it. Do not derive or "correct" it
without a separate defect note.

Tests: round-trip row/point at zero scroll, non-zero scroll, the `- 40` offset, cell rounding at
half-cell, and the `column > columns` clamp in `pointX`.

## 7. Verification

Per commit:

1. new test class fails against the unextracted code (compile failure is the red state);
2. extraction applied, `./gradlew :terminal-view:test --tests ...` is not usable
   (`:terminal-view:test` rejects `--tests`), so run `:terminal-view:testDebugUnitTest`;
3. full `./gradlew test` (debug + release variants of all four modules) green.

After the last extraction, one `:app:assembleDebug`. No device check is required to call this done,
because no behaviour changes except the documented IME fix; a device smoke for soft-keyboard text,
umlauts, emoji, `Ctrl-C` and `Alt-B` is added to `manual-checks.md` for the device phase, which the
work order keeps last.

## 8. Documentation and rollback

- `outstanding-work.md` §3.2 records the four seams and narrows the remaining `TerminalView` item to
  the View-bound half (gesture, viewport, selection, autofill, blinker, session binding) with the
  Robolectric limitation restated.
- Each seam is an independent commit, so any one can be reverted without touching the others; the
  IME fix is separate again.
- No `:terminal-view` public API changes, so `:app` and the companion apps need no adjustment.
