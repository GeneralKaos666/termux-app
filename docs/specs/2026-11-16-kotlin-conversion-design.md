# Kotlin conversion design — `:terminal-view` and the remaining `:app` Java

**Date:** 2026-11-16
**Status:** design approved 2026-11-16. Implementation plan not yet written.
**Scope:** `terminal-view/src/main` (18 files, 4,212 LOC) and `app/src/main` (31 Java files,
7,987 LOC). No change to `:terminal-emulator`, `:termux-shared` or any companion app.
**Origin:** the 2026-11-16 assessment of a whole-repo Kotlin conversion. The assessed full scope
(all four modules plus the five plugin repos, ~48,800 Java LOC) was rejected in favour of this
subset.

---

## 1. Context

`app/src/main` is already half Kotlin: 82 Kotlin files (8,128 LOC) against 31 Java files (7,987
LOC), with the Compose subtree under `app/src/main/java/com/termux/app/ui/` fully converted. What
remains Java is the legacy layer — `TermuxActivity` (1,715 lines), `TermuxService` (961), the
settings `PreferenceFragment` stack (12 files), the terminal clients, and the receivers/filepicker
tail.

`terminal-view` is 100% Java: 18 files, dominated by `TerminalView` (1,824 lines) and
`TerminalRenderer` (647). It is a **published artifact** (`com.termux:terminal-view`, version
`0.118.0`, `terminal-view/build.gradle`) and `:termux-shared` consumes it through an `api`
dependency, so its Java-visible surface is a contract, not an implementation detail.

`:terminal-emulator` (15,468 LOC) stays Java: its `TerminalEmulator` hot loop offers no benefit from
conversion and carries real regression risk. `:termux-shared` (27,518 LOC) stays Java: it is
published to JitPack and consumed by all five companion apps, and it holds 1,349 `public static`
declarations whose Kotlin equivalents need `@JvmStatic`/`@JvmField` to stay reachable from Java.

## 2. Goal and success criteria

Convert the two modules to Kotlin with no behavior change and no change to the Java-visible API of
`:terminal-view`.

Success:

1. `./gradlew test` passes.
2. `./gradlew assembleDebug` produces an installable APK.
3. Zero `.java` files remain under `terminal-view/src/main` and `app/src/main`.
4. `:termux-shared` and `:app` still compile against `:terminal-view` unchanged — the compatibility
   proof is a successful Java compile, not inspection.
5. `:terminal-view` stays publishable: the `release` Maven publication and its sources jar still
   build and carry real sources.

Non-goals: behavior changes, new features, performance tuning, test conversion, renaming or
repackaging anything, touching `:terminal-emulator`, `:termux-shared` or the plugin repos.

## 3. Decision: partial conversion

Three options were weighed on 2026-11-16.

| Option | Cost | Outcome |
|---|---|---|
| Full conversion, all modules + plugins | ~10–13 weeks | Uniform language; no feature payoff |
| **This design** | **~2–3 weeks** | Kotlin everywhere new work happens; published API and hot loop untouched |
| New code in Kotlin only | none | Mixed codebase indefinitely, which the repo already tolerates |

Chosen: the middle path. The two modules it covers are where the fork's active work lands, and
neither carries the published-API hazard that makes `:termux-shared` the wrong place to start.

## 4. Build wiring

### 4.1 Keep the `src/main/java` layout

Kotlin already compiles from `app/src/main/java/` in this repo — AGP 9.1.1's built-in Kotlin
handles `.kt` in that source directory. Keeping the layout means:

- `terminal-view/build.gradle`'s `sourceJar` task
  (`from android.sourceSets.main.java.srcDirs`) keeps publishing real sources. Moving files to
  `src/main/kotlin` would silently ship an empty sources jar.
- No source-set configuration is added to either module.
- File paths in review diffs stay comparable to today's.

The only build change for `terminal-view` is removing `enableKotlin = false`
(`terminal-view/build.gradle:6`).

### 4.2 Emit real JVM default methods

`TerminalViewClient` declares one default method, `onLinkClicked(String)`
(`terminal-view/src/main/java/com/termux/view/TerminalViewClient.java:37`), and
`TermuxTerminalViewClientBase` (Java, `:termux-shared`) implements the interface without overriding
it. A Kotlin interface body compiles to `DefaultImpls` by default, which Java implementors cannot
inherit; that class would stop compiling.

`terminal-view/build.gradle` gains:

```gradle
kotlin {
    compilerOptions {
        jvmDefault = JvmDefaultMode.ALL_COMPATIBILITY
    }
}
```

`all-compatibility` emits real JVM default methods *and* retains `DefaultImpls` for consumers of the
already-published AAR. Both source and binary compatibility are preserved.

### 4.3 Static members

`terminal-view` has 7 non-private statics: `TerminalView`'s two cursor-blink-rate bounds,
`LinkUris.sanitize`, `HandleTint.handleTint`, `PopupWindowCompatGingerbread`'s two methods, and the
`TextSelectionHandleView` handle constants. Each becomes `const val` in a `companion object` or
`@JvmStatic` inside an `object`, so Java callers in `:termux-shared` and `:app` resolve the identical
symbols.

## 5. Translation ruleset

