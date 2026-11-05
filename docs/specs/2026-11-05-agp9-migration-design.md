# AGP 9 migration — design

**Date:** 2026-11-05
**Scope:** move the build from AGP 8.13.2 / Gradle 9.2.1 to AGP 9.1.1 / Gradle 9.3.1 with
built-in Kotlin, preserve the APK file names the release workflows validate, preserve the
published-AAR compatibility surface, and then land the three dependency bumps that AGP 9.1
unblocks (`androidx.core` 1.19.1, `androidx.lifecycle:lifecycle-runtime-compose` 2.11.0,
`androidx.compose:compose-bom` 2026.09.00).
**Ledger item:** `outstanding-work.md` §4.3 rows 26, 29 and 32 (all marked *blocked — needs
AGP 9.1.0+*).

## 1. Why this slice and why these versions

Rows 26, 29 and 32 of the §4.3 roadmap are the last open dependency-refresh items. Each one
records the same blocker: the artifact needs AGP 9.1.0 or newer. The repository is on AGP
8.13.2, so the refresh cannot continue without migrating AGP first.

AGP versions published on `dl.google.com` at the time of writing go up to `9.6.0-alpha02`
(`9.5.0` is the newest stable). The compatibility tables give:

| AGP | Minimum Gradle | Maximum API level |
|---|---|---|
| 9.0.x | 9.1.0 | 36.1 |
| 9.1.1 | 9.3.1 | 37 |
| 9.5.0 | 9.6.0 | 37 |

**Chosen: AGP 9.1.1 with Gradle 9.3.1.** It is the lowest stable pair that satisfies the
`9.1.0+` requirement, so it adds the fewest AGP behavior changes beyond what the dependency
bump demands. AGP 9.5 would force a second, larger wrapper jump and bring four more minor
releases of behavior change with no dependency benefit. Alphas are not acceptable for a
release-producing build.

JDK and SDK constraints are already met: CI and JitPack pin Temurin 17, this workspace runs
Java 21, `compileSdkVersion=36`, `minSdkVersion=23`, `targetSdkVersion=28`. AGP 9.1.1 needs
JDK 17 minimum and build-tools 36.0.0. Build-tools 36.0.0 is not installed in this workspace
(35.0.0 and 37.0.0 are); AGP may download it, otherwise it is installed with `sdkmanager`
before the migration is verified.

## 2. The APK-name constraint and `android.newDsl=false`

`app/build.gradle` renames every APK inside `applicationVariants.all { variant.outputs.all { ... } }`.
Two workflows depend on that naming:

- `.github/workflows/debug_build.yml` validates `app/build/outputs/apk/debug/termux-app_<tag>_<abi>.apk`.
- `.github/workflows/attach_debug_apks_to_release.yml` uploads the five files
  (`universal`, `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`) plus a `sha256sums` file to the
  GitHub release.

AGP 9.0 enables the new public DSL by default and hides the legacy implementation and the
legacy variant API. The AGP 9.1.1 `gradle-api` jar confirms that the new
`com.android.build.api.variant.VariantOutput` interface exposes only `versionCode`, `versionName`
and `enabled` — there is no `outputFileName` and no replacement on `ApkOutput` (only
`apkInstallGroups`). There is therefore no new-DSL way to set the APK file name.

**Decision: set `android.newDsl=false` in `gradle.properties`** and keep the existing
`applicationVariants` block unchanged. The opt-out is documented by AGP and removed in AGP 10,
so the ledger gains a follow-up row for the new-DSL naming migration. The alternatives were
rejected:

- A post-`assemble` copy task that writes the expected names next to AGP's default
  `app-<abi>-<buildType>.apk` files: fully new DSL, but duplicates every APK (~5 files per
  build), needs task-graph wiring, and must be proven against both workflows.
- Dropping the custom names and rewriting both workflows: changes the released asset names,
  which is a product-visible change outside this slice.

`android.newDsl=false` also keeps the remaining legacy constructs working
(`android.sourceSets.main.java.srcDirs` in the three `sourceJar` tasks, `applicationVariants`).
No other build file relies on legacy variant APIs.

## 3. Built-in Kotlin

AGP 9.0 enables built-in Kotlin by default; the `org.jetbrains.kotlin.android` plugin is not
compatible with it and must be removed.

