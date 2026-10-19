# Repo audit — what is left to do

**Date:** 2026-10-18
**Status:** capture only. This is a snapshot of the repository as it stands, not a plan, a
commitment, or an authorization to start anything. Where it disagrees with
[`2026-10-16-outstanding-work.md`](2026-10-16-outstanding-work.md), the ledger still owns the
decisions; this file owns the *state of the code and docs*.
**Method:** full read-only sweep of `docs/**` (every plan, spec and idea note) plus `TODO`/`FIXME`/
stub/error-swallowing/test-coverage/debt sweeps over all four modules. Findings marked **[verified]**
were re-checked by hand at the cited line; the rest come from the sweep and carry its file:line.
**Related:** `2026-10-16-outstanding-work.md` (ledger), `2026-10-14-terminal-engine-upgrade-idea.md`
(program), `ideas.md` (manual checklists).

---

## 1. Code gaps

### 1.1 WS4 kitty graphics is ack-only — it never draws an image **[verified]** — highest priority

WS4 is recorded as implemented (`docs/specs/2026-10-18-ws4-graphics-placement-design.md:4`), but the
only kitty code that landed is the parser plus a reply. Direct inspection:

- `terminal-emulator/.../TerminalEmulator.java:2661` `doApc()` parses with
  `KittyGraphicsParser.parse(content, null)` (payload dropped) and only writes a reply, then
  `// Otherwise ignore`. No decode, no store, no placement, no draw call.
- `terminal-emulator/.../TerminalBuffer.java:835` `deleteTerminalBitmaps(...)` has **no call site
  anywhere in the repo** — the `d=a|A|i|c|C` deletion path is unreachable dead code.
- `terminal-emulator/.../TerminalBuffer.java:975` `addTerminalBitmapForImage(...)` is called only by
  the iTerm2 path (`TerminalEmulator.java:3046`); kitty never calls it.
- Chunk accumulation (`m=1` … `m=0`) is not implemented; each APC chunk is processed standalone.
- `terminal-emulator/.../kitty/KittyGraphicsCommand.java:12-27` parses width/height/format/
  offsets/columns/rows/source rect/cursor policy/delete spec/payload, and nothing consumes them.

Consequences: a kitty-speaking program receives `OK` and sees nothing; the §8 device check
"kitty graphics render with `kitten icat`" (`docs/specs/2026-10-18-ws4-graphics-placement-design.md:168`)
cannot pass; sixel/iTerm2 rendering is unaffected. The plan's Task 4 (decode seam, `a=t` store,
`a=T` place, retransmit/delete) was never executed — only its `aa07194f` "Wire APC … with ack" step
landed.

**Two honest ways out:** implement the missing decode/store/place/delete, or correct the WS4 spec and
plan to "ack-only subset, no rendering" and drop/restate the rendering device check. Leaving the docs
as they are is the one option that is not honest.

### 1.2 Focus events are stored but never emitted **[verified, upstream-inherited]**

`TerminalEmulator.java:207` defines `DECSET_BIT_SEND_FOCUS_EVENTS` (DECSET 1004) and `:495` maps it,
but no code ever emits focus-in/focus-out sequences. Programs that ask for focus reporting get
nothing and cannot tell. Documented in-tree as "DECSET 1004 - NOT implemented" (`:206`).

### 1.3 Escape-sequence errors are silently swallowed **[verified, upstream-inherited]**

`TerminalEmulator.java:37` `LOG_ESCAPE_SEQUENCES = false` makes `logError(...)` a no-op, so
`unknownSequence` / `unimplementedSequence` (`:3303`, `:3313`) produce no output in release builds.
This is what let §1.1 go unnoticed: the terminal accepts a protocol it does not implement, silently.

### 1.4 Palette OSC in wallpaper/custom-image mode never repaints

`app/.../terminal/TermuxTerminalSessionActivityClient.java:559` early-returns from
`updateBackgroundColor()` when a wallpaper or custom image is in use, so OSC 4/10/11/12 palette
changes made with no accompanying text output stay invisible. Known WS2 limitation
(`docs/specs/2026-10-17-renderer-dirty-region-design.md:77-82`); the robust fix (a palette version
signal) needs its own spec cycle.

### 1.5 Inherited markers worth a decision, not necessarily a fix

