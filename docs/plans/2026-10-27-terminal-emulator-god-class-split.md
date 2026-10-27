# TerminalEmulator god-class split — plan

**Date:** 2026-10-27
**Scope:** behavior-preserving decomposition of `TerminalEmulator` (3,876 lines), the largest god class in the engine program.
**Constraint:** the published `:termux-shared` surface reaches into `TerminalEmulator`/`TerminalBuffer` for transcript dumping (`ShellUtils` → `getEmulator().getScreen().getTranscriptText*`). Those accessors are untouched here; the split only relocates private escape-state handling.

## The seams

`TerminalEmulator` mixes escape-state dispatch with three self-contained *graphics* subsystems, each already partially decomposed:

| Subsystem | In-band trigger | Already extracted | Remaining in `TerminalEmulator` |
|---|---|---|---|
| Sixel | DCS `q` / `P1;P2;P3;q` | `TerminalSixel` (bitmap/state), `TerminalBuffer.sixel*` | `processSixelDcs` (parser), `getSixelDcsSetupArgs`, `startIfSixelDcs` |
| Kitty graphics | APC `_G` | `com.termux.terminal.kitty.*`, `KittyGraphicsTransfer` | `doApc` + `doKitty*` + `handleKittyGraphicsCommand` |
| iTerm2 / OSC 8 | OSC 1337 / OSC 8 | `ITermImage` | the inline `File=`/`MultipartFile=` block in `doOsc` |

## Extraction order (ascending risk)

1. **`processSixelDcs` → `SixelParser.process`** — pure DCS-data parser; talks only to `TerminalBuffer.sixel*` (null-safe) and the logger. Low risk, no state-machine side effects beyond a returned buffer-capacity hint. **done 2026-10-27.**
2. **`getSixelDcsSetupArgs` + `startIfSixelDcs` → `SixelParser`** — has the subtle `ESC_DCS__CHECK_IF_SIXEL` side effect (must stay behavior-identical); do after step 1 settles. **done 2026-10-27** (`parseSetupArgs` returns a `Setup` distinguishing "sixel" from "definitively not sixel"; `startIfSixelDcs` stays in the emulator as DCS-state orchestration).
3. **Kitty APC block → `KittyGraphicsCommandHandler`** — larger; the `doKitty*` methods share the `KittyGraphicsTransfer` and reply path.
4. **OSC 1337 block → `ITermImage`/`OscImageHandler`** — the `File=`/`MultipartFile=` inline block.

## Rules

- Each step is one commit, `:terminal-emulator:test` green before and after.
- Log output is preserved exactly (pass the existing `LOG_TAG`; do not rename the tag mid-split).
- No public API changes to `TerminalBuffer`, `TerminalSixel`, `ITermImage`, or `ShellUtils`.
- New parser code gets its own test class; the parser's validation paths are testable against a `TerminalBuffer` whose sixel state is `null` (all `sixel*` calls no-op), so no native graphics needed.