- Only `:app` has Kotlin sources (61 `.kt` files). The three library modules have none.
- Remove `id "org.jetbrains.kotlin.android"` from `app/build.gradle`.
- Keep the `org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21` and
  `org.jetbrains.kotlin.plugin.compose:2.4.21` classpath entries in the root `build.gradle`.
  AGP carries a runtime dependency on KGP 2.2.10; declaring 2.4.21 on the buildscript classpath
  is the documented way to stay on the higher version, and the Compose plugin must match KGP.
  The `plugins { id "org.jetbrains.kotlin.plugin.compose" }` application in `:app` stays.
- Remove `kotlinOptions { jvmTarget = "17" }` from the `android` block; it is a KGP extension that
  does not exist with built-in Kotlin. With built-in Kotlin the Kotlin target defaults to
  `compileOptions.targetCompatibility`, which is already `JavaVersion.VERSION_17`. Verification
  asserts the Kotlin classes are Java 17 bytecode (major version 61); if they are not, set
  `kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }` explicitly rather than accepting
  a different target.
- Add `enableKotlin = false` to `:termux-shared`, `:terminal-view` and `:terminal-emulator`.
  Two reasons: it removes the Kotlin compilation task from modules with no Kotlin sources, and
  it keeps the automatic `kotlin-stdlib` dependency out of the published POMs, which would
  otherwise be a new transitive dependency for the companion apps.

Fallback if the Compose plugin cannot attach to the built-in Kotlin integration, or if KGP 2.4.21
fails against AGP 9.1.1's internal KGP API: set `android.builtInKotlin=false` and restore
`org.jetbrains.kotlin.android`, keeping AGP 9.1.1. That combination requires `android.newDsl=false`,
which the design already sets. A last resort is the KGP/Compose pair AGP 9.1.1 bundles (2.2.10),
which would be a Kotlin downgrade and is not chosen unless the build cannot work otherwise.

## 4. AGP 9.1 API and behavior changes that need a build-file edit

| Change | Action |
|---|---|
| `getDefaultProguardFile('proguard-android.txt')` is disallowed (`android.r8.proguardAndroidTxt.disallowed=true`); only `proguard-android-optimize.txt` is accepted | Replace the call with `proguard-android-optimize.txt` in all four modules, add `-dontoptimize` to `app/proguard-rules.pro` so the release artifact keeps today's non-optimized R8 behavior (the old default file contained `-dontoptimize`), and update that file's stale `proguard-android.txt` header mention. The three libraries set `minifyEnabled false`, so their call never runs R8. Enabling optimization is a separate decision for the R8-hardening work, not this migration. |
| `buildConfigField` still exists on `VariantDimension`; verified in the 9.1.1 API jar | No change |
| `compileSdkVersion(int)`, `minSdkVersion(int)`, `targetSdkVersion(int)` still exist | No change |
| `splits.abi` still exposes `enable`, `reset()`, `include` and `universalApk` | No change |
| `publishing.multipleVariants { withSourcesJar() / withJavadocJar() / allVariants() }` still exists on `LibraryPublishing` | No change |
| `packagingOptions` is still present but deprecated in favor of `packaging` | Rename in `:app` |

## 5. AGP 9 defaults accepted as-is

| Property | New default | Why it is safe here |
|---|---|---|
| `android.onlyEnableUnitTestForTheTestedBuildType` | `true` | Unit tests are created for debug only. `./gradlew test` executes ~701 methods instead of ~1,402, and CI never ran the release variant separately. The ledger gains a note that each unit test now runs once; historical test counts in earlier done entries are left as they were recorded. |
| `android.enableAppCompileTimeRClass` | `true` | The app compiles against a non-final `R`. No `switch`/`when` uses an `R` id (grepped in `app/src`). |
| `android.r8.strictFullModeForKeepRules` | `true` | `-keep class X` no longer implies the default constructor. All consumer rules already use `-keep class com.termux.{shared,view,terminal}.** { *; }`, which keeps constructors. |
| `android.r8.optimizedResourceShrinking` | `true` | `shrinkResources = false` in `:app`, and only the app shrinks. |
| `android.dependency.useConstraints` | `false` | No app `androidTest`. The two library `androidTest` compiles are verified explicitly. |
| `android.uniquePackageNames` | `true` | Namespaces are already distinct: `com.termux`, `com.termux.shared`, `com.termux.view`, `com.termux.emulator`. |
| `android.default.androidx.test.runner` | `true` | Every module that sets a runner already names `androidx.test.runner.AndroidJUnitRunner`. |
| `android.sdk.defaultTargetSdkToCompileSdkIfUnset` | `true` | `targetSdkVersion` is set explicitly in every module. |
| `android.defaults.buildfeatures.resvalues` / `.shaders` | `false` | No `resValue` and no shaders in the build. |
| Default NDK | r28c | `ndkVersion` is pinned to 29.0.14206865 in `gradle.properties`. |
| AGP 9.1 R8 repackages obfuscated classes to the unnamed package | enabled | Library classes stay named through the keep rules. The release mapping check is re-run with the new expectation (`com.termux.app.**` repackaged, library classes named). |

