# Terminal engine upgrade — workstream 3: run shaping and ligatures (design spec)

**Date:** 2026-10-18
**Status:** implemented (commits `d5bb6baa`, `94d29f2c`, `30d9a9e6`); the §7 device checks are outstanding.
Implementation plan: [`../plans/2026-10-18-ws3-shaping-ligatures.md`](../plans/2026-10-18-ws3-shaping-ligatures.md).
**Premise note:** `AGENTS.md` states this app is standalone — never merging back to upstream `termux/termux-app` — so
the binding constraints are companion-app interoperability (package identity, `TermuxConstants`, the client
extension points), `minSdk 21`, Java 1.8, and no new third-party dependency (the workstream-1 precedent).
**Origin:** the 2026-10-18 outstanding-work review, where the user selected workstreams 3 and 4 as the next work;
each gets its own spec → plan → implementation cycle, and this document is workstream 3.
**Scope:** **workstream 3 only** — the renderer's shaping behaviour (grid-snap verification with a per-cell
fallback) plus a user-facing ligature toggle. Workstream 4 (graphics placement) is a separate later cycle.

**Decisions taken by the user (2026-10-18):**
- **Scope: ligatures + grid-snap fallback**, not ligatures-only and not a bundled HarfBuzz stack. Shaped advances
  are measured and checked against the cell grid; runs that don't fit fall back to cell-local drawing. Uses the
  Minikin shaping already in the draw path — no new dependency.
- **Ligatures default ON, toggleable** in the appearance screen.
- **Approach 1 (measurement-only verification)** over Approach 2 (cluster-aware restructuring of `drawTextRun`).
  The cursor/selection run-split behaviour stays as it is: a ligature straddling a cursor or selection boundary
  renders as its base glyphs.
- **The toggle is deterministic** — explicit `"+liga,+calt"` when on, `"-liga,-calt"` when off — decided when the
  test device turned out to be disconnected, so no on-device probe of the platform's default feature settings can
  gate the design. Under this rule the semantics of the toggle do not depend on any unverified platform default.
- **Grid-snap verification is API 29+** (found during planning, 2026-10-18: `Paint.getTextRunAdvances` is API 29,
  not 21 — §4). The user chose "verify on 29+, single span below": where shaped per-char advances are available the
  policy and the per-cell fallback run; below 29 a run draws as one shaped span (today's glyph behaviour, ligatures
  still form, `mes` is the shaped total). The ligature toggle itself applies on every API level.
- **Device checks are deferred** while the device is disconnected (§7), recorded here and in
  `../ledger/manual-checks.md` (D-1).
- **Delivery: straight onto `master`**, feature-style, one commit per task, each gated on a green local
  `./gradlew test` (the workstream-2 shape). No branch, no PR.

---

## 1. Background (what exists today, grounded in code)

`TerminalRenderer.render` (`terminal-view/…/TerminalRenderer.java`) walks each row column by column and groups
cells into **runs**. A run breaks on: style change, cursor, selection, link id, underline colour, terminal-bitmap
cells, and per-cell `fontWidthMismatch` (a cell whose *unshaped* `measureText` differs from
`WcWidth × mFontWidth` by more than 1% of a cell, detected at lines 166–168). The row loop accumulates the run's
unshaped measured width (`measuredWidthForRun`) and passes it into `drawTextRun` as `mes`.

`drawTextRun` paints, in order: background rect, cursor rect, then the glyphs — one `Canvas.drawTextRun` call per
run on API 23+ (`drawText` below that), with context range equal to the run itself — then the underline pass.
If `mes` differs from the run's cell width by more than 1%, the whole run is horizontally scaled onto its cells
(lines 261–269) — today's containment mechanism for mismatched glyphs.

Grounding facts:

- **Shaping already happens.** Every glyph call goes through Android's Minikin/HarfBuzz text stack; the run-level
  context range is what allows cross-cell substitution (ligatures, contextual forms). Nothing in the repo sets
  font-feature settings, so today's ligature behaviour for a sideloaded font is whatever the platform defaults do
  — **unverified** (§7, deferred probe).
- **The grid is only ever checked against unshaped measures.** A run whose *shaped* output no longer matches the
  cell grid (a ligature glyph with a non-multiple advance, or script shaping that changes widths) passes the
  current checks and draws with drift, while cursor/selection/underline rects stay cell-exact.