`ITermImage.java:273` (name/size keys unsupported), `TerminalEmulator.java:3062` (iTerm2 file-saving
to Downloads dropped), `:1837` (reverse wrap-around TODO), `:2299-2302` (ECH clears attributes?),
`TerminalRow.java:296` (unassigned characters get width 0), `:3497` (cursor field threading), plus a
long tail of `// Ignore.` branches on purpose-skipped controls. All predate the franchise work.

---

## 2. Test gaps

- **`:termux-shared` has no `src/test` at all** — only `androidTest/.../ExampleInstrumentedTest.java`
  boilerplate, and `testImplementation junit` declared but unused. Worse,
  `termux-shared/src/main/java/com/termux/shared/file/tests/FileUtilsTests.java` (18 KB of assertions)
  ships in the **main** source set and is invoked from app startup.
- **WS4's own named tests do not exist** — `TerminalEmulatorKittyTest` and
  `TerminalBufferPlacementTest` were named in
  `docs/plans/2026-10-18-ws4-graphics-placement-plan.md:78,44`. Nothing exercises `doApc`, the
  placement lifecycle, or the (dead) deletion path, which is exactly how §1.1 went unnoticed.
- WS5: the underline-colour array's erase/scroll/reflow plumbing has no direct test (documented,
  `docs/specs/2026-10-17-osc8-hyperlinks-sgr58-underline-design.md:287-291`).
- No test at all for: `:terminal-emulator` `TerminalBitmap`, `TerminalSixel`, `ITermImage`,
  `TerminalSession`, `TerminalColors`; `:terminal-view` `TerminalView` (1661 lines),
  text-selection controllers/handles; `:app` `TermuxService`, `TermuxTerminalSessionActivityClient`,
  `TermuxInstaller`, `RunCommandService`, `TermuxDocumentsProvider`, and every Compose screen.
- Commented-out assertions sitting in live test files: `TerminalRowTest.java:379,396-402,410,414,426,
  438-440`; `TerminalTest.java:75`; `UnicodeInputTest.java:52-55`; `ResizeTest.java:132,134`;
  `ScrollRegionTest.java:68`.
- No `@Ignore`/`@Disabled` anywhere, and no empty test bodies — the suite is honest, it is just
  incomplete.

---

## 3. Device / manual checks still outstanding

| # | Check | Source |
|---|---|---|
| D-1 | WS3 §7: Minikin-default probe, ligature ON/OFF, cursor+selection over ligatures, Arabic drift, perf smoke, default-font regression, API 21–28 legacy path | `docs/specs/2026-10-18-ws3-shaping-ligatures-design.md:221-235` |
| D-2 | WS4 §8: kitty render via `kitten icat`, `a=q` detection, `a=d` deletion, sixel/iTerm2 parity, scroll+GC, chunked images | `docs/specs/2026-10-18-ws4-graphics-placement-design.md:166-173` |
| D-3 | WS2 7-item visual checklist: heavy output, htop-style redraw, scrollback drag, selection during output, 4:1–4:5 underline distinction, font-size/resize/rotation, cursor + reverse video | `docs/specs/2026-10-17-renderer-dirty-region-design.md:205-214` |
| D-4 | WS1 engine sanity on a real emoji/link session | `docs/plans/2026-10-17-terminal-engine-cell-model.md:534-536` |
| D-5 | Appearance presets: Solarized Light, Gruvbox Dark, One Dark readability | `docs/ideas/ideas.md:3-27` |
| D-6 | Appearance screen full checklist (entry points, controls, preview accuracy, Apply-without-restart, Back-discards, font picker, Reset, rotation) | `docs/plans/2026-10-14-in-app-terminal-appearance.md:184-186` |
| D-7 | Settings-IA manual check: `Settings → Termux` opens the three entries, rows correct, persistence across restart | `docs/plans/2026-10-16-settings-ia.md:907-909` |
| D-8 | B1 onboarding edges: cancel/retry after failure, rotation mid-install, back key | `docs/plans/2026-10-10-shell-sessions.md:439` |
| D-9 | A2 multi-window split-pane stability (one manual check recommended) | `docs/plans/2026-10-10-shell-sessions.md:127` |
| D-10 | Termux:Float `canDrawOverlays()` under `sharedUserId` (verify, do not "fix" blind) | `docs/ideas/2026-10-16-termux-float-port-idea.md:123-126` |

