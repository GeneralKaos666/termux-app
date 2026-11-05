# AGP 9 Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the build from AGP 8.13.2 / Gradle 9.2.1 to AGP 9.1.1 / Gradle 9.3.1 with built-in Kotlin. Tasks 3–5 (the three dependency bumps) were stopped during execution and belong to a compileSdk 37 slice — see the execution status below.

**Architecture:** Tasks 1–2 are signed commits. Commit 1 raises only the Gradle wrapper. Commit 2 performs the whole toolchain migration atomically (AGP, built-in Kotlin, legacy-DSL opt-out, AAR metadata pin, proguard default file, verification metadata, ledger). Tasks 3–5 planned one dependency bump per commit, ascending blast radius, `compose-bom` last; they are blocked.

## Execution status (2026-11-05)

| Task | Outcome |
|---|---|
| Task 1 — Gradle 9.3.1 | done, `1ff791f8` |
| Task 2 — AGP 9.1.1 | done, `d582f402`; 701 tests, lint, both app assemblies, three release AARs and three `publishToMavenLocal` green |
| Task 3 — `core` 1.19.1 | **stopped and reverted**; `minCompileSdk=37`, blocked at `:app:checkDebugAarMetadata` |
| Task 4 — `lifecycle-runtime-compose` 2.11.0 | **not attempted**; `minCompileSdk=37` |
| Task 5 — Compose BOM 2026.09.00 | **not attempted**; `ui`/`foundation` 1.12.1 need `minCompileSdk=37` |