- **Users can install any TTF.** The appearance screen copies a chosen font to
  `TermuxConstants.TERMUX_FONT_FILE` (`AppearanceStore`, max 8 MB, validated as a font), and
  `TermuxTerminalSessionActivityClient.checkForFontAndColors()` (line 528) loads it with
  `Typeface.createFromFile` and applies it via `TerminalView.setTypeface`.
- **The renderer is already rebuilt on font/size changes:** `TerminalView.setTextSize`/`setTypeface` construct a
  new `TerminalRenderer(textSize, typeface)`, which owns the single `mTextPaint`.
- **The reload hook already exists:** the appearance screen's Apply drives
  `ACTION_RELOAD_STYLE → reloadActivityStyling → checkForFontAndColors()`.
- **Workstream 1** put grapheme-cluster absorption in the write path (`GraphemeClusters`), so one column already
  holds one cluster. **Workstream 2** gave the renderer row-range dirty drawing and the
  `RowInvalidation`/overlay protocol. Both are shipped and are constraints, not targets.

**Compatibility surface:** `:termux-shared` imports nothing from `:terminal-view` internals; the published client
interfaces (`TerminalSessionClient`, `TerminalViewClient`) are untouched by this workstream — no new callbacks
(workstream 6's lane). Zero `:terminal-emulator` diff.

## 2. Architecture — components

| # | Component | Change |
|---|---|---|
| 1 | `ShapedRunPolicy` (new, pure, `:terminal-view`) | The grid-snap decision (contract below). No Android types. |
| 2 | `TerminalRenderer` | Glyph stage of `drawTextRun`: measure shaped advances → policy → single call or prefix + per-cell calls. `mes` parameter removed; shaped totals feed the existing scale hack. Ligature flag set on the one paint at construction. |
| 3 | `RunAdvancesSource` (new, package-private test seam, `:terminal-view`) | Injectable advances provider; `forPaint` delegates to `Paint.getTextRunAdvances` on API 29+ and returns `null` below (single-span draw, no policy). |
| 4 | `TerminalView` | `setLigaturesEnabled(boolean)` mirroring `setTypeface`, carrying the flag through renderer reconstruction. |
| 5 | `:app` — `AppearanceStore`, appearance screen, `checkForFontAndColors()` | `appearance_ligatures` boolean (default `true`), a toggle in the appearance screen's font section, applied through the existing reload hook. |

**`ShapedRunPolicy` contract:**

- Input: the ordered shaped advances of one run (as written by `RunAdvancesSource`), the run's cell count, and
  the cell width. Tolerance: **0.01 × cellWidth** (1% of a cell — the same spirit as today's
  `fontWidthMismatch` check), inclusive: a boundary exactly at tolerance counts as aligned.
- Compute cumulative glyph-boundary positions. Every boundary — including the final total — must lie within
  (inclusive) tolerance of an integer multiple of `cellWidth`.
- Return **`-1`** when all align (the run draws as one call), otherwise the **smallest cell boundary that does
  not coincide with a glyph boundary within tolerance** — the first place the run's glyphs and cells disagree.
- Boundary-based, not cluster-identity-based: it works whether the platform's `getTextRunAdvances` reports
  per-glyph or per-cluster entries, because cumulative positions are what the grid is checked against.
- Degenerate input (zero/negative/`NaN` advances, empty runs) classifies as misaligned — the fallback is the
  graceful degradation.

**`TerminalRenderer` glyph stage** (replaces lines 306–311 only; background, cursor, selection and the
underline/colour passes keep their current structure):

1. Ask `RunAdvancesSource` for the shaped advances of `[startCharIndex, startCharIndex + runWidthChars)` with
   context equal to the run, `isRtl = false`.
2. `policy = ShapedRunPolicy.firstMisalignedCell(advances, runWidthColumns, mFontWidth)`.
3. `policy == -1` → one `drawTextRun` call for the whole run (ligatures form naturally), `mes` = shaped total.
   Common case: a monospace font with exact-multiple ligature advances never leaves this path.
4. `policy == j ≥ 1` → draw cells `[startColumn .. startColumn + j)` as one glyph call, then restart the stage at
   cell `j`. **The full-run measurement only finds the split point — every span after a split (the prefix and
   everything after it) is measured afresh with its own context**, because Minikin re-shapes a shorter context
   (a ligature whose chars straddle the split point re-substitutes as its fragments). `mes` for each span is its
   own fresh shaped total, so the scale branch condenses a span whose own output is off its cell count. A
   one-cell span is drawn directly (its scale handling is today's behaviour) — **the policy never re-runs for a
   one-cell span, so no recursion**. For a run of one column the stage is skipped entirely.
5. Per-cell contexts contain no cross-cell substitution, so no glyph can drift outside its rect; cursor,
   selection and underline rects — drawn once per run as today — therefore stay honest.

**Ligature flag:** `TerminalRenderer(int textSize, Typeface typeface, boolean ligatures)`. At construction,
`mTextPaint.setFontFeatureSettings(ligatures ? "+liga,+calt" : "-liga,-calt")`. Measurement and drawing share
this one paint, so they always agree. `TerminalView` stores the flag like `mTextSize`/`mTypeface` and threads it
through `setTextSize`/`setTypeface` reconstruction; `setLigaturesEnabled` rebuilds only on change.

**Toggle plumbing:** `AppearanceStore` persists `appearance_ligatures` in `termux_appearance` (alongside the
frozen cursor keys — a new key, no migration, no change to any existing `app:key`). The appearance screen shows
it in the font section (`AppearanceScreen.kt`'s `appearance_section_font` `SectionTitle`). On Apply the existing
`ACTION_RELOAD_STYLE → reloadActivityStyling → checkForFontAndColors()` path runs;
`checkForFontAndColors()` reads the boolean (default `true`) and calls
`mActivity.getTerminalView().setLigaturesEnabled(value)` next to the existing `setTypeface` call.

**`RunAdvancesSource`:** package-private interface `int advances(char[] text, int start, int count, float[] out)`
— returns the number of entries written and must overwrite `[0, n)` of `out`. The production factory
`forPaint(Paint)` is the API gate: on API 29+ it wraps `mTextPaint.getTextRunAdvances(...)`; below 29 that method
does not exist, so it returns `null` and the glyph stage draws the run as one span (policy not consulted, ligatures
still form — see §4). Tests inject scripted arrays (aligned or misaligned) so every policy path is reachable
deterministically on this host, independent of what Robolectric's font stack returns. The renderer keeps a
grow-only member buffer, resized only when a run exceeds it.

## 3. Data flow

Per rendered (dirty) row:

```
WS2 row range → existing column loop builds runs (break rules unchanged)
  → glyph stage of drawTextRun:
      RunAdvancesSource.shaped advances of the run (context = run, isRtl = false)
      ShapedRunPolicy.firstMisalignedCell(...)
        ├─ -1  → one glyph call for the whole run; mes = shaped total; scale iff off-grid
        └─ j≥1 → glyph call for cells [start .. j), then restart at cell j — each span
                 re-measured with its own context (mes = its own shaped total),
                 one-cell spans draw directly (no policy re-run)
  → unchanged passes: background, cursor, selection, underline/colour, WS2 protocol
```

- The scale hack keeps its structure; its `mes` now comes from the shaped measurement of the span actually being
  drawn (run, prefix, or single cell) instead of the accumulated unshaped per-codepoint sums.
- `measuredWidthForRun` accumulation in the row loop is removed (the shaped measurement replaces it);
  per-cell `fontWidthMismatch` detection and the run breaks it causes stay as-is.
- `isRtl = false` always: cells are drawn LTR-anchored; bidi reordering is out of scope (§9).

## 4. Error handling

- `RunAdvancesSource` returning `0`, negative, or non-finite values → misaligned → fallback. No exceptions, no
  log spam; the fallback path is the degradation.
- Buffer: grow-only member array, no per-frame allocation churn; `n` (entries written) is the only length the
  policy may read — stale tails beyond `n` are never consulted.
- One-cell runs bypass the policy; the scale branch contains them exactly as today (no recursion by
  construction).
- The paint's feature settings are set once at construction — no runtime reconfiguration surface, no
  measurement/draw divergence possible.
- `checkForFontAndColors()` is wrapped in its existing try/catch; a missing pref key reads as the default
  (`true`).
- **API levels (corrected during planning, 2026-10-18):** `Paint.getTextRunAdvances(char[], int, int, int, int,
  boolean, float[], int)` is **API 29**, not 21 (verified against
  `platforms/android-36/data/api-versions.xml` — `since="29"` — and the platform docs; `javap` on the `android-34`
  and `android-36` stubs shows no older overload). The `drawTextRun` vs `drawText` branch (API 23) is untouched and
  the glyph stage works with either. Below API 29 the shaped per-char advance array does not exist, so
  `RunAdvancesSource.forPaint` returns `null` and the glyph stage draws the whole run as one span (the policy is not
  consulted; ligatures still form at draw time, and the scale branch's `mes` is the shaped total). Grid-snap
  verification and the per-cell fallback are therefore **API 29+ capabilities** — the user's choice, 2026-10-18.
  If lint flags `getTextRunAdvances` on newer SDKs, suppress with an explanatory comment rather than forking.

## 5. Testing

Host-side only (Robolectric cannot rasterize on this host — Linux aarch64, recorded in workstream 5), so no
pixel assertions anywhere; pixel truth is deferred with the device checks (§7).

- **`ShapedRunPolicyTest`** (plain JUnit, `:terminal-view/src/test`): all-aligned → `-1`; a 3.7-cell ligature
  advance fails at the correct cell boundary; tolerance edge (exactly at tolerance → aligned, just over →
  misaligned); cumulative float epsilon over a long run; final-total-only misalignment; zero/`NaN`/negative advances →
  misaligned; empty and single-cell inputs; per-glyph vs per-cluster array granularity produce the same verdict
  for the same cumulative positions.
- **Renderer call-sequence tests** (recording `Canvas`, injected `RunAdvancesSource`):
  aligned run → exactly one glyph call spanning the full char range at the run's x;
  misaligned at cell `j` → prefix call then per-cell calls at exact cell origins, remainder re-checked;
  `mes` → scale only when the drawn span's shaped total is off-grid; one-column runs never consult the policy;
  background/cursor/underline drawn once per run regardless of glyph split; terminal-bitmap cells and
  `fontWidthMismatch` behaviour unchanged.
- **Paint-state test:** renderer with ligatures on → `mTextPaint.getFontFeatureSettings()` equals `"+liga,+calt"`;
  off → `"-liga,-calt"`.
- **`AppearanceStoreTest`:** `appearance_ligatures` round-trips; default is `true` when absent.
- **No host test for the API gate:** `Build.VERSION.SDK_INT` is the `android.jar` stub constant (`0`) under
  `:terminal-view`'s plain-JUnit suite, so `RunAdvancesSource.forPaint` cannot be exercised for both branches there;
  the gate is a one-line build check and belongs to the §7 legacy note.
- **Gates:** full `./gradlew test` green; zero test diffs in `:terminal-emulator` and `:termux-shared`.

## 6. Delivery sequence

Directly on `master`, feature-style, one commit per task, each with a green local `./gradlew test`:

1. `ShapedRunPolicy` + `RunAdvancesSource` + their tests (invisible; nothing consumes them yet).
2. Renderer wiring: glyph stage, shaped `mes`, removal of `measuredWidthForRun` + call-sequence tests.
3. Toggle: renderer flag, `TerminalView.setLigaturesEnabled`, `appearance_ligatures` store + appearance-screen
   UI, `checkForFontAndColors()` wiring + store/paint tests.

Commits follow the Conventional Commits rules in `AGENTS.md` (capitalized `Added`/`Changed`/`Fixed` types).

## 7. Deferred device checks

The physical device (NX769J / Android 14, used for the workstream-5 checks over wireless adb) was disconnected
when this spec was written, so all on-device verification was deferred there. **Most of it ran on 2026-10-20**
once the device was reconnected; the ticks and notes below record what passed and what is still outstanding.
Recorded here **and** appended to the device-check ledger in `../ledger/manual-checks.md` (D-1):

Verified on-device on 2026-10-20/21 (NX769J / API 34, current `master`, FiraCode + Hack + JetBrains Mono
installs). Full method, numbers and evidence frames: `~/.tmp/opencode/device-checks-2026-10-20.md`.

**Summary: 6 checks fully passed (Minikin probe, ligature toggle for both Fira Code and JetBrains Mono,
cursor **and** selection, Arabic, perf, default-font regression), 1 still deferred (API 21–28 legacy path).**

- [x] **Minikin-default probe:** with a ligature font installed, print `-> => === != >= |> <!-- -->` on the
      *current* build and screencap — evidence of what the platform default actually does (now informative only,
      not load-bearing: the toggle is explicit in both states).
      → Answered by the ON/OFF A/B below: the operator ligatures render **only** with `+liga,+calt`; the
      un-requested (platform-default) form of these strings is the un-ligated one.
- [x] **Ligature confirmation:** toggle ON renders ligatures for Fira Code / JetBrains Mono; OFF restores base
      glyphs.
      → **Both fonts confirmed on-device.** Same-frame A/B, pixel diff with Fira Code: `-> -> -> ->` changes
      448 px, `=> === !== ==` changes 1010 px; controls `fi fl ffi ff` and `mmm mmm lll` change 0 px.
      **JetBrains Mono** (fetched from the upstream repository, installed as `font.ttf`) behaves the same:
      `->` changes 804 px, `=> === !== ==` changes 1475 px, `fi fl ffi ff` changes 0 px, and the
      `mmm mmm lll` control changes only 21 px confined to 3 scanlines on one edge — antialiasing noise,
      not a glyph change.
      Cell-exact proof with a ruler line (`A->B`, `A==B`; cell = 16 px, so `->` must sit inside x18–50):
      **ON** draws one joined 28 px run at x20–47; **OFF** draws two separate glyphs (`-` at 21..29,
      `>` at 37..47). Both states keep the art inside its two cells and leave `B` cleanly on cell 3 —
      i.e. the ligature is condensed inside its cell box and does **not** spill.
      Cavil worth recording: the pre-existing `ndot-firacode-nerd.ttf` install showed 0 changed pixels ON vs OFF —
      the toggle is a no-op for that build, so "ligatures work" cannot be assumed from the installed font alone.
- [x] **Cursor/selection accuracy** over ligature text (block cursor and a selection spanning ligatures).
      → **Both halves verified.** Cursor: BAR style (`appearance_cursor_style = 2`), parked at columns
      0/2/8/10 on a ligature line with `CHA`; fitted `x = 2.00 + 16.000 × col` with **0.00 px residual at
      every column** — the cursor uses the same grid the ligature art is snapped to.
      Selection: a real long-press-drag (`input motionevent DOWN → MOVE… → UP`, which succeeded where
      `input swipe` did not) selects text and shows the Material handle drawables. **Selection is rendered
      as a colour inversion, not an overlay** — `TerminalRenderer` passes
      `reverseVideo || invertCursorTextColor || lastRunInsideSelection` to `drawTextRun`, and the selection
      rect comes from `selx1`/`selx2` in *columns*. Empirically the selected row flipped from white-on-black
      to black-on-white with the background landing on #f0f6ff, starting at cell 0.00 on the 16 px grid.
      Because it is a swap of the two colours, the WCAG contrast ratio is **identical** before and after
      selection (measured 19.33 both ways), so a selection spanning a ligature can never swallow its text;
      and because only a colour flag changes, glyph positions cannot shift. Note the corollary: cells whose
      fg already equals bg (e.g. ANSI 0 on the dark presets) stay invisible when selected — they were
      already invisible unselected.
- [x] **Arabic / complex-script sample:** no drift beyond cell bounds; cursor/selection rects align.
      → **No drift.** `A|مرحبا|END` occupies exactly 11 cells (last lit col 10, col 11 empty); the joined
      Arabic cluster spans cells 2–6 and does not spill. Mixed bidi `B|abc مراحبا abc|END` occupies exactly
      20 cells. Cursor/selection rects over Arabic were not measured separately; both are computed from
      column indices, so they are script-independent by construction (and the selection mechanism is now
      verified — see the cursor/selection item).
- [x] **Perf smoke:** heavy scroll (`yes` / `git log --color | less -R`) with no visible jank; per-run
      measurement overhead bounded.
      → Controlled A/B, identical 50 000-line ligature-rich burst: **ON is not slower than OFF** (p50 25 vs
      26 ms; ON had *fewer* slow draw-command frames, 4 vs 9). The shaping measurement does not add visible
      cost, matching §5's prediction that it replaces per-codepoint `measureText`. Caveat: terminal repaints on
      data arrival, not vsync, so gfxinfo's absolute "janky %" is not meaningful; only the ON/OFF delta is.
      `git log --color | less -R` was not run specifically (no colour-heavy repo on the device); the `yes`-style
      burst covers the same repaint path.
- [x] **Regression:** default monospace font renders byte-identically with the toggle both ways (feature strings
      are no-ops for fonts without those features — verify).
      → **Confirmed: 0 differing pixels** with `font.ttf` removed (built-in monospace), both frames rendering the
      full probe (identical lit-pixel counts per row). The `+liga,+calt`/`-liga,-calt` strings are genuine no-ops
      for a font without those features.
- [ ] **Legacy path (API 21–28), inspection-only for now:** the available device is Android 14 (API 34), so the
      below-29 branch (no shaped per-char advances → single-span draw) cannot be exercised here. It is a one-line
      `Build.VERSION.SDK_INT >= 29` gate with no host coverage (§5); if an API 21–28 device becomes available,
      confirm ligatures still form and the run total scale still applies.
      → **Still deferred:** no API 21–28 device available (only API 34). Unchanged.

## 8. Risks and things to verify during implementation

- **`getTextRunAdvances` fill semantics** (per-glyph vs per-cluster, how many entries it writes) are pinned by
  the `RunAdvancesSource` contract (`n` written, `[0, n)` overwritten) and tested at the seam; the policy reads
  only `[0, n)`. It is API 29+ only, so the seam also carries the API gate (§4): below 29 the source is `null` and
  the glyph stage never consults the policy.
- **Robolectric's font stack** may return zeroes or unaligned values — by design the suite does not depend on
  it: every policy path is reachable through injected advances. The one real-text integration point runs on the
  device (§7).
- **Misaligned runs degrade in two documented ways, both grid-honest.** An interior split point re-shapes a
  shorter context, so a ligature whose chars straddle the split renders as its fragments (`=` + `=>` instead of
  `==>`) — different glyph art, exact cell alignment. A misalignment with no interior split (the run's total
  only) draws as one span whose scale correction condenses its glyphs onto the cells, today's
  `fontWidthMismatch` treatment generalized. Well-made ligature fonts (exact-multiple advances) hit neither.
- **Cost:** one extra shaping measurement per drawn run. Runs are only drawn for dirty rows (WS2), and the
  measurement *replaces* the N per-codepoint `measureText` calls that fed `mes`, so the common case is plausibly
  cheaper than today. Perf smoke deferred (§7).
- **`"+liga,+calt"` may enable alternates** the platform default would not have (if the default was off). That
  is the decided behaviour (default ON), harmless for fonts without the features, and reversible by the toggle.
- **New pref key** `appearance_ligatures` is additive; the 13 frozen leaf `app:key` values of the settings-IA
  work are untouched, and no migration exists or is needed.
- **Run splits at cursor/selection edges remove shaping context**, so a ligature under the block cursor or
  straddling a selection edge renders as base glyphs — accepted, documented (§9), matches today.

## 9. Non-goals (this workstream)

| Excluded | Why |
|---|---|
| Bundled HarfBuzz / JNI / any new dependency | User's scope choice; workstream-1 precedent |
| Bidi reordering (`isRtl=true` handling) | Cells are drawn LTR-anchored; kitty defaults the same way; a product decision, not this cycle |
| Cluster-aware cursor/selection restructure (Approach 2) | Deferred by explicit choice; cursor/selection keep splitting runs at cell boundaries |
| Changes to `:terminal-emulator` | `GraphemeClusters`/`WcWidth`/write path are workstream 1's, already correct for this |
| Changes to `:termux-shared` | Nothing in the compatibility surface needs the shaping state |
| New methods on `TerminalSessionClient` / `TerminalViewClient` | Workstream 6's lane; `TerminalView` gets a plain setter instead |
| Vertical-script layout, grapheme-aware cursor movement | Out of the recorded capability targets |
| Rasterized/pixel tests on the host | Impossible on this host (workstream 5 evidence); device checks deferred (§7) |
