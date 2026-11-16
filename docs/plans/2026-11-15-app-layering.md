# App Layering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the session-mapping, wallpaper, notification and wake-lock logic out of `TermuxActivity` and `TermuxService` into testable units, behind package layers inside `:app`, without changing anything a companion app resolves.

**Architecture:** Three packages inside `:app` — `com.termux.app.domain` (pure Kotlin), `com.termux.app.data` (platform-backed), `com.termux.app.ui` (Compose and state holders) — with the Activity and Service remaining the composition layer. A source-scanning `LayeringTest` enforces the import rules under the existing `./gradlew test` gate. No new Gradle module.

**Tech Stack:** Java 17 + Kotlin 2.4.21, AGP 9.1.1, Compose, JUnit 4, Robolectric (`app/src/test`, `includeAndroidResources = true`).

**Spec:** `docs/specs/2026-11-15-app-layering-design.md`

**Ordering note:** this plan runs the F4 preference seam (Task 4) *before* `LayeringTest` (Task 5), so the rule can land with no temporary allowlist. The spec's §12 lists F4 after the test; the deviation is deliberate and recorded in the spec.

## Global Constraints

- Do not modify anything under `termux-shared/`, `terminal-emulator/`, `terminal-view/`, `app/src/main/AndroidManifest.xml` or `gradle.properties`. Run `git diff --stat -- termux-shared terminal-emulator terminal-view app/src/main/AndroidManifest.xml gradle.properties` before every commit; it must print nothing.
- No new Gradle module, no new dependency, no `lifecycle-viewmodel`.
- Commit messages use this repository's capitalized Conventional Commit types: `Added`, `Changed`, `Deprecated`, `Removed`, `Fixed`, `Security`, with a present-tense description, e.g. `Changed(app): Extract session mapping from TermuxActivity`.
- Every commit is signed: `git commit -S`, then `git log -1 --format='%G?'` must print `G`.
- Per-task gate: `./gradlew :app:testDebugUnitTest`. Final gate: `./gradlew test` and `./gradlew :app:assembleDebug`.
- Style: 4-space indent, LF, final newline (`.editorconfig`).
- Kotlin package moves are invisible to plugins: only `:termux-shared`, `:terminal-view` and `:terminal-emulator` are published. `:app` is not.

## Review Focus

Each line is an input or condition the spec implies but no existing test exercises. The test that pins it is named in the task shown.

1. **A session is closed and a new one starts in the same refresh.** The new session must get a fresh id; reusing the dead session's id would make selection and rename hit the wrong session. (Task 6)
2. **Wallpaper mode with blur 0, or a radius whose downscale divisor is ≤ 1.** The bitmap must be returned unresampled, not upscaled-then-blurred. (Task 7)
3. **Notification refresh with zero sessions, zero tasks, no wake lock, but the float session running.** The service must not stop. (Task 8)
4. **The wake-lock notification action is tapped when no lock is held** (a double tap). It must be a no-op, not an exception. (Task 9)
5. **The three notification actions after the split.** Exit, wake and float must still target `TermuxService` with `ACTION_STOP_SERVICE`, `ACTION_WAKE_LOCK`/`ACTION_WAKE_UNLOCK` and `ACTION_TOGGLE_FLOAT`. (Task 8)

---

### Task 1: Ledger corrections (F5, F6, F7)

**Files:**
- Modify: `docs/ledger/outstanding-work.md` (§3.1 line ~414, §3.2)

**Interfaces:**
- Consumes: nothing.
- Produces: nothing executable. The spec and this plan are referenced from the ledger.

- [ ] **Step 1: Replace the false claim in §3.1**

