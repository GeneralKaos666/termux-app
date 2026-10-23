# Upgrade roadmap (platform currency)

Items 1–3 completed in this slice. Item 15 (`targetSdkVersion` raise) is dropped by decision. Remaining items below are **to do**; dependency refresh is one commit per dependency ascending blast radius with `compose-bom` last and separate. R8/release build remains a separate architectural slice.

| # | Item | Status | Notes |
|---|------|--------|-------|
| 1 | Gradle 10 DSL syntax (`prop = value`) | done | c9826aaa |
| 2 | Move `FileUtilsTests` out of published artifact (instrumented) | done | c389a88d |
| 3 | AndroidX instrumentation test runner (`terminal-view`) | done | 823542d7 |
| 4 | Docs re-homing (`docs/ideas/` → `docs/ledger/`) | done | this commit |
| 5 | R8/release build hardening (keep rules for `:termux-shared` public API, JNI entry points, Compose) | to do | separate slice, affects published artifact |
| 6 | God-class splits: `TerminalEmulator.java` (3,876), `TerminalView.java` (1,661), `FileUtils.java` (2,044), `TermuxConstants.java` (1,338) | to do | behavior-preserving |
| 7 | `:termux-shared` unit tests | to do | expand beyond instrumented harness |
| 8 | `LOG_ESCAPE_SEQUENCES = false` silent protocol errors | to do | enable/restore logging or explicit errors |
| 9 | Focus events (DECSET 1004) never emitted | to do | fix terminal focus event delivery |
| 10 | Wallpaper-mode palette repaint | to do | fix repaint on palette changes |
| 11 | Wide-glyph OSC 8 hit-test | to do | hit-test accuracy for wide glyphs + hyperlinks |
| 12 | Java 1.8 → 17 | to do | align toolchain; check desugaring |
| 13 | CI hardening (lint, release build, dependabot for Gradle, dependency verification) | to do | workflows |
| 14 | CI bootstrap re-download | to do | avoid re-downloading cached zips unnecessarily |
| 15 | JitPack versioning | to do | library publishing/versioning |
| 16 | Session restore | to do | restore sessions across process death |
| 17 | Termux:Float (shape A vs B) | to do | compatibility/shape support |
| 18 | 256-colour palette | to do | full 256-colour support |
| 19 | xterm conformance + WS6 audit | to do | conformance gaps |
| 20 | Device checks D-3…D-9 | to do | device/compat checks |
| 21 | Dependency refresh — `desugar_jdk_libs` 1.1.5 → 2.1.5 | to do | ascending blast radius |
| 22 | Dependency refresh — `commons-io` 2.5 → 2.22.0 | to do | note Android < 8 rationale replaced by desugaring |
| 23 | Dependency refresh — `guava` 24.1-jre → 33.7.2-jre | to do | keep `listenablefuture` shim if needed |
| 24 | Dependency refresh — `androidx.window` 1.1.0 → 1.5.1 | to do | |
| 25 | Dependency refresh — `appcompat` 1.6.1 → 1.8.0 | to do | |
| 26 | Dependency refresh — `material` 1.12.0 → 1.14.0 | to do | |
| 27 | Dependency refresh — `core` 1.13.1 → 1.19.1 | to do | |
| 28 | Dependency refresh — `annotation` 1.9.0 → 1.11.0 | to do | |
| 29 | Dependency refresh — `activity-compose` 1.11.0 → 1.13.0 | to do | |
| 30 | Dependency refresh — `lifecycle-runtime-compose` 2.9.4 → 2.11.0 | to do | |
| 31 | Dependency refresh — `robolectric` 4.10 → 4.17 | to do | |
| 32 | Dependency refresh — `hiddenapibypass` 6.1 → 6.2 | to do | |
| 33 | Dependency refresh — `compose-bom` 2025.11.01 → 2026.09.00 | to do | **last and separate** |
