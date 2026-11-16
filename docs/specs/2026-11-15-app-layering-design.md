# App layering design — moving logic out of `TermuxActivity` and `TermuxService`

**Date:** 2026-11-15
**Status:** design approved 2026-11-15. Implementation plan not yet written.
**Scope:** `:app` only. No change to `:termux-shared`, `:terminal-view` or `:terminal-emulator`.
**Origin:** the 2026-11-15 clean-architecture audit of `:app` (findings F1–F7 recorded in
`docs/ledger/outstanding-work.md`).

---

## 1. Context

The Compose subtree under `app/src/main/java/com/termux/app/ui/` is already layered by convention
and unit-tested: it imports no `:app` class outside its own package plus `ShellHostCallbacks`, it
reaches the OS through injected seams (`FloatSessionSource` at
`ui/floating/FloatController.kt:28`, `AppearancePreferences` at `ui/appearance/AppearanceStore.kt:15`),
and its pure logic carries no `android.*` imports (`SessionReorder`, `SessionItem`, `KeySet`,
`KeySetPresets`, `FloatWindowGeometry`, `AppearanceState`, `ShellChromeState`), each with a JVM test
under `app/src/test/java/com/termux/app/ui/`.

The debt sits in the legacy Java layer:

| Finding | Problem | Evidence |
|---|---|---|
| F1 | No presentation-state layer. Zero `ViewModel`/`StateFlow`; `lifecycle-viewmodel` is not even a dependency (only `lifecycle-runtime-compose`, `app/build.gradle:44`). State holders are plain classes mutated by a Java Activity. | `ui/sessions/SessionsState.kt:16` |
| F2 | `TermuxActivity` (1593 lines) is composition root, presentation logic and data loader at once. | `refreshSessionsBar` 801–838; `downscaleForBlur` 978; `loadSystemWallpaperBitmap` 1015; `loadCustomImageBitmap` 1082 |
| F3 | `TermuxService` (1057 lines) holds five responsibilities: Intent dispatch, command→session creation, foreground notification, wake lock, float window. | 140; 562/678/751; 868/936; 336; 250/655 |
| F4 | Preferences are the de-facto data layer with no seam: 22 files read `SharedPreferences`/`TermuxPreferenceConstants` directly, and `ui/floating/FloatController.kt:19` imports `TermuxAppSharedPreferences`. | `FloatController.kt:15,82` |
| F5 | `outstanding-work.md:414` claims the remaining `:app` classes hold "no JVM-testable pure logic". That is false. | see §10 |
| F6 | Two settings stacks: legacy `PreferenceFragment` Java (13 files) and Compose `ui/appearance`. | `fragments/settings/**` |
| F7 | `:termux-shared` depends on `:terminal-view`, and 7 files import terminal internals — inverted relative to a clean layering. | recorded constraint |

## 2. Goal and success criteria

Extract logic from the Activity and the Service so it becomes unit-testable, without changing
anything a companion app resolves.

Success:

1. `./gradlew test` and `./gradlew :app:assembleDebug` pass.
2. Every extraction is behavior-preserving and covered by a test that fails if the behavior changes.
3. A frozen-surface check (§4) proves the plugin-facing surface is byte-identical.
4. `outstanding-work.md` no longer carries the false claim at line 414, and F6/F7 are recorded as
   non-goals with their reasons.

## 3. Non-goals

- **No new Gradle module.** Recorded twice already (`outstanding-work.md:710`, `:796`) and the
  engine program's driving goal is explicitly "not a general maintainability, Kotlin or packaging
  refactor" (`:138`). Layers stay as packages inside `:app`.
- **No ViewModel / MVVM rewrite.** See §9.
- **No dependency-injection container.** No Hilt, no Koin. There is nothing to inject into.
- **No settings consolidation (F6).** That is its own spec cycle; the direction is recorded instead.
- **No `:termux-shared` → `:terminal-view` inversion fix (F7).** Published API, recorded non-goal.
- **No session-durability work.** Unrelated; tracked separately in §1.1 of the ledger.

