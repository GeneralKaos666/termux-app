# compileSdk 37 — design

**Date:** 2026-11-05
**Scope:** raise `compileSdkVersion` from 36 to 37, bump Robolectric 4.10 → 4.17 (the prerequisite
that makes unit tests compile against the new SDK), and land the four dependency bumps that
compileSdk 37 unblocks: `hiddenapibypass` 6.2, `androidx.core` 1.19.1,
`androidx.lifecycle:lifecycle-runtime-compose` 2.11.0, and `androidx.compose:compose-bom`
2026.09.00. `targetSdkVersion` (28) and `minSdkVersion` (23) do not change.
**Ledger item:** `outstanding-work.md` §4.3 rows 26, 29, 30, 31 and 32 (the last five open
dependency-refresh rows).

## 1. Why this slice and what the probe proved

§4.3 rows 26, 29, 30, 31 and 32 all record blockers that trace to the same root: the target
artifacts declare `minCompileSdk=37` (or, for `hiddenapibypass` 6.2, need it), but the repository
compiles against 36. Raising `compileSdk` was previously assumed to break Robolectric 4.10, which
could not be bumped because its `android-all-instrumented` jar was unfetchable here. A throwaway
probe (2026-11-05) disproved both halves of that assumption and corrected the blocker reason:

- The `android-37` platform installs. Its package name is `platforms;android-37.0` (the base SDK,
  `IsBaseSdk=true`, API level 37.0), not `platforms;android-37`. The local `sdkmanager` (12.0)
  prints an "SDK XML version 4" warning yet installs it, and AGP 9.1.1 resolves `compileSdkVersion
  = 37` to it.
