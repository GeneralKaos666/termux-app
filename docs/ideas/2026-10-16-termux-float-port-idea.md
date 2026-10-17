# Termux:Float port — idea capture (NOT an approved spec)

**Date:** 2026-10-16
**Status:** feasibility probe done, then **deferred by the user** — "neither yet, just log the
findings". Two shapes are recorded in §5 but **neither is chosen**; no design approved, no spec
written, no plan. Resume at §7.
**Origin:** user question — "is there a way to port over termux-float into the repo?", followed by
"why not clone repo into this one?", answered by asking to log rather than proceed.
**Path classification:** architectural (introduces a new subsystem/app to this repo, or rewires
`:app` into a floating-window host). Per the brainstorming skill this needs: approach selection →
sectioned design → written spec → written plan. Only the probe and framing below are done.

**Related:** `ideas/2026-10-16-outstanding-work.md` (§8 root-screen tidy already plans to group the
four plugin sections under `Plugins`; the `termux_float` entry is one of them).

---

## 1. What was probed, and how

Shallow clone of upstream `https://github.com/termux/termux-float` at `master`, commit `75352bd`
("Changed: Bump termux-shared to 8aca6dbbf4"). The probe clone was kept **outside** the repo at
`/data/data/com.termux/files/home/.tmp/opencode/termux-float-probe` and has since been deleted
(it was throwaway) — re-clone if this is picked up. Nothing in this repo was modified by the probe.

## 2. Why a plain `git clone` into this repo does not work

1. **It is a whole separate Gradle build.** Its `settings.gradle` is exactly `include ':app'`, and
   it brings its own root `build.gradle` (AGP **8.7.3**), gradle wrapper, and `gradle.properties`
   (`minSdkVersion=24`, `targetSdkVersion=28`, `compileSdkVersion=35`). A nested project is never
   built by this repo's Gradle, and the nested `.git` becomes an embedded repo / an unregistered
   submodule.
2. **It links published Maven artifacts, not this repo's modules** (`app/build.gradle:75-76`):
   - `com.termux.termux-app:termux-shared:8aca6dbbf4`
   - `com.termux.termux-app:terminal-view:8aca6dbbf4`

   Included as-is, that ships a **second copy of every `com.termux.shared.*` class** alongside
   `:termux-shared`. A real port has to rewrite those two lines to `project(':termux-shared')` /
   `project(':terminal-view')`.
2. **Version skew.** It pins termux-shared `8aca6dbbf4`; this repo's `:termux-shared` is `0.118.0`
   with the fork's own changes.
3. **Toolchain skew.** Float is Java 11 source/target with `coreLibraryDesugaring`; this repo pins
   Java **1.8** and AGP 8.13.2 repo-wide.
4. **Licence is *not* a blocker.** termux-float is GPLv3-only, the same family as this repo.

## 3. What this repo already has (verified in code)

Fork scaffolding for Termux:Float already exists; only the window itself is missing.

- `:app`'s manifest **already declares `SYSTEM_ALERT_WINDOW`** (`app/src/main/AndroidManifest.xml:31`),
   plus `FOREGROUND_SERVICE` (`:29`), `VIBRATE` (`:28`) and `WAKE_LOCK` (`:27`).
2. **Signing already matches.** `:app`'s debug signing config and termux-float's are the *same*
   keystore, alias and passwords (`testkey_untrusted.jks`, alias `alias`), and both manifests use
   `android:sharedUserId="${TERMUX_PACKAGE_NAME}"` → the `com.termux` shared UID already lines up
   for debug builds.
3. **`termux-shared` already carries the entire float contract** (`TermuxConstants.java`):
   `TERMUX_FLOAT_PACKAGE_NAME` (`com.termux.window`, `:394`), `TERMUX_FLOAT_ACTIVITY_NAME` (`:1237`),
   `TERMUX_FLOAT_SERVICE_NAME` (`:1240`), `TERMUX_FLOAT_SERVICE.ACTION_SHOW/HIDE/STOP_SERVICE`
   (`:1248-1254`), the properties paths (`:746-761`), and the notification channel + id `1339`
   (`:872`, `:874`). `TermuxFloatAppSharedPreferences` already exists too.
3. **The session API matches exactly.** `TermuxSession.execute(ctx, executionCommand,
   TerminalSessionClient, TermuxSessionClient, IShellEnvironment, HashMap, boolean)` in this repo
   is the signature termux-float's `createTermuxSession()` already calls.
3. **The float clients' base classes are present**: `TermuxTerminalViewClientBase` and
   `TermuxTerminalSessionClientBase` (both in `:termux-shared`).
3. **The Settings entry already exists**: `root_preferences.xml` has `app:key="termux_float"`,
   hidden by `SettingsActivity.configureTermuxFloatPreference()` unless `com.termux.window` is
   installed. Port the app and this entry starts working with no further wiring.

## 3b. Verified absences (what is genuinely missing here)

- No `TermuxFloatActivity` / `TermuxFloatService` / `TermuxFloatView` / float view client.
- No `WindowManager` overlay usage anywhere in the repo (`TYPE_APPLICATION_OVERLAY` appears only in
  text-selection popup code in `:terminal-view`).