## 4. Frozen surfaces

These must not change. The freeze check in §11 asserts it mechanically.

| Surface | Why | Where |
|---|---|---|
| `TERMUX_SERVICE.ACTION_*` / `EXTRA_*`, `TERMUX_ACTIVITY.EXTRA_*` names and values | Termux:Tasker, Termux:Widget, `RUN_COMMAND` and `termux-am` drive these | `TermuxService.java:154–170`, `:401–438` |
| `TermuxConstants` paths, package names, `TERMUX_PACKAGE_NAME` | companion apps and the `termux-api` / `termux-x11` binaries resolve against it | `AGENTS.md` |
| `termux.properties` keys | Termux:Styling and Termux:X11 read them | parsed in `:termux-shared` |
| `:termux-shared` public API | published to JitPack and consumed by the plugin apps | `AGENTS.md` |
| Manifest component names, `sharedUserId` | companion-app interoperability | `AGENTS.md` |
| `TermuxService` public methods and `LocalBinder` | binder shape; no `termux-shared` class binds to it today, so it is cheap to keep | `TermuxService.java:205` |

Note that `:app` is not published — only `:termux-shared`, `:terminal-view` and
`:terminal-emulator` are. Package moves inside `:app` are therefore invisible to plugins.

## 5. Target shape

```
com.termux.app.domain.*   pure Kotlin — no android.*, androidx.*, ui.*, data.*
com.termux.app.data.*     SharedPreferences, File, Bitmap behind interfaces
com.termux.app.ui.*       Compose + plain state holders
com.termux.app.*          composition: Activity, Service, receivers
```

### The rule

A JVM test, `LayeringTest`, scans `app/src/main/java` and fails on a forbidden import:

| Package | May not import |
|---|---|
| `domain` | `android.`, `androidx.`, `com.termux.app.ui.`, `com.termux.app.data.` |
| `data` | `androidx.compose.`, `com.termux.app.ui.` |
| `ui` | `com.termux.app.TermuxActivity`, `TermuxService`, `TermuxApplication`, `TermuxInstaller`; and `getSharedPreferences(`, `TermuxAppSharedPreferences`, `TermuxPreferenceConstants` outside an `*Activity` composition root |

Exceptions carry an explicit allowlist entry with a comment explaining why, so an exception is a
decision rather than an omission. The test runs inside the existing `./gradlew test` gate: no new
Gradle task, no CI change.

State holders stay in `ui` (they are Compose-observed and belong with their screens); models and
algorithms move to `domain`. That is the dividing line, not "Kotlin file".

### The move map

| From `ui/` | To | What |
|---|---|---|
| `sessions/SessionItem.kt`, `sessions/SessionReorder.kt` | `domain/sessions/` | item model, reorder maths |
| `keys/KeySet.kt`, `keys/KeySetPresets.kt` | `domain/keys/` | key-set model and presets |
| `floating/FloatWindowGeometry.kt` | `domain/floating/` | geometry maths |
| `appearance/ColorSchemes.kt` | `domain/appearance/` | colour presets |
| `appearance/AppearanceState.kt` (model half: `CursorStyle`, `CursorBlink`, `TerminalColorSet`, `TerminalAppearance`) | `domain/appearance/AppearanceModel.kt` | appearance model |
| `appearance/AppearanceStore.kt` (`AppearancePreferences`, `AppearancePreferenceKeys`, `FontError`, `FontValidator`, `ColorPropertiesDocument`, hex helpers) | `domain/appearance/` | pure file/validation logic |
| `appearance/AppearanceStore.kt` (`AppearanceStore` itself) | `data/appearance/` | reads/writes `colors.properties` and `font.ttf` |
| `appearance/AppearanceActivity.kt` (`AndroidAppearancePreferences`, line 156) | `data/appearance/` | the `SharedPreferences` implementation |
| `appearance/AppearanceStyling.kt`, `appearance/CursorPreferences.kt` | `data/appearance/` | preference read side |
| `floating/FloatWindowPrefs.kt` (`FloatWindowPrefs`, `FloatWindowState`) | `data/floating/` | `SharedPreferences` persistence |