## 6. Published-AAR compatibility

AGP 9.0 changes the default AAR metadata: `LibraryVariantImpl` now defaults `minCompileSdk` to the
module's own compileSdk (36 here) unless `android.aar.metadata.defaultMinCompileSdkToCompileSdk=false`.
Today's AARs carry `minCompileSdk=1` (checked in `build/intermediates/aar_metadata/`), and the
companion apps are separate builds. Raising the requirement to 36 could break their builds, so each
library module sets

```groovy
defaultConfig {
    aarMetadata {
        minCompileSdk = 1
    }
}
```

to preserve the current value. The DSL pin is preferred over the boolean option because the option
is already marked for removal in AGP 10. `minAgpVersion` keeps its default `1.0.0`; it is checked
after the first build and pinned only if it unexpectedly excludes an AGP 8.x consumer. Library
public APIs, package names and `com.termux` identities do not change.

## 7. Dependency bumps this slice unlocks

Landed only after the migration builds green, one commit each, ascending blast radius:

| Item | From | To | Notes |
|---|---|---|---|
| `androidx.core:core` | 1.13.1 | 1.19.1 | `:app` and `:termux-shared` |
| `androidx.lifecycle:lifecycle-runtime-compose` | 2.9.4 | 2.11.0 | `:app` only |
| `androidx.compose:compose-bom` | 2025.11.01 | 2026.09.00 | last and separate, per §4.3 |

Still blocked and left untouched: `hiddenapibypass` 6.2 (needs compileSdk 37, which breaks
Robolectric 4.10), Robolectric 4.17 (its `android-all-instrumented` jar cannot be fetched here),
and therefore the compileSdk raise.

## 8. Verification

All commands run in this workspace with `-PndkVersion=30.0.16248370`, the local workaround for
NDK 29's `Unknown host CPU architecture: aarch64`; CI uses the pinned NDK 29 and needs no flag.

1. `./gradlew help` — configuration succeeds under the new DSL.
2. `./gradlew test` — ~701 methods, 0 failures. Inspect `*/build/test-results/*/TEST-*.xml`:
   Robolectric 4.10 is unproven on AGP 9, so a skipped or zero-test run is a failure, not a pass.
3. `./gradlew lint` — 0 errors.
4. `./gradlew :app:assembleDebug :app:assembleRelease` — then assert the five
   `termux-app_*_<abi>.apk` files exist in `app/build/outputs/apk/debug`, that
   `app/build/outputs/mapping/release/mapping.txt` exists, and that the mapping still names the
   three library packages (`com.termux.shared`, `com.termux.view`, `com.termux.terminal`).
5. Assert the Kotlin bytecode target: `javap -verbose` on a class under
   `app/build/tmp/kotlin-classes/debug` reports `major version: 61`.
6. `./gradlew :termux-shared:assembleDebugAndroidTest :terminal-view:assembleDebugAndroidTest`.
7. `./gradlew :termux-shared:assembleRelease :terminal-view:assembleRelease :terminal-emulator:assembleRelease`
   — inspect each release AAR's `aar-metadata.properties`: `minCompileSdk=1`, `minAgpVersion=1.0.0`.
8. `./gradlew :termux-shared:generatePomFileForReleasePublication` — inspect
   `termux-shared/build/publications/release/pom-default.xml` and confirm there is no
   `org.jetbrains.kotlin:kotlin-stdlib` dependency (the `enableKotlin=false` guarantee).
