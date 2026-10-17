# Terminal engine upgrade — idea capture (NOT an approved spec)

**Date:** 2026-10-14
**Status:** **updated 2026-10-17** — approach **B (substrate first)** chosen; workstream 1 (the cell model) is
designed, with the spec at [`../specs/2026-10-17-terminal-engine-cell-model-design.md`](../specs/2026-10-17-terminal-engine-cell-model-design.md)
(awaiting user review, pre-planning). Workstreams 2–6 remain unspecced. §§1–5 and 7 are the record that led there;
§6's resume steps are now complete for workstream 1.
**Origin:** user question — "what would an upgraded `terminal-emulator`, `terminal-view`
and `termux-shared` look like?"
**Path classification:** architectural (restructures module internals and touches a
published API). Per the brainstorming skill this needs: approach selection → sectioned
design → written spec → written plan. Only the exploration and framing below are done.

---

## 1. Decisions taken by the user

- **Driving goal:** *Performance & rendering* + *New terminal capabilities*. Not a
  general maintainability/Kotlin/packaging refactor for its own sake.
- **Constraint:** *Upstream-compatible.* Keep `minSdk 21`, Java 1.8, and `:termux-shared`'s
  published API and the `TerminalSessionClient` / `TerminalViewClient` extension points
  source-compatible for plugin apps. Changes should be PR-shaped for termux-app; enabling
  refactors must be externally invisible.
- **Capability targets (all four):** kitty graphics protocol; OSC 8 hyperlinks +
  underline styles; ligatures & grapheme shaping; more VT/xterm conformance.
- **Performance driver:** *no concrete symptom yet* — the goal is architectural headroom so
  the new features render cheaply. Not chasing a regression.

## 2. Grounded findings (verified in code, so this file is self-contained)

Sizes: `:terminal-emulator` 8 019 LOC (incl. one JNI file), `:terminal-view` 2 880 LOC,
`:termux-shared` 26 855 LOC. All Java 1.8.

`terminal-emulator`:
- `TerminalEmulator.java` is **3 569 lines** — escape parser + screen mutation + DECSET
  flags + colors + sixel/iTerm state in one class with inner classes.
- `TerminalSession.java` owns the pty and spawns three raw threads
  (`TermSessionInputReader`, `TermSessionOutputWriter`, `TermSessionWaiter`) that marshal
  I/O to the main thread through a `Handler`. Android types leak into the "core":
  `android.os.Handler`, `android.system.Os`, `ErrnoException`. Consequence: the core is
  only testable via `unitTests.returnDefaultValues = true`.
- `TerminalSessionClient` is a fat callback interface that also carries seven `log*` methods.

`terminal-view`:
- `TerminalView.java` is **1 520 lines** (`extends View`) doing input, gestures, selection
  and drawing; `TerminalRenderer.java` (276) draws the visible rows into a software
  `Canvas` in `onDraw`. **No dirty tracking / no hardware surface / no glyph shaping.**
- `TerminalViewClient` duplicates the same seven `log*` methods as `TerminalSessionClient`
  — two parallel, logging-aware client interfaces.

`termux-shared`:
- Grab-bag: shell/command running, local sockets, file/filesystem, package/permission
  utils, `TermuxConstants`, plugin utils, preferences — **and UI** (`ExtraKeysView`,
  `ReportActivity`, `TextIOActivity`).
- `implementation project(":terminal-view")` — the published shared library drags the whole
  terminal UI + emulator upward. Published to JitPack; consumed by plugin apps; API frozen.
- **7 files import `com.termux.terminal` / `com.termux.view`:**
  `shell/ShellUtils.java` (uses `TerminalBuffer`, `TerminalEmulator`, `TerminalSession`),
  `shell/command/ExecutionCommand.java` (`TerminalSession`),
  `termux/settings/properties/TermuxPropertyConstants.java` (`TerminalEmulator`,
  `TerminalView`), `termux/shell/command/runner/terminal/TermuxSession.java`
  (`TerminalSession`), `termux/terminal/TermuxTerminalSessionClientBase.java`
  (`TerminalSessionClient`), `termux/terminal/TermuxTerminalViewClientBase.java`
  (`TerminalViewClient`), `termux/terminal/io/TerminalExtraKeys.java` (`TerminalView`).

What already exists (do not re-pitch as new):
- **True color (24-bit)** — `TerminalEmulator` handles `38/48/58` extended color (`:2529`).
- **Sixel** — `TerminalSixel.java`, DCS `q` path (`TerminalEmulator` `:328`).
- **iTerm2 inline images** — `ITermImage.java` (`OSC 1337`), plus `TerminalBitmap.java`.