- `settings.gradle` is only `':app', ':termux-shared', ':terminal-emulator', ':terminal-view'`.
- No counterpart to termux-float's `settings/properties/TermuxFloatAppSharedProperties.java`.

## 4. Probe inventory — what would actually move

Java (9 files, ~750 LOC total):

| File | LOC |
|---|---|
| `TermuxFloatView.java` (the overlay: drag, pinch-resize, focus, chrome) | 359 |
| `TermuxFloatViewClient.java` (`TerminalViewClient`; virtual Ctrl/Fn keys, font size) | 263 |
| `TermuxFloatService.java` (foreground service, session + notification) | 255 |
| `TermuxFloatSessionClient.java` (`TerminalSessionClient`; bell, clipboard, colors/font) | 191 |
| `FloatingBubbleManager.java` (minimize-to-bubble) | 149 |
| `TermuxFloatApplication.java` (log config) | 39 |
| `TermuxFloatAppSharedProperties.java` (**no counterpart here**) | 36 |
| `TermuxFloatPermissionActivity.java` (overlay permission prompt) | 34 |
| `TermuxFloatActivity.java` (launcher; starts the service and finishes) | 21 |

Around it: 3 layouts (`activity_main.xml` is just the float chrome — `TerminalView` + two
buttons), ~9 drawables, `values/{dimens,strings,styles}.xml`, 4 notification PNGs, and
`testkey_untrusted.jks`. Manifest adds 2 activities + 1 service.

## 5. Two shapes (open decision — user deferred, none chosen)

- **A — new `:float` module, faithful port.** Add `include ':float'`; move the sources; rewrite the
  two Maven deps to `project(':termux-shared')` / `project(':terminal-view')`; per-module
  `minSdkVersion 24`; carry over `manifestPlaceholders` (`TERMUX_PACKAGE_NAME`, `TERMUX_APP_NAME`,
  `TERMUX_FLOAT_APP_NAME`); keep `applicationId com.termux.window` + `sharedUserId`. Result: a
  plugin-style second APK, exactly as upstream ships it, and the existing Settings entry lights up.
  Cheapest path (order of a day), and still re-syncable with upstream later. Costs: a second APK in
  CI, and a copy to maintain.
- **B — fold the float into `:app` as a first-class floating terminal** over the *existing*
  `TermuxService` sessions. No second APK, no `sharedUserId`, no package-name constraints.
  Better long-term UX fit with this fork's Compose shell host, but real design work: overlay chrome
  alongside `ui/ShellHost.kt`, which session the window shows, IME/insets (see ledger §3 — A3 Step 2
  is still deferred), and it drifts away from upstream so float fixes stop cherry-picking.

**Recommendation recorded at the time:** A if the goal is "have Termux:Float again without
maintaining a separate repo"; B only if floating terminals should become part of *this fork's* UX.

## 6. Gotchas / to verify during design

- **minSdk skew**: float declares 24; `gradle.properties` is repo-wide at 21 → needs a per-module
  override.
- **In-app reuse (shape B) is blocked as-is**: `TermuxFloatView.initFloatView()` calls
  `TermuxFloatAppSharedPreferences.build(getContext(), true)`, which resolves
  `TERMUX_FLOAT_PACKAGE_NAME` and **exits the app** when `applicationId != com.termux.window`
  (the source comments this: "will also fail if … does not equal applicationId"). B must swap that
  for the app's own preferences.
- **Overlay permission under `sharedUserId`**: `TermuxFloatService.initializeFloatView()` catches
  the failure instead of checking `Settings.canDrawOverlays()`, with the comment that
  `canDrawOverlays()` "always returns false, perhaps due to sharedUserId". Do not "fix" this
  blindly — verify on device.
- **Build plumbing to port**: `applicationVariants.all { outputFileName = "termux-float-app_…" }`,
  `packagingOptions { exclude "lib/*/liblocal-socket.so" }`, `coreLibraryDesugaring`, and the
  `TERMUX_FLOAT_APP__BUILD_APP_VERSION_NAME` / `_APK_VERSION_TAG` env overrides with their own
  semver validation.
- **Signing must match for release too**, not just debug — `sharedUserId` requires identical
  certificates. Debug already matches; release does not (yet).
- **CI**: `.github/workflows/debug_build.yml` and `attach_debug_apks_to_release.yml` run
  `./gradlew assembleDebug`; a second application module means two APKs and a release-attachment
  decision.
- **AGENTS.md constraint**: fork changes are meant to stay concentrated in `:app`. Adding a module
  is a deliberate deviation and should be recorded as such.

## 7. How to resume

1. Ask the user to confirm the goal and pick shape **A** or **B** (A recommended for a faithful,
   cheap port).
2. Approach selection → present the design in sections, scaled to complexity, with approval after
   each: architecture, components, data flow, error handling, testing.
3. Write the spec to `docs/superpowers/specs/2026-10-16-termux-float-port-design.md`, self-review,
   then get the user to review it.
4. Invoke the `writing-plans` skill for the implementation plan. (Per the brainstorming skill,
   `writing-plans` is the only skill to invoke after brainstorming.)