Find the bullet beginning `` - `[limitation]` the remaining `:app` classes (`TermuxService`, `` and replace its second sentence (the one claiming there is "no JVM-testable pure logic") with:

```markdown
  inspected 2026-10-27 — most of these are Intent/Service/Activity/Compose glue that delegates to
  `:termux-shared` (`IntentUtils`/`DataUtils`/`FileUtils`/`TermuxFileUtils`). **Corrected
  2026-11-15:** the "no JVM-testable pure logic" claim was wrong. Four pieces are pure and are now
  tracked in the app-layering work (`docs/specs/2026-11-15-app-layering-design.md`):
  `TermuxActivity.refreshSessionsBar` stable-id allocation, `TermuxActivity.downscaleForBlur`,
  `TermuxService.processShellCreateMode`, and `TermuxActivity.moveSessionById` index maths.
```

- [ ] **Step 2: Add the F6 and F7 entries to §3.2**

Append:

```markdown
- `[deferred]` two settings stacks: the legacy Java `PreferenceFragment` screens under
  `app/src/main/java/com/termux/app/fragments/settings/` (13 files) and the Compose appearance
  screen under `ui/appearance/`. Both write preferences, so the settings surface has two owners.
  Consolidation is its own spec cycle; the direction is recorded in
  `docs/plans/2026-10-16-settings-ia.md`. Excluded from the 2026-11-15 app-layering work.
- `[non-goal]` `:termux-shared` depends on `:terminal-view`, and 7 files in `:termux-shared` import
  terminal internals. Inverted relative to a clean layering, but `:termux-shared` is published to
  JitPack and consumed by the plugin apps, so the dependency cannot be removed without breaking
  them. Accepted 2026-11-15.
- `[open]` app layering: `docs/specs/2026-11-15-app-layering-design.md` and
  `docs/plans/2026-11-15-app-layering.md`. Layer packages inside `:app` (`domain`/`data`/`ui`)
  enforced by `LayeringTest`, plus the extraction of session mapping and wallpaper loading from
  `TermuxActivity` and the notification and wake-lock split of `TermuxService`. No new Gradle
  module. Plugin-facing surfaces frozen and checked mechanically.
```

- [ ] **Step 3: Commit**

```bash
git add docs/ledger/outstanding-work.md
git commit -S -m "Changed(docs): Correct the :app testability claim and record the layering work"
```

---

### Task 2: Move the pure models into `domain`

**Files:**
- Move: `app/src/main/java/com/termux/app/ui/sessions/SessionItem.kt` → `app/src/main/java/com/termux/app/domain/sessions/SessionItem.kt`
- Move: `app/src/main/java/com/termux/app/ui/sessions/SessionReorder.kt` → `app/src/main/java/com/termux/app/domain/sessions/SessionReorder.kt`
- Move: `app/src/main/java/com/termux/app/ui/keys/KeySet.kt` → `app/src/main/java/com/termux/app/domain/keys/KeySet.kt`
- Move: `app/src/main/java/com/termux/app/ui/keys/KeySetPresets.kt` → `app/src/main/java/com/termux/app/domain/keys/KeySetPresets.kt`
- Move: `app/src/main/java/com/termux/app/ui/floating/FloatWindowGeometry.kt` → `app/src/main/java/com/termux/app/domain/floating/FloatWindowGeometry.kt`
- Move: `app/src/main/java/com/termux/app/ui/appearance/ColorSchemes.kt` → `app/src/main/java/com/termux/app/domain/appearance/ColorSchemes.kt`
- Create: `app/src/main/java/com/termux/app/domain/appearance/AppearanceModel.kt` (model half of `ui/appearance/AppearanceState.kt`)
- Modify: `app/src/main/java/com/termux/app/ui/appearance/AppearanceState.kt` (keeps only the `AppearanceState` class)
- Move tests to the mirrored packages under `app/src/test/java/com/termux/app/domain/`: `SessionItemMappingTest`, `SessionReorderTest`, `KeySetTest`, `KeySetPresetsTest`, `FloatWindowGeometryTest`, `ColorSchemesTest`

**Interfaces:**
- Consumes: nothing.
- Produces: package `com.termux.app.domain.sessions` (`SessionItem`, `SessionState`, `sessionState(...)`, `sessionTitle(id: Int, name: String?, terminalTitle: String?): String`, `sessionItem(id: Int, name: String?, terminalTitle: String?, running: Boolean, exitCode: Int, active: Boolean): SessionItem`, `TabBounds`, `dropIndex(...)`, `moveItemInPlace(...)`), package `com.termux.app.domain.keys`, package `com.termux.app.domain.floating`, package `com.termux.app.domain.appearance` (`ColorSchemePreset`, `ColorSchemes`, `CursorStyle`, `CursorBlink`, `TerminalColorSet`, `TerminalAppearance`, `parseColor`, `colorToHex`, `colorLabel`, `ANSI_COLOR_COUNT`, `INDEXED_COLOR_COUNT`). Every signature is unchanged; only the package changes.

- [ ] **Step 1: `git mv` each file to its destination and rewrite its `package` line**

`git mv` preserves history. Change `package com.termux.app.ui.sessions` → `package com.termux.app.domain.sessions`, and likewise for `keys`, `floating` and `appearance`.

- [ ] **Step 2: Split `AppearanceState.kt`**

Move to `domain/appearance/AppearanceModel.kt`: `ANSI_COLOR_COUNT`, `INDEXED_COLOR_COUNT`, `CursorStyle`, `CursorBlink`, `TerminalColorSet`, `TerminalAppearance`, `parseColor`, `colorToHex`, `ANSI_COLOR_NAMES`, `colorLabel`. `AppearanceState.kt` keeps only `class AppearanceState` and gains imports for the moved types (explicit names, matching the file's existing import style). No body changes.

- [ ] **Step 3: Fix imports until the module compiles**

Compiler-driven. Known call sites: `app/src/main/java/com/termux/app/TermuxActivity.java:45-46` (`SessionItem`, `SessionItemKt`), plus the `ui/` screens and the moved tests. Java callers of a top-level Kotlin function keep the same `...Kt` class name under the new package.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. This task changes no behavior; a failure means an import or a package line was missed.

- [ ] **Step 5: Commit**

```bash
git add -A app/src
git commit -S -m "Changed(app): Move the pure session, key, float and appearance models into domain"
```

---

### Task 3: Move the persistence into `data`

**Files:**
- Create: `app/src/main/java/com/termux/app/data/appearance/AppearancePreferences.kt` (the `AppearancePreferences` interface and `AppearancePreferenceKeys`, moved out of `ui/appearance/AppearanceStore.kt`)
- Create: `app/src/main/java/com/termux/app/domain/appearance/FontValidation.kt` (`FontError`, `FontValidator`, `ColorPropertiesDocument`, the `ColorPropertiesDocument.color` helper, moved out of `AppearanceStore.kt`)
- Move: the `AppearanceStore` class itself → `app/src/main/java/com/termux/app/data/appearance/AppearanceStore.kt`
- Create: `app/src/main/java/com/termux/app/data/appearance/AndroidAppearancePreferences.kt` (moved out of `ui/appearance/AppearanceActivity.kt:156`)
- Move: `ui/appearance/AppearanceStyling.kt` → `data/appearance/AppearanceStyling.kt`
- Move: `ui/appearance/CursorPreferences.kt` → `data/appearance/CursorPreferences.kt`
- Move: `ui/floating/FloatWindowPrefs.kt` → `data/floating/FloatWindowPrefs.kt`
- Modify: `app/src/main/java/com/termux/app/TermuxActivity.java:49`, `app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java:23-24`, `app/src/main/java/com/termux/app/terminal/TermuxTerminalViewClient.java:34`
- Move tests: `AppearanceStoreTest`, `AppearanceStylingTest`, `CursorPreferencesTest`, `FloatWindowPrefsTest` to the mirrored packages; `ColorHexTest` to `domain/appearance`

**Interfaces:**
- Consumes: `com.termux.app.domain.appearance.*` from Task 2.
- Produces: `com.termux.app.data.appearance.AppearancePreferences`, `AppearancePreferenceKeys`, `AppearanceStore`, `AndroidAppearancePreferences`, `AppearanceStyling`, `CursorPreferences`; `com.termux.app.data.floating.FloatWindowPrefs`, `FloatWindowState`, and `FloatWindowPrefs.create(context: Context): FloatWindowPrefs`. Signatures otherwise unchanged.

- [ ] **Step 1: Move the files and split `AppearanceStore.kt` as listed**

`AppearanceStore` stays in `data` because it reads and writes `colors.properties` and `font.ttf`. The validator, the document model and the hex helpers have no platform dependency and go to `domain`.

- [ ] **Step 2: Move `AndroidAppearancePreferences` out of the Activity file**

It becomes `data/appearance/AndroidAppearancePreferences.kt` with package `com.termux.app.data.appearance`. `AppearanceActivity` imports it and keeps constructing it — that is the composition root.

- [ ] **Step 3: Give `FloatWindowPrefs` a factory and stop `FloatController` reading preferences directly**

In `data/floating/FloatWindowPrefs.kt` add `PREFERENCES_FILE = "termux_float_window"` to the companion and:

```kotlin
companion object {
    fun create(context: Context): FloatWindowPrefs =
        FloatWindowPrefs(context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE))
}
```

In `ui/floating/FloatController.kt` replace the `prefs` initializer (`context.getSharedPreferences(PREFS_NAME, ...)`, line 85) with `FloatWindowPrefs.create(context)` and delete `PREFS_NAME` from the companion at line 332. This removes the last raw `getSharedPreferences` call from `ui/floating`, so Task 5's rule needs no allowlist for it.

- [ ] **Step 4: Fix imports until the module compiles, then run the tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. No behavior change.

- [ ] **Step 5: Commit**

```bash
git add -A app/src
git commit -S -m "Changed(app): Move the appearance and float persistence into data"
```

---

### Task 4: The `FloatController` font-size seam (F4)

**Files:**
- Modify: `app/src/main/java/com/termux/app/ui/floating/FloatController.kt:19` (drop the `TermuxAppSharedPreferences` import), `:60-83` (constructor and view initializer), `:356` (top-level constant)
- Modify: `app/src/main/java/com/termux/app/TermuxService.java:655-660`
- Modify: `app/src/test/java/com/termux/app/ui/floating/FloatControllerTest.kt` (11 construction sites)

**Interfaces:**
- Consumes: `FloatWindowPrefs.create` from Task 3.
- Produces: `class FloatController @JvmOverloads constructor(context: Context, sessionSource: FloatSessionSource, fontSize: Int, canDrawOverlays: () -> Boolean = { ... })`, plus `const val DEFAULT_FONT_SIZE = 14` on `FloatController`'s companion object (moved from the private top-level constant, so Java can read it).

- [ ] **Step 1: Add the parameter and remove the import**

`setTextSize(fontSize)` replaces `setTextSize(TermuxAppSharedPreferences.build(context)?.fontSize ?: DEFAULT_FONT_SIZE)`. Delete the `TermuxAppSharedPreferences` import; the file must no longer mention it.

- [ ] **Step 2: Update the Java caller**

In `getFloatController()`:

```java
TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(this);
int fontSize = preferences != null ? preferences.getFontSize() : FloatController.DEFAULT_FONT_SIZE;
mFloatController = new FloatController(this, this, fontSize);
```

- [ ] **Step 3: Update the 11 test construction sites**

Add `fontSize = 14` to each `FloatController(...)` call. Keep the existing named `canDrawOverlays` argument.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.ui.floating.*"` then `./gradlew :app:testDebugUnitTest`
Expected: PASS. There is no new behavioral test on purpose: asserting the applied text size would need a `getTextSize()` on `:terminal-view`, which the freeze forbids. The dependency is now explicit in the type, so a caller cannot silently read preferences.

- [ ] **Step 5: Commit**

```bash
git add -A app/src
git commit -S -m "Changed(app): Pass the float font size into FloatController"
```

---

### Task 5: `LayeringTest`

**Files:**
- Create: `app/src/test/java/com/termux/app/LayeringTest.kt`

**Interfaces:**
- Consumes: the package layout from Tasks 2–4.
- Produces: `internal data class LayerRule(val name: String, val packagePrefix: String, val forbidden: List<String>, val allowedFiles: Set<String> = emptySet())` and `internal fun findViolations(sources: Map<String, String>, rules: List<LayerRule>): List<String>`, where `sources` maps a source path to its text and each returned string is `"<path>: <forbidden token>"`.

- [ ] **Step 1: Write the failing test**

```kotlin
class LayeringTest {

    @Test
    fun `domain may not import android`() {
        val sources = mapOf(
            "domain/x/A.kt" to "package com.termux.app.domain.x\nimport android.content.Context\n",
        )
        assertEquals(listOf("domain/x/A.kt: android."), findViolations(sources, LayerRules.ALL))
    }

    @Test fun `domain may not import androidx`()   // "import androidx.compose.runtime.getValue"
    @Test fun `domain may not import ui or data`() // "import com.termux.app.ui.ShellHost", "import com.termux.app.data.appearance.AppearanceStore"
    @Test fun `data may not import compose`()      // package com.termux.app.data.x + "import androidx.compose.material3.Text"
    @Test fun `ui may not import the activity, service, application or installer`()
                                                   // package com.termux.app.ui.x + "import com.termux.app.TermuxService"
    @Test fun `ui may not touch SharedPreferences outside an Activity`()
                                                   // package com.termux.app.ui.x + "context.getSharedPreferences(\"p\", 0)"
    @Test fun `an Activity composition root may touch SharedPreferences`()
                                                   // same body, path "ui/appearance/AppearanceActivity.kt", expected emptyList()

    @Test
    fun `the real source tree is clean`() {
        val root = java.io.File("src/main/java")
        val sources = root.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .associate { it.relativeTo(root).path to it.readText() }
        assertEquals(emptyList<String>(), findViolations(sources, LayerRules.ALL))
    }
}
```

Each of the six one-line tests above carries the same two assertions as the first: the expected violation list, and nothing else.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.LayeringTest"`
Expected: FAIL — `findViolations` is unresolved.

- [ ] **Step 3: Implement the scanner and the rules**

`findViolations` matches a rule when the source's declared `package ` line starts with `rule.packagePrefix`, the path is not in `rule.allowedFiles`, and the text contains a forbidden token. Match on the declared package, not the path, so a mis-filed file is caught. Rules:

```kotlin
object LayerRules {
    val ALL = listOf(
        LayerRule("domain", "com.termux.app.domain",
            listOf("android.", "androidx.", "com.termux.app.ui.", "com.termux.app.data.")),
        LayerRule("data", "com.termux.app.data",
            listOf("androidx.compose.", "com.termux.app.ui.")),
        LayerRule("ui", "com.termux.app.ui",
            listOf("com.termux.app.TermuxActivity", "com.termux.app.TermuxService",
                   "com.termux.app.TermuxApplication", "com.termux.app.TermuxInstaller",
                   "getSharedPreferences(", "TermuxAppSharedPreferences", "TermuxPreferenceConstants"),
            allowedFiles = setOf("com/termux/app/ui/appearance/AppearanceActivity.kt")),
    )
}
```

The single allowlist entry is the composition root: `AppearanceActivity` constructs `AndroidAppearancePreferences` and reads the shared font-size defaults, which is what an Activity composition root does.

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.LayeringTest"`
Expected: PASS, all 8 tests. If `the real source tree is clean` fails, the reported file is a missed move from Task 2 or 3 — fix the move, not the rule.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/termux/app/LayeringTest.kt
git commit -S -m "Added(app): Enforce the domain, data and ui package layers with a test"
```

---

### Task 6: Session mapping out of `TermuxActivity` (F2)

**Files:**
- Create: `app/src/main/java/com/termux/app/domain/sessions/SessionSnapshot.kt`
- Create: `app/src/main/java/com/termux/app/domain/sessions/SessionIdAllocator.kt`
- Create: `app/src/main/java/com/termux/app/domain/sessions/SessionItems.kt`
- Create: `app/src/test/java/com/termux/app/domain/sessions/SessionItemsTest.kt`
- Create: `app/src/test/java/com/termux/app/domain/sessions/SessionIdAllocatorTest.kt`
- Modify: `app/src/main/java/com/termux/app/TermuxActivity.java:186-191` (fields), `:801-838` (`refreshSessionsBar`)

**Interfaces:**
- Consumes: `sessionItem(...)` from Task 2.
- Produces:
  - `data class SessionSnapshot(val name: String?, val terminalTitle: String?, val running: Boolean, val exitStatus: Int)`
  - `class SessionIdAllocator { fun idFor(key: Any): Int }` — identity-keyed, ids start at 1 and are never reused.
  - `fun sessionItems(snapshots: List<SessionSnapshot>, ids: List<Int>, activeIndex: Int): List<SessionItem>` — requires `ids.size == snapshots.size`.

- [ ] **Step 1: Write the failing tests**

```kotlin
class SessionIdAllocatorTest {
    @Test fun `an id is stable for the same key`()                 // idFor(a) twice -> same value
    @Test fun `ids are never reused after a key goes away`()       // idFor(a)=1, idFor(b)=2, idFor(a)=1, idFor(c)=3
    @Test fun `equal but distinct keys get distinct ids`()         // two distinct String instances, same content
}

class SessionItemsTest {
    @Test fun `ids survive a reorder`()                            // [A,B] ids [7,8]; [B,A] ids [8,7] -> titles follow the ids
    @Test fun `exactly one item is active`()                       // activeIndex = 1
    @Test fun `no item is active when the index is out of range`() // activeIndex = -1
    @Test fun `the fallback title keeps the stable id`()           // SessionSnapshot(null, null, false, 0) with id 7 -> "Session 7"
    @Test fun `mismatched id and snapshot counts are rejected`()   // require throws IllegalArgumentException
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.domain.sessions.*"`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Implement the three files**

`SessionIdAllocator` holds a `java.util.IdentityHashMap<Any, Int>` and a counter starting at 0, returning `++counter` on first sight. Identity semantics matter: the activity's current `mSessionIds` is an `IdentityHashMap` keyed by `TerminalSession`, and two sessions can compare equal by value while being different sessions.

`sessionItems` maps each snapshot through `sessionItem(id = ids[i], name, terminalTitle, running, exitCode = exitStatus, active = i == activeIndex)`.

- [ ] **Step 4: Run them to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.domain.sessions.*"`
Expected: PASS.

- [ ] **Step 5: Wire `TermuxActivity.refreshSessionsBar`**

Replace the loop body with snapshot collection and one `sessionItems` call. Delete the `mSessionIds`/`mNextSessionId` fields and add `private final SessionIdAllocator mSessionIds = new SessionIdAllocator();`, passing each `terminalSession` object as the key. `mSessionsById` stays: selection callbacks still map a bar id back to a `TermuxSession`. Compute `activeIndex` as the index of `currentSession` in `sessions`, or `-1`.

- [ ] **Step 6: Run the app suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, including `SessionItemMappingTest`.

- [ ] **Step 7: Commit**

```bash
git add -A app/src
git commit -S -m "Changed(app): Extract the sessions-bar mapping from TermuxActivity"
```

---

### Task 7: Wallpaper loading out of `TermuxActivity` (F2)

**Files:**
- Create: `app/src/main/java/com/termux/app/data/WallpaperSource.kt` (interface plus `AndroidWallpaperSource`)
- Create: `app/src/test/java/com/termux/app/data/AndroidWallpaperSourceTest.kt`
- Modify: `app/src/main/java/com/termux/app/TermuxActivity.java:923-1090` (`syncTerminalBackground` and the five helpers)

**Interfaces:**
- Consumes: `BackgroundFx.blurDownscale(Float): Float` (`ui/background/BackgroundFx.kt`, unchanged).
- Produces:

```kotlin
interface WallpaperSource {
    fun systemWallpaper(): Bitmap?
    fun customImage(path: String?): Bitmap?
    fun downscaleForBlur(bitmap: Bitmap?, blurRadius: Float): Bitmap?
}

class AndroidWallpaperSource(
    context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : WallpaperSource
```

- [ ] **Step 1: Write the failing test**

```kotlin
@RunWith(RobolectricTestRunner::class)
class AndroidWallpaperSourceTest {
    @Test fun `downscaling null returns null`()
    @Test fun `a zero radius returns the same bitmap instance`()
    @Test fun `a radius whose divisor is at most one returns the same bitmap instance`()  // e.g. 1f
    @Test fun `api 31 returns the same bitmap instance`()          // sdkInt = 31, radius 20f
    @Test fun `api 30 with a large radius returns a smaller bitmap`() // 100x100 -> width < 100
    @Test fun `a missing custom image path returns null`()          // customImage("/nonexistent/x.png")
    @Test fun `a null custom image path returns null`()
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.data.AndroidWallpaperSourceTest"`
Expected: FAIL — `AndroidWallpaperSource` unresolved.

- [ ] **Step 3: Implement `AndroidWallpaperSource`**

Move the bodies of `loadSystemWallpaperBitmap`, `wallpaperFallbackBitmap`, `drawableToScreenBitmap`, `loadCustomImageBitmap` and `downscaleForBlur` verbatim, with `Build.VERSION.SDK_INT` replaced by the injected `sdkInt` in the blur fallback only. Keep every bitmap option and every `BackgroundFx` call identical — this must be a move, not a rewrite.

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.data.AndroidWallpaperSourceTest"`
Expected: PASS.

- [ ] **Step 5: Wire the activity**

Add `private final WallpaperSource mWallpaperSource = new AndroidWallpaperSource(this);` and replace the five call sites inside `syncTerminalBackground` with `mWallpaperSource.*`. Delete the five private methods. The loader thread, the `runOnUiThread` hop and the `mTerminalBackgroundLoading`/`mTerminalBackgroundLoadedBlur` fields stay in the activity — they are presentation state.

- [ ] **Step 6: Run the app suite and commit**

Run: `./gradlew :app:testDebugUnitTest` — expect PASS.

```bash
git add -A app/src
git commit -S -m "Changed(app): Move wallpaper loading out of TermuxActivity"
```

---

### Task 8: Notification out of `TermuxService` (F3)

**Files:**
- Create: `app/src/main/java/com/termux/app/domain/notifications/ServiceNotificationPolicy.kt`
- Create: `app/src/main/java/com/termux/app/data/notifications/ServiceNotification.kt`
- Create: `app/src/test/java/com/termux/app/domain/notifications/ServiceNotificationPolicyTest.kt`
- Create: `app/src/test/java/com/termux/app/data/notifications/ServiceNotificationTest.kt`
- Modify: `app/src/main/java/com/termux/app/TermuxService.java:868-957` (`buildNotification`, `setupNotificationChannel`, `updateNotification`)

**Interfaces:**
- Consumes: `TERMUX_SERVICE.ACTION_STOP_SERVICE` / `ACTION_WAKE_LOCK` / `ACTION_WAKE_UNLOCK`, `TermuxService.ACTION_TOGGLE_FLOAT` (`:121`), `TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID` / `_CHANNEL_NAME` / `_NOTIFICATION_ID`, `NotificationUtils.geNotificationBuilder`.
- Produces:
  - `fun shouldStopService(wakeLockHeld: Boolean, sessionCount: Int, taskCount: Int, floatRunning: Boolean): Boolean` — true only when all four say nothing is keeping the service alive.
  - `class ServiceNotification(private val context: Context) { fun setupChannel(); fun build(sessionCount: Int, taskCount: Int, wakeLockHeld: Boolean): Notification? }`

- [ ] **Step 1: Write the failing tests**

```kotlin
class ServiceNotificationPolicyTest {
    @Test fun `stops when nothing is alive`()                     // (false, 0, 0, false) -> true
    @Test fun `does not stop while a session runs`()              // (false, 1, 0, false) -> false
    @Test fun `does not stop while a task runs`()                 // (false, 0, 1, false) -> false
    @Test fun `does not stop while the wake lock is held`()       // (true, 0, 0, false) -> false
    @Test fun `does not stop while the float session runs`()      // (false, 0, 0, true) -> false
}

@RunWith(RobolectricTestRunner::class)
class ServiceNotificationTest {
    @Test fun `the notification carries three actions`()          // exit, wake, float
    @Test fun `the notification uses the Termux channel`()        // getChannelId() == TERMUX_APP_NOTIFICATION_CHANNEL_ID
    @Test fun `the wake action flips with the lock state`()       // false -> ACTION_WAKE_LOCK, true -> ACTION_WAKE_UNLOCK
    @Test fun `the session and task counts appear in the text`()  // "2 sessions, 1 task"
}
```

For the action assertions read `Notification.actions` and compare each action's title to the string resources; for the wake action, compare `filterEquals` against a freshly built expected `PendingIntent`. If `PendingIntent` comparison proves brittle under Robolectric, assert the action title and count only and say so in the test comment — do not weaken the policy tests.

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.*.notifications.*"`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Implement both files**

Move the three method bodies verbatim. Every `PendingIntent` keeps `new Intent(context, TermuxService.class).setAction(<same constant>)` and request code `0`. Channel id, channel name, importance `IMPORTANCE_LOW`, notification id, small icon, colour, `setOngoing(true)`, `setShowWhen(false)` and `NOTIFICATION_MODE_SILENT` stay identical.

- [ ] **Step 4: Run them to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.*.notifications.*"`
Expected: PASS.

- [ ] **Step 5: Wire `TermuxService`**

`buildNotification()` becomes `mServiceNotification.build(getTermuxSessionsSize(), mShellManager.mTermuxTasks.size(), wakeLockHeld)`; `setupNotificationChannel()` becomes `mServiceNotification.setupChannel()`; `updateNotification()`'s guard becomes `if (shouldStopService(...)) requestStopService(); else notify(...)`. `mServiceNotification` is a final field initialized at declaration, matching the class's existing style.

- [ ] **Step 6: Run the app suite and commit**

Run: `./gradlew :app:testDebugUnitTest` — expect PASS.

```bash
git add -A app/src
git commit -S -m "Changed(app): Extract the service notification from TermuxService"
```

---

### Task 9: Wake lock out of `TermuxService` (F3)

**Files:**
- Create: `app/src/main/java/com/termux/app/data/wakelock/WakeLockController.kt`
- Create: `app/src/test/java/com/termux/app/data/wakelock/WakeLockControllerTest.kt`
- Modify: `app/src/main/java/com/termux/app/TermuxService.java:104-105` (fields), `:154-170` (dispatch), `:336-388` (the two actions), `:884` and `:945` (the `mWakeLock` reads)

**Interfaces:**
- Consumes: `PermissionUtils.checkIfBatteryOptimizationsDisabled` / `requestDisableBatteryOptimizations`.
- Produces:

```kotlin
class WakeLockController(
    private val context: Context,
    private val onChange: () -> Unit,
    private val requestBatteryOptimizationExemption: () -> Unit = {
        if (!PermissionUtils.checkIfBatteryOptimizationsDisabled(context)) {
            PermissionUtils.requestDisableBatteryOptimizations(context)
        }
    },
) {
    val isHeld: Boolean
    fun acquire()
    fun release(updateNotification: Boolean)
}
```

- [ ] **Step 1: Write the failing test**

```kotlin
@RunWith(RobolectricTestRunner::class)
class WakeLockControllerTest {
    @Test fun `acquire holds the lock and notifies once`()
    @Test fun `acquiring twice notifies once`()                    // second acquire is a no-op
    @Test fun `release drops the lock and notifies`()
    @Test fun `releasing twice does not throw or notify again`()    // Review Focus 4
    @Test fun `release without a notification update does not notify`() // release(updateNotification = false)
}
```

Construct with `requestBatteryOptimizationExemption = {}` so no Intent is started under Robolectric.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.data.wakelock.WakeLockControllerTest"`
Expected: FAIL — unresolved reference.

- [ ] **Step 3: Implement `WakeLockController`**

Move the bodies verbatim: the same `PowerManager.PARTIAL_WAKE_LOCK` tag (`TermuxConstants.TERMUX_APP_NAME.toLowerCase() + ":service-wakelock"`), the same `WifiManager.WIFI_MODE_FULL_HIGH_PERF` tag, the same early-return logging for both directions, and `onChange()` exactly where `updateNotification()` was called (`acquire` always; `release` only when the flag is true).

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.data.wakelock.WakeLockControllerTest"`
Expected: PASS.

- [ ] **Step 5: Wire `TermuxService`**

Replace `mWakeLock`/`mWifiLock` with `private final WakeLockController mWakeLockController = new WakeLockController(this, this::updateNotification);`. `actionAcquireWakeLock()` → `mWakeLockController.acquire()`; `actionReleaseWakeLock(updateNotification)` → `mWakeLockController.release(updateNotification)`; `buildNotification`'s `mWakeLock != null` and `updateNotification`'s `mWakeLock == null` → `mWakeLockController.isHeld()`. The `ACTION_WAKE_LOCK`/`ACTION_WAKE_UNLOCK` dispatch cases keep calling the same two private methods, so the intent contract is untouched.

- [ ] **Step 6: Run the full gate**

Run: `./gradlew test`
Expected: PASS across all four modules.
Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.
Run: `git diff --stat -- termux-shared terminal-emulator terminal-view app/src/main/AndroidManifest.xml gradle.properties`
Expected: no output.

- [ ] **Step 7: Commit**

```bash
git add -A app/src
git commit -S -m "Changed(app): Extract the wake lock from TermuxService"
```

---

### Task 10: Record the outcome and the device checks

**Files:**
- Modify: `docs/ledger/outstanding-work.md` (the `[open]` app-layering entry from Task 1)
- Modify: `docs/ledger/manual-checks.md`

**Interfaces:**
- Consumes: the results of Tasks 1–9.
- Produces: nothing executable.

- [ ] **Step 1: Mark the ledger entry done**

Change the `[open]` app-layering entry to `[done 2026-11-15]` with the commit hashes of Tasks 2–9, the `wc -l` line counts before and after for `TermuxActivity.java` and `TermuxService.java`, and one line naming what is left in each class. If a step was deferred, record it as `[deferred]` with the reason rather than dropping it.

- [ ] **Step 2: Add the device checks to `manual-checks.md`**

Append one row per check, following the file's existing format:

- `termux-api` command through the companion app (a `termux-toast` or `termux-battery-status` call).
- A Termux:Widget shortcut launching a command.
- A Termux:Tasker action, including its result callback.
- A Termux:X11 launch.
- A Termux:Float toggle.
- The notification's Exit, wake-lock and float actions.
- The wake-lock action tapped twice in a row.

- [ ] **Step 3: Commit**

```bash
git add docs/ledger
git commit -S -m "Changed(docs): Record the app layering outcome and its device checks"
```