What is missing:
- **Kitty graphics** — `APC` (`ESC _`) is "only parsed, but ignored" (`TerminalEmulator`
  `:163`); the `ESC_APC` / `ESC_APC__ESC` states exist but process nothing.
- **OSC 8 hyperlinks** — no hyperlink handling found anywhere.
- **Rich underline styles** (double/curly/dotted/dashed) — not modeled.
- **Dirty-region tracking** — `TerminalBuffer` has no dirty/change state; `onDraw` redraws
  all visible rows each `invalidate()`.
- **Glyph shaping / ligatures / grapheme clusters** — per-cell draw via
  `drawText`/`drawTextRun`; `WcWidth` handles width, not clusters.

## 3. The `:termux-shared` compatibility crux

This is why the upgrade can't just be "rewrite the modules":

- `ShellUtils` reaches into `TerminalBuffer` / `TerminalEmulator` for transcript dumping, so
  the terminal's internal grid model is **effectively part of termux-shared's API**.
  Any `TerminalBuffer` restructure must keep those accessors as delegating shims.
- `TermuxTerminalSessionClientBase` / `TermuxTerminalViewClientBase` are the published base
  classes plugins extend. New client callbacks (e.g. `onLinkClicked`, image hooks) must be
  added as **Java 8 `default` no-op methods** on `TerminalSessionClient` /
  `TerminalViewClient` (desugared on minSdk 21) so existing implementors still compile, then
  wired through the `*Base` classes.

## 4. Scope: this is a program, not one spec

| # | Workstream | Depends on |
|---|---|---|
| 1 | Cell model: rich attributes, grapheme clusters, link id, dirty-row tracking | — |
| 2 | Rendering: frame snapshot + dirty-region + image layer | 1 |
| 3 | Text: run shaping / ligatures | 1, 2 |
| 4 | Graphics: unify sixel + iTerm2 + kitty as one placement layer | 1, 2 |
| 5 | Conformance + OSC 8 + underline styles | 1, 2 |
| 6 | `:termux-shared` compat shims (threaded through all of the above) | — |

Recommendation: spec **workstream 1 first**, then 2, then 3–5 as separate specs/plans. One
combined spec would be un-landable upstream.

## 5. Candidate approaches (open decision — none chosen)

- **A — Additive side tables.** Keep `TerminalEmulator` as-is; bolt on a hyperlink map,
  image list, cluster table, dirty bitset. Smallest PRs, lowest risk, but the god class keeps
  growing and side tables drift from cells; no real headroom; shaping still fights per-cell
  draw.
- **B — Substrate first (recommended).** Evolve `TerminalBuffer` into the real cell model
  (underline style, link id, grapheme cluster, dirty rows, one image-placement layer). Keep
  the parser where it is (do **not** split it yet — YAGNI). Then add a frame-snapshot /
  dirty-region renderer in `:terminal-view`. Then land 3–5 as small consumers. Real headroom;
  every later feature becomes small. Cost: phase 1 is the biggest chunk and must preserve the
  `TerminalBuffer` and client-class surface for `:termux-shared` / plugins.
- **C — Renderer retrofit only.** Add dirty regions + a frame abstraction now, defer all
  cell-model work. Immediate low-risk headroom, but shaping, graphemes and hyperlink
  hit-testing still need the model later, so the substrate is paid for anyway with less
  leverage.

**Recommendation: B**, because all four chosen capabilities need the same substrate
(attributes, clusters, link ids, dirty tracking). C buys only half the headroom.

## 6. Open decision / how to resume

1. Ask the user to pick approach **A / B / C** (B recommended).
2. Then present the design in sections, scaled to complexity, getting approval after each:
   architecture, components, data flow, error handling, testing.
3. Then write the spec to `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md`, self-review,
   and get the user to review it.
4. Then invoke the `writing-plans` skill to produce the implementation plan. (Per the
   brainstorming skill, `writing-plans` is the only skill to invoke after brainstorming.)
5. First spec to write: **workstream 1 (cell model)**.

## 7. Risks / things to verify during design

- Upstream appetite for a `TerminalBuffer` restructure — it may need to land as additive
  accessors first, then internal migration, to stay PR-reviewable.
- Java 8 `default` methods in the client interfaces: confirm desugaring covers the plugin
  apps' toolchains, not just this repo.
- `:termux-shared` UI (`ExtraKeysView`, `ReportActivity`, `TextIOActivity`) dragging
  `:terminal-view` upward — out of scope here (packaging is a separate concern) but noted;
  don't regress it.
- Graphics: unify decode paths (sixel / iTerm2 / kitty) without breaking the existing
  `TerminalSixel` / `ITermImage` behavior that ships today.