The blocker was recorded by `d1179868` `Changed(docs): Record compileSdk 37 blocker for dependency refresh`. **Do not execute Tasks 3–5 on compileSdk 36.** They need a compileSdk 37 slice first, which also has to deal with Robolectric (4.10 breaks on API 37; 4.17's `android-all-instrumented` Android 17 jar now returns HTTP 200 at 248,937,044 bytes, so re-verify that blocker rather than assuming it) and installing the `android-37` platform (only `android-36` is present here).

**Tech Stack:** Gradle 9.3.1, AGP 9.1.1, Kotlin Gradle plugin 2.4.21 (built-in Kotlin integration), Compose compiler plugin 2.4.21, Android SDK compileSdk 36, NDK 29.0.14206865 (local workaround: `-PndkVersion=30.0.16248370`).

**Spec:** `docs/specs/2026-11-05-agp9-migration-design.md`

## Global Constraints

- AGP **9.1.1**, Gradle **9.3.1**, KGP/Compose plugin stay **2.4.21** on the buildscript classpath.
- `android.newDsl=false` in `gradle.properties`; the `applicationVariants` APK-rename block in `app/build.gradle` stays **unchanged**.
- The five released APK names must keep the shape `termux-app_<tag>_<abi>.apk` for `universal`, `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`.
- `enableKotlin = false` in `:termux-shared`, `:terminal-view`, `:terminal-emulator`.
- `defaultConfig { aarMetadata { minCompileSdk = 1 } }` in all three library modules; no `minAgpVersion` pin.
- All four modules use `getDefaultProguardFile('proguard-android-optimize.txt')`; `app/proguard-rules.pro` gains `-dontoptimize`.
- Package identity, namespaces, published public API and POM dependency set do not change.
- Every commit is GPG-signed. Commit types use the capitalized Conventional Commits form (`Changed(...)`).
- Each commit that changes a resolved artifact carries a regenerated `gradle/verification-metadata.xml`.
- No `compileSdk`/`targetSdk` change, no Robolectric or `hiddenapibypass` bump.
- Local verification commands carry `-PndkVersion=30.0.16248370`; CI keeps the pinned NDK 29 and no flag.

## Review Focus

- **Release-tag builds.** `TERMUX_APK_VERSION_TAG` changes the APK name prefix; all other verification runs without it. Task 2 builds once with `TERMUX_APK_VERSION_TAG=v0.0.0-rc-test` and asserts `termux-app_v0.0.0-rc-test_<abi>.apk`.
- **JitPack publication artifacts.** `withJavadocJar()` runs AGP's Dokka path, which the POM check never executes. Task 2 runs `publishToMavenLocal` on the three libraries so the sources and javadoc jars are built under AGP 9.
- **Release-variant unit tests disappear.** `android.onlyEnableUnitTestForTheTestedBuildType=true` removes `testReleaseUnitTest`; anyone (or any script) invoking it gets "Task not found". Task 2 documents this in the ledger and asserts `./gradlew tasks --all | grep testReleaseUnitTest` is empty.
- **Consumer compileSdk floor.** A new default would force consumers to compileSdk 36. Task 2 asserts `minCompileSdk=1` in each release AAR's `aar-metadata.properties`.
- **Kotlin bytecode target.** Built-in Kotlin derives `jvmTarget` from `compileOptions`; a silent drift to Java 8 bytecode would still pass every test. Task 2 asserts `major version: 61` on a real Kotlin class.

---

## File Structure

| File | Change |
|---|---|
| `gradle/wrapper/gradle-wrapper.properties` | `distributionUrl` → Gradle 9.3.1 |
| `build.gradle` | AGP classpath 9.1.1 (KGP/Compose untouched) |
| `gradle.properties` | add `android.newDsl=false` |
| `app/build.gradle` | drop Kotlin Android plugin and `kotlinOptions`; optimize proguard file; `packagingOptions` → `packaging`; `core` bump (Task 3, blocked); lifecycle bump (Task 4, blocked); BOM bump (Task 5, blocked) |
| `app/proguard-rules.pro` | add `-dontoptimize`; correct the stale `proguard-android.txt` header line |
| `termux-shared/build.gradle` | `enableKotlin = false`, `aarMetadata.minCompileSdk = 1`, optimize proguard file, `core` bump (Task 3, blocked) |
| `terminal-view/build.gradle` | `enableKotlin = false`, `aarMetadata.minCompileSdk = 1`, optimize proguard file |
| `terminal-emulator/build.gradle` | `enableKotlin = false`, `aarMetadata.minCompileSdk = 1`, optimize proguard file |
| `gradle/verification-metadata.xml` | regenerated in Task 2 (Tasks 3–5 not executed) |
| `docs/ledger/outstanding-work.md` | §4.1 follow-ups, §4.2 done entry and test-count note, §4.3 compileSdk 37 blocker and rows 26/29/32 re-labelled |
| `/AGENTS.md` (gitignored, local only) | toolchain paragraph after Task 2; never committed |

---

### Task 1: Gradle wrapper 9.3.1

**Files:**
- Modify: `gradle/wrapper/gradle-wrapper.properties:3`

**Interfaces:**
- Consumes: nothing.
- Produces: Gradle 9.3.1, the minimum wrapper for AGP 9.1.1. Task 2 relies on it.

- [ ] **Step 1: Point the wrapper at 9.3.1**

`gradle/wrapper/gradle-wrapper.properties` line 3 becomes:

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-9.3.1-bin.zip
```

Leave every other key unchanged.

- [ ] **Step 2: Verify the distribution and that AGP 8.13.2 still configures**

Run: `./gradlew --version`

Expected: `Gradle 9.3.1` in the banner.

Run: `./gradlew help`

Expected: `BUILD SUCCESSFUL`, still on AGP 8.13.2 at this point.

- [ ] **Step 3: Decide the fallback if AGP 8.13.2 rejects Gradle 9.3.1**

If `help` fails with an AGP/Gradle compatibility error, do **not** force a fix: revert this file, fold the wrapper change into Task 2, and state in Task 2's commit body that the wrapper and AGP had to move together. If it succeeds, keep the separate commit.

- [ ] **Step 4: Commit**

```bash
git add gradle/wrapper/gradle-wrapper.properties
git commit -m "Changed(build): Upgrade Gradle wrapper to 9.3.1"
```

Expected: `git verify-commit HEAD` reports `Good signature`.

---

### Task 2: Migrate to AGP 9.1.1 with built-in Kotlin

**Files:**
- Modify: `build.gradle:7`
- Modify: `gradle.properties` (append one line)
- Modify: `app/build.gradle` (plugins block, buildTypes.release, packaging block, remove `kotlinOptions`)
- Modify: `app/proguard-rules.pro` (header comment, add `-dontoptimize`)
- Modify: `termux-shared/build.gradle`, `terminal-view/build.gradle`, `terminal-emulator/build.gradle`
- Modify: `gradle/verification-metadata.xml` (regenerated)
- Modify: `docs/ledger/outstanding-work.md`

**Interfaces:**
- Consumes: Gradle 9.3.1 from Task 1.
- Produces: a build whose only AGP is 9.1.1, with built-in Kotlin on `:app` and `enableKotlin = false` on the three libraries; Tasks 3–5 only touch dependency coordinates.

- [ ] **Step 1: Bump the AGP classpath**

In `build.gradle`, change:

```groovy
classpath "com.android.tools.build:gradle:8.13.2"
```

to:

```groovy
classpath "com.android.tools.build:gradle:9.1.1"
```

Do not touch the KGP or Compose plugin lines.

- [ ] **Step 2: Add the legacy-DSL opt-out**

Append to `gradle.properties`:

```properties
# AGP 9 defaults to the new DSL, which hides the legacy variant API used to name the
# APKs in app/build.gradle. Keep the legacy DSL until that hook is migrated.
android.newDsl=false
```

- [ ] **Step 3: Move `:app` to built-in Kotlin**

In `app/build.gradle`:

1. Delete the line `id "org.jetbrains.kotlin.android"` from `plugins`. Keep `com.android.application` and `org.jetbrains.kotlin.plugin.compose`.
2. Delete this block at the end of `android {}`:

```groovy
    kotlinOptions {
        jvmTarget = "17"
    }
```

Nothing replaces it: built-in Kotlin takes the target from `compileOptions.targetCompatibility` (already `JavaVersion.VERSION_17`).

- [ ] **Step 4: Replace the disallowed proguard default file in all four modules**

In `app/build.gradle`, `termux-shared/build.gradle`, `terminal-view/build.gradle` and `terminal-emulator/build.gradle`, change every occurrence of:

```groovy
proguardFiles getDefaultProguardFile('proguard-android.txt'), 'proguard-rules.pro'
```

to:

```groovy
proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
```

- [ ] **Step 5: Keep the release R8 behavior identical**

In `app/proguard-rules.pro`, replace the stale header line

```proguard
# in android-sdk/tools/proguard/proguard-android.txt
```

with

```proguard
# in android-sdk/tools/proguard/proguard-android-optimize.txt
```

and append at the end of the file:

```proguard
# The default file above no longer implies this; keep the release artifact's current
# non-optimized R8 behavior. Re-evaluate when R8 hardening is taken up.
-dontoptimize
```

- [ ] **Step 6: Rename the deprecated packaging block in `:app`**

Change `packagingOptions {` to `packaging {` in `app/build.gradle`. The nested `jniLibs { useLegacyPackaging = true }` block is unchanged.

- [ ] **Step 7: Turn Kotlin off in the three Java-only libraries**

In each of `termux-shared/build.gradle`, `terminal-view/build.gradle` and `terminal-emulator/build.gradle`, add one line directly under the `namespace` line inside `android {}`:

```groovy
    enableKotlin = false
```

Note: `terminal-emulator`'s namespace lives on the first line of `android {}`; `termux-shared` and `terminal-view` likewise. Place the new line immediately after it in each file.

- [ ] **Step 8: Pin the published AAR compileSdk floor**

In each library's existing `defaultConfig {` block, add:

```groovy
        aarMetadata {
            minCompileSdk = 1
        }
```

All three modules already have a `defaultConfig` block, so nothing else changes.

- [ ] **Step 9: Configure, then run the acceptance suite**

Run: `./gradlew help`

Expected: `BUILD SUCCESSFUL` with no AGP deprecation error. Warnings about deprecated APIs are acceptable only if they name something this plan kept on purpose (`newDsl=false`, `packaging`).

Run: `./gradlew test -PndkVersion=30.0.16248370`

Expected: `BUILD SUCCESSFUL`; ~701 test methods, 0 failures. Then check that the tests really executed:

```bash
grep -h -o 'tests="[0-9]*"' */build/test-results/testDebugUnitTest/*.xml \
  | cut -d'"' -f2 | awk '{s+=$1} END {print "tests=" s}'
grep -h -o 'failures="[0-9]*"\|errors="[0-9]*"' */build/test-results/testDebugUnitTest/*.xml \
  | cut -d'"' -f2 | awk '{s+=$1} END {print "failures+errors=" s}'
```

Acceptance: `tests=` in the 690–710 range and `failures+errors=0`. A zero-test or all-skipped result is a **failure** (Robolectric 4.10 on the AGP 9 runtime is unproven; stop and report the migration blocked instead of working around it).

- [ ] **Step 10: Verify Kotlin bytecode and that release-variant tests are gone**

Run:

```bash
find app/build/tmp/kotlin-classes/debug -name '*.class' | head -1 | xargs javap -verbose | grep 'major version'
```

Expected: `major version: 61`. If it is not 61, add `kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }` to `app/build.gradle` and re-run.

Run: `./gradlew tasks --all | grep testReleaseUnitTest`

Expected: no output (the task no longer exists). Record this in the ledger in Step 15.

- [ ] **Step 11: Build both app variants and assert the APK contract**

Run: `./gradlew :app:assembleDebug :app:assembleRelease -PndkVersion=30.0.16248370`

Expected: `BUILD SUCCESSFUL`. Then:

```bash
ls app/build/outputs/apk/debug/termux-app_*_{universal,arm64-v8a,armeabi-v7a,x86_64,x86}.apk
```

Acceptance: five files exist and the listing prints exactly five `debug` names (release splits are off by default, so assert only that `app/build/outputs/apk/release/` contains a `termux-app_*_universal.apk`). Also assert:

```bash
test -f app/build/outputs/mapping/release/mapping.txt && echo mapping-ok
```

- [ ] **Step 12: Re-check the release mapping expectation**

Inspect `app/build/outputs/mapping/release/mapping.txt`:

```bash
grep -c 'com\.termux\.shared\.' app/build/outputs/mapping/release/mapping.txt
grep -c 'com\.termux\.view\.' app/build/outputs/mapping/release/mapping.txt
grep -c 'com\.termux\.terminal\.' app/build/outputs/mapping/release/mapping.txt
```

Acceptance: each count is greater than zero, i.e. the three library packages still appear by name (the keep rules hold). App classes may be repackaged to the unnamed package under AGP 9.1; that is expected and is not a failure.

- [ ] **Step 13: Exercise the release-tag naming path**

Run: `TERMUX_APK_VERSION_TAG=v0.0.0-rc-test ./gradlew :app:assembleDebug -PndkVersion=30.0.16248370`

Expected: `app/build/outputs/apk/debug/termux-app_v0.0.0-rc-test_{universal,arm64-v8a,armeabi-v7a,x86_64,x86}.apk` — five files.

- [ ] **Step 14: Verify library artifacts, test APKs and publications**

Run:

```bash
./gradlew :termux-shared:assembleDebugAndroidTest :terminal-view:assembleDebugAndroidTest \
  :termux-shared:assembleRelease :terminal-view:assembleRelease :terminal-emulator:assembleRelease \
  :termux-shared:publishToMavenLocal :terminal-view:publishToMavenLocal :terminal-emulator:publishToMavenLocal \
  -PndkVersion=30.0.16248370
```

Expected: `BUILD SUCCESSFUL` (this also proves the Dokka javadoc path still works under AGP 9).

Then:

```bash
for m in termux-shared terminal-view terminal-emulator; do
  grep -E 'minCompileSdk|minAgpVersion' $m/build/intermediates/aar_metadata/release/*/aar-metadata.properties
done
```

Acceptance: every module reports `minCompileSdk=1` and `minAgpVersion=1.0.0`.

- [ ] **Step 15: Confirm the POM has no Kotlin stdlib**

Run: `./gradlew :termux-shared:generatePomFileForReleasePublication`

Then:

```bash
grep -c 'kotlin-stdlib' termux-shared/build/publications/release/pom-default.xml
```

Expected: `0` (or grep finds nothing). A non-zero count means `enableKotlin = false` did not take effect — fix before committing.

- [ ] **Step 16: Lint**

Run: `./gradlew lint -PndkVersion=30.0.16248370`

Expected: `BUILD SUCCESSFUL`, 0 errors.

- [ ] **Step 17: Regenerate the dependency verification metadata**

Run:

```bash
./gradlew --write-verification-metadata sha256 help test lint \
  :app:assembleDebug :app:assembleRelease \
  :termux-shared:assembleDebugAndroidTest :terminal-view:assembleDebugAndroidTest \
  :termux-shared:assembleRelease :terminal-view:assembleRelease :terminal-emulator:assembleRelease \
  :termux-shared:publishToMavenLocal :terminal-view:publishToMavenLocal :terminal-emulator:publishToMavenLocal \
  -PndkVersion=30.0.16248370
```

Then re-run the same task list **without** `--write-verification-metadata`, and also run:

```bash
./gradlew help test :app:assembleDebug -PndkVersion=30.0.16248370
```

Expected: both green; a `Dependency verification failed` error means the metadata is incomplete.

- [ ] **Step 18: Update the ledger**

In `docs/ledger/outstanding-work.md`:

1. §4.2 — add a `[done 2026-11-05]` entry: AGP 9.1.1 with Gradle 9.3.1, built-in Kotlin on `:app` (KGP/Compose 2.4.21 kept), `enableKotlin=false` on the three libraries, `android.newDsl=false` to keep the APK rename, `aarMetadata.minCompileSdk=1`, and proguard default file moved to `proguard-android-optimize.txt` with `-dontoptimize`.
2. §4.2 — in the same entry, record that `android.onlyEnableUnitTestForTheTestedBuildType` now defaults to `true`, so `./gradlew test` runs each unit test once (debug only) and `testReleaseUnitTest` no longer exists; historical counts in earlier entries stay as recorded.
3. §4.1 — add two `[open]` items:
   - Migrate the APK file-name hook off `applicationVariants` before AGP 10 (the new DSL exposes no file-name API; the choices are a post-assemble copy task or renaming the released assets).
   - Re-evaluate `-dontoptimize` in `app/proguard-rules.pro` when R8 hardening is taken up; enabling optimization is deliberately out of scope here.

- [ ] **Step 19: Commit**

```bash
git add build.gradle gradle.properties app/build.gradle app/proguard-rules.pro \
  termux-shared/build.gradle terminal-view/build.gradle terminal-emulator/build.gradle \
  gradle/verification-metadata.xml docs/ledger/outstanding-work.md
git commit -m "Changed(build): Migrate Android Gradle plugin to 9.1.1"
```

Expected: `git verify-commit HEAD` reports `Good signature`.

---

### Task 3: `androidx.core` 1.19.1

> **BLOCKED 2026-11-05 — do not execute on compileSdk 36.** `core:1.19.1` and `core-ktx:1.19.1`
> declare `minCompileSdk=37`; the attempt failed at `:app:checkDebugAarMetadata` and was reverted
> (`d1179868`). Execute only after a compileSdk 37 slice lands.

**Files:**
- Modify: `app/build.gradle:29`
- Modify: `termux-shared/build.gradle:12`
- Modify: `gradle/verification-metadata.xml`
- Modify: `docs/ledger/outstanding-work.md` (§4.3 row 26 and the §4.3 paragraph)

**Interfaces:**
- Consumes: the AGP 9.1.1 build from Task 2.
- Produces: `core` 1.19.1 resolved for `:app` and `:termux-shared`, verification metadata for it, ledger row 26 closed. Tasks 4–5 assume this dependency state.

- [ ] **Step 1: Bump both declarations**

Change `implementation "androidx.core:core:1.13.1"` to `implementation "androidx.core:core:1.19.1"` in `app/build.gradle` and in `termux-shared/build.gradle`.

- [ ] **Step 2: Build and test**

Run: `./gradlew test :app:assembleDebug :termux-shared:assembleRelease -PndkVersion=30.0.16248370`

Expected: `BUILD SUCCESSFUL`, same effective test count as Task 2, 0 failures.

- [ ] **Step 3: Regenerate the verification metadata**

Run the Task 2 Step 17 write command again (same task list), then re-run the task list without the write flag.

Expected: green both times.

- [ ] **Step 4: Update the ledger**

Mark row 26 `done 2026-11-05` with the commit id added in Step 5, and update the §4.3 paragraph so only rows 30 and 31 are still blocked.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle termux-shared/build.gradle gradle/verification-metadata.xml docs/ledger/outstanding-work.md
git commit -m "Changed(build): Update androidx.core to 1.19.1"
```

---

### Task 4: `lifecycle-runtime-compose` 2.11.0

> **BLOCKED 2026-11-05 — do not execute on compileSdk 36.** `lifecycle-runtime-compose:2.11.0`
> declares `minCompileSdk=37` (and `minAgp=9.1.0`). Execute only after a compileSdk 37 slice lands.

**Files:**
- Modify: `app/build.gradle:42`
- Modify: `gradle/verification-metadata.xml`
- Modify: `docs/ledger/outstanding-work.md` (§4.3 row 29 and the §4.3 paragraph)

**Interfaces:**
- Consumes: Task 3's dependency state.
- Produces: `lifecycle-runtime-compose` 2.11.0 in `:app`, ledger row 29 closed.

- [ ] **Step 1: Bump the declaration**

Change `implementation "androidx.lifecycle:lifecycle-runtime-compose:2.9.4"` to `implementation "androidx.lifecycle:lifecycle-runtime-compose:2.11.0"` in `app/build.gradle`.

- [ ] **Step 2: Build and test**

Run: `./gradlew test :app:assembleDebug -PndkVersion=30.0.16248370`

Expected: `BUILD SUCCESSFUL`, 0 failures. If a Compose API broke, stop: the version pair is wrong, not the test.

- [ ] **Step 3: Regenerate the verification metadata**

Run the Task 2 Step 17 write command again, then the same list without the write flag.

- [ ] **Step 4: Update the ledger**

Mark row 29 `done 2026-11-05` with the commit id added in Step 5, and adjust the §4.3 paragraph accordingly.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle gradle/verification-metadata.xml docs/ledger/outstanding-work.md
git commit -m "Changed(build): Update lifecycle-runtime-compose to 2.11.0"
```

---

### Task 5: Compose BOM 2026.09.00

> **BLOCKED 2026-11-05 — do not execute on compileSdk 36.** `compose-bom:2026.09.00` maps `ui` and
> `foundation` to 1.12.1, both `minCompileSdk=37` (`material3` 1.4.0 is 35, `runtime` 1.12.1 is 34).
> Execute only after a compileSdk 37 slice lands.

**Files:**
- Modify: `app/build.gradle:36`
- Modify: `gradle/verification-metadata.xml`
- Modify: `docs/ledger/outstanding-work.md` (§4.3 row 32 and the §4.3 paragraph)

**Interfaces:**
- Consumes: the migrated build plus Tasks 3–4.
- Produces: the last open §4.3 dependency refresh row closed; only rows 30 and 31 stay blocked.

- [ ] **Step 1: Bump the BOM**

Change `implementation platform("androidx.compose:compose-bom:2025.11.01")` to `implementation platform("androidx.compose:compose-bom:2026.09.00")` in `app/build.gradle`.

Do not change the unpinned Compose artifact declarations (`ui`, `foundation`, `material3`, tooling) — the BOM governs them.

- [ ] **Step 2: Build, test and lint**

Run: `./gradlew test lint :app:assembleDebug :app:assembleRelease -PndkVersion=30.0.16248370`

Expected: `BUILD SUCCESSFUL`, 0 failures, 0 lint errors. Re-check the five debug APK names, since this is the last commit that can disturb packaging.

- [ ] **Step 3: Regenerate the verification metadata**

Run the Task 2 Step 17 write command again, then the same list without the write flag.

- [ ] **Step 4: Update the ledger**

Mark row 32 `done 2026-11-05` with the commit id added in Step 5, and rewrite the §4.3 paragraph: the refresh is closed except rows 30 and 31, which stay blocked on compileSdk 37.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle gradle/verification-metadata.xml docs/ledger/outstanding-work.md
git commit -m "Changed(build): Update Compose BOM to 2026.09.00"
```

- [ ] **Step 6: Update the local toolchain note**

Update the toolchain paragraph in the gitignored `/AGENTS.md` (AGP 9.1.1, Gradle 9.3.1, built-in Kotlin, `android.newDsl=false`). Do not commit it.

---

## Self-Review Notes

- Spec §2 (APK names) → Task 2 Steps 11 and 13. §3 (built-in Kotlin, `enableKotlin`) → Steps 3, 7, 15. §4 (API changes) → Steps 4–6. §5 (defaults) → Task 2 Step 18 ledger note plus Steps 10, 12, 16. §6 (AAR metadata) → Step 8 and Step 14. §7 (bumps) → Tasks 3–5, all blocked on compileSdk 37, none executed. §8 (verification) → Steps 9–17. §9 (commit split) → Tasks 1–2 plus the blocker-record commit.
- The spec's risk table maps to explicit stop conditions: Robolectric failure → Task 2 Step 9, jvmTarget drift → Step 10, proguard behavior → Step 5, AAR metadata → Step 14, missing build-tools → `help` failure in Step 9 (install `build-tools;36.0.0` with `sdkmanager` and retry).