Stays in `ui/`: every state holder (`AppearanceState`, `SessionsState`, `ShellChromeState`,
`ExtraKeysState`, `FirstRunState`, `SnackbarState`, `TerminalBackgroundState`), every `@Composable`,
and the two `*Activity` composition roots.

`AppearanceActivity` keeps constructing the data implementations; that is what a composition root
does, and it is the allowlisted exception for the `ui` rule.

Why a test rather than a Gradle verification task: it fails in the same place the other `:app` tests
fail, and it needs no build-file surface.

## 6. F2 — `TermuxActivity`

Domain cannot take `TerminalSession` or `TermuxSession` as parameters: both import `android.*`
(`TerminalSession.java:3–8`, `TermuxSession.java:3–4`). The boundary is therefore a snapshot type,
mapped at the composition layer.

New, in `domain/sessions/`:

- `SessionSnapshot` — `name`, `title`, `running`, `exitStatus`. Nothing else.
- `SessionIdAllocator` — owns the stable-id rule currently inline at `TermuxActivity.java:812–821`:
  a session gets an id on first sight, and an id is never reused after its session closes.
- `SessionItems` — `sessionItems(snapshots, currentIndex, ids): List<SessionItem>`, reusing the
  existing `sessionItem(...)` so `SessionItemMappingTest` keeps covering the per-item mapping.

New, in `data/`:

- `WallpaperSource` — interface plus implementation holding `Context` / `WallpaperManager`. Absorbs
  `loadSystemWallpaperBitmap` (1015), `wallpaperFallbackBitmap` (1043), `drawableToScreenBitmap`
  (1062), `loadCustomImageBitmap` (1082) and `downscaleForBlur` (978). The `Build.VERSION.SDK_INT`
  read inside `downscaleForBlur` becomes an injected `sdkInt` parameter so the fallback path is
  testable on any host. `BackgroundFx.blurDownscale` stays where it is; it is already pure.

The Activity keeps its field and its call sites in `syncTerminalBackground` (923); only the method
bodies move. `refreshSessionsBar` shrinks to: build snapshots from `mTermuxService`, call
`sessionItems`, assign to `mSessionsState`.

## 7. F4 — preferences seam

`FloatController.kt:82` calls `TermuxAppSharedPreferences.build(context)?.fontSize` inside the view
initializer, which is the only reason `ui/floating` imports the persistence type. Replace it with a
`fontSize: () -> Int` constructor parameter supplied by `TermuxService`, which is the composition
root and already builds that preferences object. All other float persistence already goes through
`FloatWindowPrefs`, so no further change is needed there.

This follows the seam `AppearanceStore` already proves works: an interface, a real implementation,
and a fake in the test.

No `:termux-shared` change.

## 8. F3 — `TermuxService`, notification and wake lock only

New, in `data/`:

- `ServiceNotification` — `buildNotification` (868), `setupNotificationChannel` (936),
  `updateNotification` (944). The channel id, channel name, importance and every action
  `PendingIntent` stay byte-identical; only the containing class changes.
- `WakeLockController` — `actionAcquireWakeLock` (336), `actionReleaseWakeLock` (364) and their
  coupling to the notification update.

Untouched by this work: the `ACTION_*` constants (121, 154–170), the `onStartCommand` dispatch,
`actionServiceExecute` (390) and the entire command→session path
(`executeTermuxSessionCommand` 562, `executeTermuxSession` 678, `processShellCreateMode` 751), every
public method, and `LocalBinder`. The plugin execution path is not edited at all.

The float window (`actionToggleFloat` 250, `getFloatController` 655) is also left in place: it is
app-internal, but it is not where the audit found the most risk, and moving it would grow this
change for little gain.

## 9. F1 — decision: no ViewModel layer

`TermuxActivity` already owns plain state holders (`SessionsState`, `ShellChromeState`,
`SnackbarState`) that Compose observes. Introducing `ViewModel` would add a lifecycle dependency and
a construction path for no behavior gain, and the derivation logic that actually hurts is what §6
moves out.