9. Regenerate `gradle/verification-metadata.xml` in every commit that changes a resolved artifact,
   with the same task list used in §4.2 plus steps 4–8 above; then rebuild with no write flag and
   confirm dependency verification passes.

JitPack cannot be exercised from this workspace: its build is triggered only by a release tag. The
`jitpack.yml` JDK 17 pin already satisfies AGP 9.1.1, and CI's `ubuntu-latest` runner auto-installs
build-tools 36.0.0 the same way the local SDK does. A JitPack failure would be a release-time
finding, not something this slice can prove or prevent.

## 9. Commit sequence

1. `Changed(build): Upgrade Gradle wrapper to 9.3.1` — wrapper properties only.
2. `Changed(build): Migrate Android Gradle plugin to 9.1.1` — root and module build files,
   `android.newDsl=false`, built-in Kotlin, `enableKotlin=false`, proguard default file,
   AAR metadata pin, regenerated verification metadata, ledger updates.
3. `Changed(build): Update androidx.core to 1.19.1` — dependency, metadata, ledger row 26.
4. `Changed(build): Update lifecycle-runtime-compose to 2.11.0` — dependency, metadata,
   ledger row 29.
5. `Changed(build): Update Compose BOM to 2026.09.00` — BOM, metadata, ledger row 32.

Every commit is GPG-signed. No commit mixes two dependency changes.

Ledger and docs changes follow the same split:

- **Commit 2** adds a `[done 2026-11-05]` entry to §4.2 recording AGP 9.1.1 / Gradle 9.3.1 and the
  built-in Kotlin setup, notes in §4.1 for the two new open items (migrate the APK rename off
  `android.newDsl=false` before AGP 10, and re-evaluate `-dontoptimize` when R8 hardening is
  revisited), and a §4.2 note that each unit test now runs once (debug only).
- **Commits 3–5** mark rows 26, 29 and 32 done with their commit ids and refresh the §4.3
  paragraph that currently says the remaining refresh is blocked on AGP 9.1 (only rows 30 and 31
  stay blocked afterwards).
- The local, gitignored `/AGENTS.md` toolchain paragraph is updated after the migration; it is not
  part of any commit.

## 10. Risks

| Risk | Detection | Response |
|---|---|---|
| Compose compiler plugin does not attach to built-in Kotlin | `:app:compileDebugKotlin` fails | Set `android.builtInKotlin=false`, restore `org.jetbrains.kotlin.android` |
| KGP 2.4.21 is incompatible with AGP 9.1.1's internal KGP API | configuration or `:app:compileDebugKotlin` fails inside KGP | Same `builtInKotlin=false` fallback; only if that also fails, fall back to the bundled KGP/Compose 2.2.10 pair and record it as a Kotlin downgrade |
| Kotlin bytecode target silently changes | step 5 reports a major version other than 61 | Set `kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }` |
| Robolectric 4.10 incompatible with AGP 9 unit-test runtime | `./gradlew test` fails or runs zero tests | Cannot bump Robolectric here (jar unfetchable); stop and report the migration as blocked |
| Release behavior changes because `proguard-android-optimize.txt` enables R8 optimization | release mapping differs from a plain keep-rule change | `-dontoptimize` in `app/proguard-rules.pro` keeps today's behavior; enabling optimization is left to the R8-hardening item |
| Build-tools 36.0.0 absent | AGP error naming the missing package | Install with `sdkmanager "build-tools;36.0.0"` |
| Gradle daemon OOM at `-Xmx2048M` | daemon exits with `OutOfMemoryError` | Raise `org.gradle.jvmargs` only after reproducing |
| AGP 9 default AAR metadata blocks companion apps | a release AAR shows `minCompileSdk` above 1 | The `minCompileSdk = 1` pin is the mitigation; `minAgpVersion` stays `1.0.0` unless a build proves otherwise |

## 11. Non-goals

- No `compileSdk` or `targetSdk` change.
- No `hiddenapibypass`, Robolectric or other blocked dependency bump.
- No migration of the APK-rename hook to the new DSL (ledger follow-up before AGP 10).
- No library public-API, package-identity or POM dependency change.
- No app-code change: this slice touches build files, tooling metadata and the ledger only.