WS5's device check passed on 2026-10-18 and is not pending. D-2 is blocked by §1.1. Two items have no
host test by design and are covered only by inspection plus a device run: the WS3 API-29 gate
(`SDK_INT` stubbed to 0) and the WS2/TerminalView overlay protocol.

---

## 4. Unspecced or undecided work

- **WS6 `:termux-shared` compat shims** — unspecced. Note it was largely *consumed* while WS1–WS5
  landed (new client callbacks shipped as Java 8 `default` methods; `ShellUtils` accessors kept
  working), so what remains is closer to an audit than a build: prove `:termux-shared`'s public API
  stayed backward compatible and that the delegating shims are real.
- **xterm-conformance remainder** — deliberately parked with WS5; unspecced.
- **Session durability / restore** — needs its own cycle (snapshot + opt-in respawn).
- **Session "Direction B"** — ergonomics and restore follow-ups recorded, not designed.
- **Termux:Float** — shape A (`:float` module) vs shape B (fold into `:app`); decision still "neither",
  with build-plumbing gotchas inventoried.
- **256-colour palette** in the appearance screen — undecided (`docs/ideas/ideas.md:59-62`).
- **Palette version signal** for §1.4 — needs a spec cycle if the robust fix is chosen.

---

## 5. Doc hygiene — mechanical, one commit

Status lines that contradict shipped code:

- `docs/specs/2026-10-10-shell-and-sessions-ux-design.md:5` — "awaiting user review (pre-planning)"
  (A–C shipped).
- `docs/specs/2026-10-16-settings-ia-design.md:12` — same, though the rework shipped (`de9ed98b`..`9077b5f6`).
- `docs/specs/2026-10-17-renderer-dirty-region-design.md:19` — "written, not yet approved" though
  implemented (`e8e165a8`..`67ec4de3`).
- `docs/plans/2026-10-17-terminal-engine-cell-model.md:26` — "awaiting user review (pre-execution)"
  though WS2/WS3/WS5 all consume its storage.

Plans whose task lists are entirely unchecked although the work shipped: `2026-10-16-settings-ia.md`,
`2026-10-17-terminal-engine-cell-model.md`, `2026-10-17-renderer-dirty-region.md`,
`2026-10-18-ws3-shaping-ligatures.md`, and `2026-10-10-shell-sessions.md:47-91` (A1).

Also: the ledger's WS1 line (`2026-10-16-outstanding-work.md:96-98`) still reads "pre-planning
review"; the appearance plan points at a gitignored `docs/superpowers/` path
(`docs/plans/2026-10-14-in-app-terminal-appearance.md:29`); `docs/en/index.md` is a stub.

---

## 6. Build and dependency debt

- `app/build.gradle:92-94` — a keystore password in plaintext next to the committed
  `testkey_untrusted.jks` (upstream inheritance; debug-only, still unappealing).
- Guava pinned at `24.1-jre` (2017) in `:app` and `:termux-shared`; commons-io pinned at `2.5` on
  purpose for Android < 8; a `listenablefuture` conflict shim.
- `-dontobfuscate` in both proguard rule files makes release `minifyEnabled true` mostly inert.
- `targetSdkVersion=28` while `compileSdkVersion=36`.
- `:app`'s `javaCompile` depends on downloading ~115 MB of bootstrap zips; `./gradlew clean` deletes
  them (documented in `AGENTS.md`).
- `terminal-view/build.gradle:16` still names the pre-AndroidX
  `android.support.test.runner.AndroidJUnitRunner`.
- No lint baseline; suppressions are ad hoc, with `SharedPreferenceUtils` carrying eight
  `@SuppressLint("ApplySharedPref")` (synchronous `commit()` hidden on the main path).

---

## 7. Suggested order

1. **Resolve §1.1** — implement the kitty decode/store/place/delete path, or re-scope WS4's spec and
   plan to "ack-only". It is the only place where the recorded status overstates the code, and it
   blocks device check D-2.
2. **Doc hygiene (§5)** — one mechanical commit; removes four false "awaiting review" statuses and
   five all-unchecked plans.
3. **Device batch (§3)** — WS3, WS4 (after step 1), WS2, the appearance presets and the
   settings-IA check, whenever a phone is available.
4. **WS6 audit** — cheapest of the unspecced epics, and it doubles as a compatibility regression
   check on the published `:termux-shared` surface.
5. Then pick one of the remaining epics (§4) and run it through the normal spec → plan cycle.