The rule this change records instead: **derivation lives in `domain`; the composition layer only
assigns.** `SessionsState.setSessions` stays public so Java can assign it directly.

## 10. F5, F6, F7

- **F5:** correct `outstanding-work.md:414`. The claim that the remaining `:app` classes hold "no
  JVM-testable pure logic" is false. Concrete counterexamples, all of which become test targets in
  this work or are trivially extractable: `refreshSessionsBar` stable-id allocation,
  `downscaleForBlur`, `processShellCreateMode`, `moveSessionById` index math.
- **F6:** recorded as `[deferred]` with the direction named in the settings-IA plan. Two settings
  stacks is a real cost, but consolidating 13 fragments is its own spec cycle.
- **F7:** recorded as `[non-goal]` with the published-API reason.

## 11. Testing and verification

Per step: `./gradlew :app:testDebugUnitTest`. Final gate: `./gradlew test` and
`./gradlew :app:assembleDebug`.

New tests:

- `LayeringTest` — the import rule of §5. The scanner is a pure function over source text, so the
  test feeds it synthetic violations and asserts each rule fires; that way the test cannot silently
  stop failing when the real tree happens to be clean.
- `SessionItemsTest` — stable id survives a reorder, a closed session's id is never reused, the
  active index resolves, and the fallback title keeps its stable-id-derived number (the behavior
  `SessionItemMappingTest` already pins for the single-item case).
- `WallpaperSourceTest` (Robolectric) — no wallpaper configured, custom image path missing, divisor
  ≤ 1 returns the bitmap unchanged, and the API < 31 downscale path.
- `WakeLockControllerTest` (Robolectric `PowerManager`) — acquire/release toggle the lock and update
  the notification exactly as today.
- `ServiceNotificationTest` (Robolectric) — channel id/name/importance and action count unchanged.

Frozen-surface check, run before each commit:

```sh
git diff --stat -- termux-shared terminal-emulator terminal-view \
    app/src/main/AndroidManifest.xml gradle.properties
```

must be empty.

Device checks are appended to `docs/ledger/manual-checks.md` and run last, per the ledger's own
rule: a `termux-api` call through the companion app, a Termux:Widget shortcut, a Termux:Tasker
action, a Termux:X11 launch, a Termux:Float toggle, the notification actions, and the wake-lock
toggle.

## 12. Sequencing and commits

1. **Docs** — F5 correction, F6/F7 entries. No code.
2. **`LayeringTest` + package moves** — the move map in §5, applied with `git mv` plus import
   updates. Mechanical, behavior-preserving. Lands before 3–5 so the rule gates them.
3. **F2** — `SessionSnapshot`/`SessionIdAllocator`/`SessionItems` and `WallpaperSource`.
4. **F4** — the `FloatController` constructor seam.
5. **F3** — `ServiceNotification`, then `WakeLockController`.

One commit per step, Conventional Commits with this repository's capitalized types, e.g.
`Changed(app): Extract session mapping from TermuxActivity`.

## 13. Risks

| Risk | Mitigation |
|---|---|
| A package move breaks a Java/Compose boundary silently | Step 2 is behavior-preserving only and lands with the full `:app` suite green before any logic moves |
| `LayeringTest` produces false positives and gets allowlisted into uselessness | Each exception needs a comment; the test carries a negative case so it cannot silently stop failing |
| The notification split changes a `PendingIntent` and breaks a notification action | `ServiceNotificationTest` asserts the action count and channel identity; the device check exercises the actions |
| The wake-lock split changes the notification refresh cadence | `WakeLockControllerTest` asserts the lock state and the update call, not the implementation |
| Wallpaper extraction changes the blur fallback on API < 31 | `sdkInt` is injected, so both paths are covered by tests instead of only the host's |

## 14. Open questions

None. The three decisions that needed your call are recorded: no new Gradle module, notification +
wake lock only for F3, F6/F7 as non-goals.