Fixed before the first file, applied without deviation.

| Java | Kotlin |
|---|---|
| `@Nullable` / `@NonNull` reference | `T?` / `T`, transcribed from the annotation |
| unannotated reference type | `T` when the body has no null path and no in-repo caller passes null; `T?` where it returns or accepts null |
| `getX()`/`setX()` on a class with Java callers | kept as `fun getX()` / `fun setX()`; no property rewrite |
| `public static final int X` | `const val X` in `companion object` |
| static utility method | `object` + `@JvmStatic` |
| `internal` | never on anything Java calls — Kotlin mangles those names |
| `final` parameter or local | `val` |
| `switch` | `when` |
| anonymous class with fields or `this` use | `object : X() {}` |
| anonymous class with neither | lambda |
| default parameter on public API | not allowed; keep the explicit Java overloads |
| `int[]` / `Integer` | `IntArray` / no boxing; hot paths never see `Int?` |
| non-static inner class | `inner class` |

Two rules carry the weight: nullability is never guessed — an annotation wins, and otherwise the Java
body decides, because only two annotations exist in the whole module — and no public method signature
changes shape. A `!!` in converted code is a defect, not a fix.

## 6. Sequencing

`:terminal-view` lands before `:app`, because both `:app` and `:termux-shared` sit on top of it. The
risky seam is proven in a one-file batch before any bulk work.

**`:terminal-view`**

- **Batch 0** — build wiring (4.1–4.2) plus `TerminalViewClient` alone. Proves the
  `all-compatibility` interop against `TermuxTerminalViewClientBase` in a single file.
- **Batch 1** — leaf classes with no Java consumers: `LinkUris`, `KeyCodePoints`,
  `RowInvalidation`, `RenderBand`, `RunAdvancesSource`, `ShapedRunPolicy`, `TerminalGeometry`,
  `CursorFx`, `ImeTextEncoder`, `HandleTint`, `PopupWindowCompatGingerbread`, `support/`.
- **Batch 2** — `textselection/`: `CursorController`, `TextSelectionCursorController`,
  `TextSelectionHandleView`.
- **Batch 3** — `GestureAndScaleRecognizer`, `TerminalRenderer`.
- **Batch 4** — `TerminalView` (1,824 lines, most Java callers) last.

**`:app`**, after `:terminal-view` merges:

- **Batch 5** — `terminal/` (7 files), `TermuxActivity`, `TermuxService`.
- **Batch 6** — `fragments/settings/` (12 files).
- **Batch 7** — the tail: `RunCommandService`, `TermuxApplication`, `TermuxInstaller`,
  `TermuxOpenReceiver`, `SystemEventReceiver`, `HelpActivity`, `SettingsActivity`,
  `FileReceiverActivity`, `UserAction`, `TermuxDocumentsProvider`.

Each batch is one revertable commit.

## 7. Verification

Per `:terminal-view` batch:

```sh
./gradlew :terminal-view:test
./gradlew :termux-shared:compileDebugJavaWithJavac
./gradlew :app:compileDebugKotlin
```

The last two are the compatibility gate: they fail if a converted Kotlin symbol no longer binds from
Java or from another module. `./gradlew assembleDebug` closes the module.

Per `:app` batch: `./gradlew :app:testDebugUnitTest` (the 2–3 minute gate used for `:app` work) and
`assembleDebug`. Final gate: `./gradlew test`.

Existing tests stay Java (§ non-goals) and act as the behavioral oracle; no assertions change.

Operational rule: never run `./gradlew clean` — its block deletes the cached
`app/src/main/cpp/bootstrap-*.zip` files and forces a ~115 MB re-download over network.

## 8. Risks

| Risk | Mitigation |
|---|---|
| Nullability transcribed wrongly, producing a runtime NPE no test reaches | Annotation-driven only; `TerminalViewClient`'s callbacks are hand-checked against every `:app` call site |
| Lost interface default breaks `TermuxTerminalViewClientBase` | Batch 0 proves it in isolation before bulk work |
| Allocations or closure captures introduced in `TerminalRenderer`'s per-frame paths | Structure preserved in `RenderBand`, `RunAdvancesSource`, `ShapedRunPolicy`; no lambdas or boxing inside draw paths |
| Text-selection behavior regresses with no unit coverage | Cursor/handle classes get a device pass, recorded in `docs/ledger/manual-checks.md` |
| Publication output changes shape | `release` publication and `sourceJar` re-run as part of batch 4 sign-off |

## 9. Rollback

Each batch is a single commit over a green tree, so any batch reverts independently. A full revert of
the work is a revert of batches 0–7 in reverse order; nothing in `:terminal-emulator` or
`:termux-shared` moves, so no cross-module state has to be unwound.

## 10. Out of scope, recorded

- `:terminal-emulator` (15,468 LOC) — hot loop; conversion offers no benefit against regression risk.
- `:termux-shared` (27,518 LOC) — published JitPack artifact with 1,349 public statics and five
  companion-app consumers; the highest-risk module in the repo.
- The five plugin repos (Termux:API, :X11, :Widget, :Boot, :Styling) — separate repositories, each
  needing its own build and release cycle, sequenced strictly after any `:termux-shared` change.