- The real Robolectric blocker is **not** `ShadowActivity` at runtime. It is compile-time:
  `android.hardware.fingerprint.FingerprintManager` was removed from `android-37`'s `android.jar`,
  and it is referenced *transitively* (no `FingerprintManager` symbol exists anywhere in this
  repository's source) through the test compile classpath. `:app:compileDebugUnitTestJavaWithJavac`
  fails with `cannot access FingerprintManager`. Bumping Robolectric 4.10 → 4.17 clears it with
  **no source change**.
- Robolectric 4.17 works on this device: it downloads the 249 MB `android-all-instrumented` jar
  plus ~38 other artifacts and runs 701/701 unit tests with 0 failures under compileSdk 37.
- All four dependency bumps resolve, pass `:app:checkDebugAarMetadata`, assemble five debug APKs,
  and keep the 701-test suite green under compileSdk 37 + Robolectric 4.17.
- `lint` passes under compileSdk 37 (CI runs `./gradlew lint`), so no new lint baseline is needed.

Because the two hard risks are already proven, this slice is a sequence of mechanical commits with
verification gates, not an experiment.

## 2. Constraints

- `targetSdkVersion=28` and `minSdkVersion=23` stay unchanged. Raising only `compileSdk` is legal
  and does not opt the app into new runtime behavior.
- No application or library source file changes. The probe compiled `:app` main + test, all four
  modules, and `assembleDebug`/`assembleRelease` with zero source edits.
- The published AAR compatibility surface does not change: the three library modules already pin
  `defaultConfig.aarMetadata.minCompileSdk = 1` (from the AGP 9 migration), so a compileSdk 37
  build still advertises a compileSdk-1 floor and companion apps are unaffected. `hiddenapibypass`,
  `core` and the Compose artifacts are `implementation` dependencies, not `api`, so they do not
  appear in the published POM's dependency list.
- Every commit is GPG-signed and uses the capitalized Conventional Commits form (`Changed(...)`).
- Every commit that changes a resolved Maven artifact carries a regenerated
  `gradle/verification-metadata.xml`. Commit 2 (the compileSdk raise) adds no Maven artifacts, so
  it does not regenerate the file.
- Local verification commands carry `-PndkVersion=30.0.16248370` (the NDK 29 `aarch64` workaround);
  CI uses the pinned NDK and needs no flag.

## 3. Commit sequence

Six signed commits, ascending blast radius, matching §4.3's "one commit per dependency" rule:

1. `Changed(build): Update Robolectric to 4.17` — `:app` + `:terminal-emulator`, metadata, ledger
   row 30.
2. `Changed(build): Raise compileSdk to 37` — `gradle.properties`, ledger §4.3 paragraph. No
   source change, no metadata regen.
3. `Changed(build): Update hiddenapibypass to 6.2` — `:termux-shared`, metadata, ledger row 31.
4. `Changed(build): Update androidx.core to 1.19.1` — `:app` + `:termux-shared`, metadata, ledger
   row 26.
5. `Changed(build): Update lifecycle-runtime-compose to 2.11.0` — `:app`, metadata, ledger row 29.
6. `Changed(build): Update Compose BOM to 2026.09.00` — `:app`, metadata, ledger row 32.

Robolectric must precede the compileSdk raise: it is the only change that makes the unit-test
compilation pass under `android-37`. Each dependency commit is self-contained and bisectable.

## 4. Per-commit detail

### Commit 1 — Robolectric 4.10 → 4.17

- `app/build.gradle:160` and `terminal-emulator/build.gradle:74`: `4.10` → `4.17`.
- `:termux-shared` is untouched (its unit tests are plain JUnit, no Robolectric).
- Regenerate `gradle/verification-metadata.xml` (adds ~38 artifacts: `robolectric:annotations`,
  `junit`, `nativeruntime`, `pluginapi`, `shadows-framework`, `org.ow2.asm:asm` 9.10.1,
  `com.ibm.icu:icu4j` 78.3, and transitive deps).
- Verification gate: `./gradlew test` = 701 methods, 0 failures/errors, still under compileSdk 36.
- Stop condition: a test failure means Robolectric 4.17 changes a runtime behavior a test depends
  on; report and do not work around it.

Note: Robolectric's `android-all-instrumented` jar is fetched by Robolectric itself at test
runtime (not by Gradle), so it never appears in `gradle/verification-metadata.xml`. This is
unchanged from 4.10.

### Commit 2 — compileSdk 36 → 37

- `gradle.properties:25`: `compileSdkVersion=36` → `37`.
- No source change, no dependency change, no verification-metadata regen.
- The `android-37.0` platform must be present. Locally it is already installed; AGP 9.1.1
  auto-installs it elsewhere (CI's `setup-java`-only jobs let AGP fetch it, exactly as it already
  fetches build-tools 36 and NDK 29 today).
- Verification gate: `./gradlew test lint :app:assembleDebug :app:assembleRelease`. `lint` is in
  the gate because CI runs it and the SDK bump is the kind of change that can surface new checks.
- Stop condition: any failure stops the commit; do not add `@SuppressLint` or `baseline.xml` as a
  workaround without explicit approval.

### Commits 3–6 — the four dependency bumps

Each commit changes one coordinate, regenerates the verification metadata, closes its ledger row,
and runs `./gradlew test :app:assembleDebug` (commit 6 also runs `lint` and `:app:assembleRelease`
because it is the last commit that can disturb packaging). The final §4.3 paragraph is rewritten
so rows 26/29/30/31/32 read `done` and only row 33 (device/manual checks) remains open.

The coordinate changes:

| Commit | File | From | To |
|---|---|---|---|
| 3 | `termux-shared/build.gradle:21` | `org.lsposed.hiddenapibypass:hiddenapibypass:6.1` | `...:6.2` |
| 4 | `app/build.gradle:28`, `termux-shared/build.gradle:14` | `androidx.core:core:1.13.1` | `...:1.19.1` |
| 5 | `app/build.gradle:44` | `androidx.lifecycle:lifecycle-runtime-compose:2.9.4` | `...:2.11.0` |
| 6 | `app/build.gradle:39` | `androidx.compose:compose-bom:2025.11.01` | `...:2026.09.00` |

Commit 6 changes only the BOM coordinate; the unpinned Compose artifact declarations (`ui`,
`foundation`, `material3`, tooling) stay as-is because the BOM governs them. The BOM maps `ui` and
`foundation` to 1.12.1 (both `minCompileSdk=37`), `material3` to 1.4.0 (35) and `runtime` to
1.12.1 (34).

## 5. Verification matrix

Every commit gates on a green build; the specific tasks differ as listed in §4.

| Check | Commits |
|---|---|
| `./gradlew test -PndkVersion=30.0.16248370` → 701 methods, 0 failures | 1–6 |
| `./gradlew lint` | 2, 6 |
| `./gradlew :app:assembleDebug :app:assembleRelease` → five `termux-app_*_<abi>.apk` | 2, 6 |
| `./gradlew --write-verification-metadata sha256 <tasks>`, then the same tasks without the flag | 1, 3, 4, 5, 6 |
| `git verify-commit HEAD` → `Good signature` | 1–6 |

The five released APK names keep the shape `termux-app_<tag>_<abi>.apk` for `universal`,
`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`, as the release workflows validate.

## 6. Compatibility and blast radius

- `core` 1.19.1 is consumed by both `:app` and `:termux-shared`, so it is the widest AndroidX bump
  and sits after `hiddenapibypass`. `lifecycle-runtime-compose` and the Compose BOM are `:app`-only;
  the BOM is last because it moves the largest set of Compose artifacts at once.
- `hiddenapibypass` 6.2 changes hidden-API access behavior at runtime. Its unit-test coverage is
  thin (it is exercised through `TermuxShared` call sites, not a dedicated suite), so it is placed
  first among the dependency bumps to surface any regression in isolation.
- No public API, package identity, or POM dependency set changes in any commit.

## 7. Non-goals

- No `targetSdk` or `minSdk` change.
- No application or library source change (the `FingerprintManager` failure was transitive and is
  fixed by the Robolectric bump).
- No CI workflow change: AGP already auto-installs the SDK components it needs.
- No caching of Robolectric's 249 MB `android-all` jar in CI (flagged as a follow-up, not this
  slice).
- No change to the `android.newDsl=false` APK-rename hook or the `-dontoptimize` release flag
  (those remain §4.1 follow-ups from the AGP 9 slice).

## 8. Risks

| Risk | Detection | Response |
|---|---|---|
| Robolectric 4.17 alters a runtime behavior a test relies on | `./gradlew test` fails in commit 1 | Stop; report the specific failing test rather than working around it |
| `compileSdk` 37 surfaces a lint error | `./gradlew lint` fails in commit 2 | Stop; do not suppress without approval |
| A dependency bump changes behavior under test | `./gradlew test` fails in commits 3–6 | Stop; the version pair, not the test, is wrong — do not edit tests |
| CI cannot auto-install `android-37.0` | the first CI `test`/`lint` run after merge fails on a missing platform | Add an explicit `sdkmanager "platforms;android-37.0"` step to the workflows (deferred, only if the auto-install proves insufficient) |
| `android-all` 249 MB download fails or is slow on CI | CI `test` job times out or errors mid-fetch | Add `actions/cache` for the Robolectric Maven sandbox (follow-up) |
