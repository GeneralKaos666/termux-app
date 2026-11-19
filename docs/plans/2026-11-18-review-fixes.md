# 2026-11-18 Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix every actionable finding from the 2026-11-18 whole-repo review except device checks and the five rejected claims.

**Architecture:** Eight phases, each a reviewable branch segment: terminal emulator, terminal view, shared library, `:app` correctness, `:app` polish, build/CI/publication, docs, final verification. Every task is independently testable and commits on its own.

**Tech Stack:** Kotlin 2.4.21, Java 17, Compose, AGP 9.1.1, Gradle 9.3.1, JUnit 4, Robolectric 4.17, GitHub Actions, JitPack.

**Spec:** None. The findings list from the 2026-11-18 review is the input; this plan is the source of truth. Line numbers are as of commit `76495368` plus the working-tree ledger edit.

## Global Constraints

- `minSdkVersion=23`, `targetSdkVersion=28`, `compileSdkVersion=37`, `ndkVersion=29.0.14206865` — from `gradle.properties`; do not change them.
- Java source/target 17 with core library desugaring. No `java.nio.file` on a path reachable below API 26; `FileUtils.java:1099` keeps its `SDK_INT >= O` guard.
- Package identity `com.termux` and the companion names in `TermuxConstants` are frozen.
- No new dependency. No `commons-io`.
- Commit types are exactly `Added`, `Changed`, `Deprecated`, `Removed`, `Fixed`, `Security`, capitalized, present tense, with a scope: `Fixed(terminal): …`.
- Every task ends with `./gradlew test` green for the modules it touched. `:app:lintDebug` must not gain new issues.
- Do not touch device checks (D-13/D-14/D-15) or the five rejected claims: `copyInterval` wide-char throw, `TermuxOpenReceiver.query`, `BackupRunner` blocking, `TextSelectionCursorController.onDetached`, OSC 20/21 `IconLabel`.

## Review Focus

1. A settings DataStore still holds an Activity context after the `applicationContext` change — a leak that lint reports once and hides afterwards.
2. The `tar` scan rejects legitimate GNU tar entries (pax headers, sparse files) and breaks restore for real backups.
3. Removing the OSC colon guard changes OSC 1337 image handling — the fast path and the image cap regress.
4. The `RowInvalidation` band change clips the top row or over-invalidates the whole view on every scroll.
5. The release workflow change lets one matrix leg delete the other's assets, or skips the tag/version check and ships a mislabelled APK.

Each line has a test or an explicit verification step in the task that owns the code (Tasks 24, 26b, 1, 7, 26).

---

## Phase 0 — Baseline

### Task 0: Branch and baseline

**Files:** none.

- [ ] **Step 1: Create the branch**

```bash
git switch -c fix/review-2026-11-18
```

- [ ] **Step 2: Record the baseline**

Run: `./gradlew test --console=plain`
Expected: `BUILD SUCCESSFUL`. Known counts: `:app` 530, `:terminal-view` 135, `:terminal-emulator` 377.

- [ ] **Step 3: Record the lint baseline**

Run: `./gradlew :app:lintDebug --console=plain`
Expected: `BUILD SUCCESSFUL`, 74 issues, 0 errors. Save the issue list: `python3 - <<'EOF'` block used in the review, or keep `app/build/reports/lint-results-debug.txt`.

No commit.

---

## Phase 1 — `:terminal-emulator`

### Task 1: Parse the OSC type from the separator, not a colon

**Files:**
- Modify: `terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java:2468-2471`
- Test: `terminal-emulator/src/test/java/com/termux/terminal/OperatingSystemControlTest.java`

**Interfaces:**
- Consumes: `TerminalTestCase.clipboardPuts` (list of strings passed to `onCopyTextToClipboard`).
- Produces: nothing new; `mOscType` semantics become `-1` = unparsed, `-2` = unknown, `>= 0` = type.

- [ ] **Step 1: Write the failing test**

Add to `OperatingSystemControlTest` (plain JUnit3; reflection avoids `android.util.Base64`, which is not available in the JVM test runtime):

```java
public void testOsc52RaisesTheArgumentCap() throws Exception {
    withTerminalSized(10, 10);
    enterString("\033]52;c;");
    Field field = TerminalEmulator.class.getDeclaredField("mTerminalControlArgsMaxLength");
    field.setAccessible(true);
    assertTrue("OSC 52 must raise the args cap above the 16384 default",
        (int) field.get(mTerminal) > 16384);
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :terminal-emulator:test --tests "com.termux.terminal.OperatingSystemControlTest"`
Expected: FAIL — the cap stays at 16384 because the args contain no `:`.

- [ ] **Step 3: Implement**

In `setOscTypeVariables()`, change the early-return guard to `if (mOscType != -1) return;` and delete `if (mTerminalControlArgs.indexOf(":") < 0) return;`. The existing loop already parses digits incrementally and sets `-2` on the first unknown character, so a colon-free OSC now gets its cap and stops being re-parsed per character.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :terminal-emulator:test`
Expected: PASS, including `OperatingSystemControlTest` titles, colors, OSC 8 links, and the image tests.

- [ ] **Step 5: Commit**

```bash
git add terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java \
        terminal-emulator/src/test/java/com/termux/terminal/OperatingSystemControlTest.java
git commit -m "Fixed(terminal): Apply OSC argument caps without a colon in the payload"
```

### Task 2: Clamp `CSI n S` like `CSI n T`

**Files:**
- Modify: `terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java:2035-2039`

- [ ] **Step 1: Implement**

In `case 'S'`, clamp before the loop exactly as `case 'T'` does at `:2044-2047`:

```java
final int linesBetweenTopAndBottomMargins = mBottomMargin - mTopMargin;
final int linesToScroll = Math.min(linesBetweenTopAndBottomMargins, getArg0(1));
```

- [ ] **Step 2: Verify**

Run: `./gradlew :terminal-emulator:test`
Expected: PASS. No new test: a 9999-line scroll of a 10-row screen and a 10-line scroll leave the same buffer, so a unit test cannot distinguish the clamp. The gate is `ScrollRegionTest` plus the suite.

- [ ] **Step 3: Commit**

```bash
git commit -am "Changed(terminal): Clamp CSI S to the scroll region height"
```

### Task 3: Copy only the interval in `copyInterval`

**Files:**
- Modify: `terminal-emulator/src/main/java/com/termux/terminal/TerminalRow.java:86`

- [ ] **Step 1: Implement**

Replace `Arrays.copyOf(line.mText, line.mText.length)` with a copy of `line.mSpaceUsed` characters. Keep the `this == line` branch (it exists to survive in-place overlap) and the existing `for (int i = x1; i < x2; )` loop unchanged.

- [ ] **Step 2: Verify**

Run: `./gradlew :terminal-emulator:test`
Expected: PASS, especially `TerminalRowTest.testNormalCharsWithDoubleDisplayWidthOverlapping` and `testCopyIntervalCarriesUnderlineStyle`.

- [ ] **Step 3: Commit**

```bash
git commit -am "Changed(terminal): Copy only the used row text in copyInterval"
```

### Task 4: Clear full rows in one pass in `blockSet`

**Files:**
- Modify: `terminal-emulator/src/main/java/com/termux/terminal/TerminalBuffer.java:641-654`

- [ ] **Step 1: Implement**

When `val == ' '` and `w == mColumns`, call `allocateFullLineIfNecessary(row).clear(style)` for each row instead of the per-cell `setChar` loop. Keep the `sx + w == mColumns` `clearLineWrap` behaviour.

- [ ] **Step 2: Verify**

Run: `./gradlew :terminal-emulator:test`
Expected: PASS, especially `ScreenBufferTest`, `ControlSequenceIntroducerTest`, `DirtyTrackingTest`.

- [ ] **Step 3: Commit**

```bash
git commit -am "Changed(terminal): Clear full rows in one pass in blockSet"
```

### Task 5: Bound the surrogate read in `GraphemeClusters`

**Files:**
- Modify: `terminal-emulator/src/main/java/com/termux/terminal/GraphemeClusters.java:126`
- Test: `terminal-emulator/src/test/java/com/termux/terminal/GraphemeClustersTest.java`

- [ ] **Step 1: Write the failing test**

```java
public void testLoneHighSurrogateAtEndIsNotReadPastSpaceUsed() {
    char[] text = new char[] { 'a', '\uD800' };
    assertEquals(2, GraphemeClusters.clusterEnd(text, 1, 2));
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :terminal-emulator:test --tests "com.termux.terminal.GraphemeClustersTest"`
Expected: FAIL if the unguarded read yields a different end index, or the test documents current behaviour if the stale char is not a low surrogate.

- [ ] **Step 3: Implement**

In `codePointAt`, treat a high surrogate at `index + 1 >= spaceUsed` as a single char; pass `spaceUsed` into the helper or guard with the caller's bound.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :terminal-emulator:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -am "Fixed(terminal): Do not read past spaceUsed for a lone surrogate"
```

### Task 6: Release JNI local references and arrays on failure

**Files:**
- Modify: `terminal-emulator/src/main/jni/termux.c:136-165`

- [ ] **Step 1: Implement**

`DeleteLocalRef` each `arg_java_string` / `env_java_string` after `ReleaseStringUTFChars`. On a `GetStringUTFChars` failure, free every `argv[i]`/`envp[i]` already allocated plus the array, then throw.

- [ ] **Step 2: Verify**

Run: `./gradlew :terminal-emulator:test`
Expected: PASS (JNI is not exercised by unit tests). The native build cannot run on this `aarch64` host (ledger §3.2); CI `assembleDebug` is the compile gate. State this in the commit body.

- [ ] **Step 3: Commit**

```bash
git commit -am "Fixed(terminal): Release JNI local references and arrays in create_subprocess"
```

---

## Phase 2 — `:terminal-view`

### Task 7: Include the row origin in the invalidation band

**Files:**
- Modify: `terminal-view/src/main/java/com/termux/view/RowInvalidation.kt:105-113`
- Test: `terminal-view/src/test/java/com/termux/view/RowInvalidationTest.java:44-64`

**Interfaces:**
- Produces: `decide(..., fontLineSpacing, fontLineSpacingAndAscent, ...)` — the new parameter is `fontLineSpacingAndAscent: Int`, threaded from `TerminalView.onScreenUpdated` (`TerminalView.kt:528-533`).

- [ ] **Step 1: Update the test to the corrected band**

In `RowInvalidationTest`, pass the new argument (use `4` for the existing spacing-10 cases) and expect `topPx = 4 + firstDirty*10`, `bottomPx = 4 + (lastDirty+1)*10` capped at `viewHeight`. For the existing row-2 case at `:50-52`: `topPx 24`, `bottomPx 54`.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :terminal-view:test --tests "com.termux.view.RowInvalidationTest"`
Expected: FAIL — `decide` has no such parameter.

- [ ] **Step 3: Implement**

Add `fontLineSpacingAndAscent` to `decide` and set `topPx = fontLineSpacingAndAscent + firstDirty * fontLineSpacing`, `bottomPx = fontLineSpacingAndAscent + (lastDirty + 1) * fontLineSpacing`. Update the one caller in `TerminalView.onScreenUpdated` to pass `mRenderer!!.mFontLineSpacingAndAscent`.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :terminal-view:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add terminal-view/src/main/java/com/termux/view/RowInvalidation.kt \
        terminal-view/src/main/java/com/termux/view/TerminalView.kt \
        terminal-view/src/test/java/com/termux/view/RowInvalidationTest.java
git commit -m "Fixed(terminal-view): Offset the row invalidation band by the row origin"
```

### Task 8: Repaint when the text size changes without a grid change

**Files:**
- Modify: `terminal-view/src/main/java/com/termux/view/TerminalView.kt:585-590`

- [ ] **Step 1: Implement**

Call `invalidate()` after `updateSize()` in `setTextSize`, matching `setTypeface` at `:593-598`.

- [ ] **Step 2: Verify**

Run: `./gradlew :terminal-view:test`
Expected: PASS. No test host for the View; the change is one line and mirrors an existing method.

- [ ] **Step 3: Commit**

```bash
git commit -am "Fixed(terminal-view): Repaint after a text size change"
```

### Task 9: Stop the cursor loop when the view detaches

**Files:**
- Modify: `terminal-view/src/main/java/com/termux/view/TerminalView.kt:1748-1763`

- [ ] **Step 1: Implement**

Call `stopTerminalCursorBlinker()` at the top of `onDetachedFromWindow`, before the observer unregister. It already sets `mCursorAlpha = 1f`, clears the glide and posts a repaint only when it was animating.

- [ ] **Step 2: Verify**

Run: `./gradlew :terminal-view:test`
Expected: PASS. No test host; verify by reading that `TerminalCursorBlinkerRunnable` can no longer re-post (`:1638` requires `mCursorBlinkRunning || mCursorEasing`, both false after the call).

- [ ] **Step 3: Commit**

```bash
git commit -am "Fixed(terminal-view): Stop the cursor frame loop when the view detaches"
```

### Task 10: Keep the blink phase when the animator scale changes

**Files:**
- Modify: `terminal-view/src/main/java/com/termux/view/TerminalView.kt:1179-1190`, `:1561-1620`

**Interfaces:**
- Produces: `TerminalCursorBlinkerRunnable.setBlinkScale(scale: Float)` — the runnable owns its scale and rebases its start time; `onAnimatorScaleChanged` calls it when the runnable exists.

- [ ] **Step 1: Implement**

Make `mBlinkStartUptimeMs` a `var`, add `private var mBlinkScale = 1f`, and replace the frame code's read of `mAnimatorDurationScale` with `mBlinkScale`. Add:

```kotlin
fun setBlinkScale(scale: Float) {
    val now = SystemClock.uptimeMillis()
    val oldPeriod = (2.0 * mBlinkRate * mBlinkScale).toLong().coerceAtLeast(1L)
    val newPeriod = (2.0 * mBlinkRate * scale).toLong().coerceAtLeast(1L)
    val phase = ((now - mBlinkStartUptimeMs) % oldPeriod + oldPeriod) % oldPeriod
    mBlinkStartUptimeMs = now - (phase.toFloat() / oldPeriod * newPeriod).toLong()
    mBlinkScale = scale
}
```

In `onAnimatorScaleChanged`, after `mAnimatorDurationScale = readAnimatorDurationScale()`, call `mTerminalCursorBlinkerRunnable?.setBlinkScale(mAnimatorDurationScale)` before the start/stop branch.

- [ ] **Step 2: Verify**

Run: `./gradlew :terminal-view:test`
Expected: PASS. `CursorFxTest` already pins `blinkAlpha`; the phase continuity itself needs the View, so record it as a manual check in the ledger entry (no device run in this plan).

- [ ] **Step 3: Commit**

```bash
git commit -am "Fixed(terminal-view): Preserve the cursor blink phase across a scale change"
```

### Task 11: Do not reuse the dispatched MotionEvent across fling frames

**Files:**
- Modify: `terminal-view/src/main/java/com/termux/view/TerminalView.kt:274-306`

- [ ] **Step 1: Implement**

Capture only what `doScroll` needs from `e` (the coordinates and source) as plain values before posting the runnable, or call `MotionEvent.obtain(e)` and `recycle()` it when the runnable ends. Also replace the two `mEmulator!!` dereferences inside `run()` with a local `val emulator = mEmulator ?: return`.

- [ ] **Step 2: Verify**

Run: `./gradlew :terminal-view:test`
Expected: PASS. Fling behaviour is device-only; note it in the commit body.

- [ ] **Step 3: Commit**

```bash
git commit -am "Fixed(terminal-view): Stop reusing the dispatched event across fling frames"
```

---

## Phase 3 — `:termux-shared`

### Task 12: Match the directory itself in `isPathInDirPaths`

**Files:**
- Modify: `termux-shared/src/main/java/com/termux/shared/file/FilePaths.java:139-142`
- Test: `termux-shared/src/test/java/com/termux/shared/file/FilePathsTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
public void pathEqualToDirPathIsInDirPathWhenNotEnsuringUnder() {
    assertTrue(FilePaths.isPathInDirPath("/a/b", "/a/b", false));
    assertFalse(FilePaths.isPathInDirPath("/a/b", "/a/b", true));
}

@Test
public void pathUnderDirPathIsInDirPath() {
    assertTrue(FilePaths.isPathInDirPath("/a/b/c", "/a/b", false));
    assertTrue(FilePaths.isPathInDirPath("/a/b/c", "/a/b", true));
    assertFalse(FilePaths.isPathInDirPath("/a/bc", "/a/b", false));
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :termux-shared:test --tests "com.termux.shared.file.FilePathsTest"`
Expected: FAIL on the first assertion.

- [ ] **Step 3: Implement**

```java
isPathInDirPaths = path.equals(normalizedDirPath) || path.startsWith(normalizedDirPath + "/");
if (ensureUnder) isPathInDirPaths = isPathInDirPaths && !path.equals(normalizedDirPath);
```

Keep the fail-closed `catch` around canonicalization.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :termux-shared:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add termux-shared/src/main/java/com/termux/shared/file/FilePaths.java \
        termux-shared/src/test/java/com/termux/shared/file/FilePathsTest.java
git commit -m "Fixed(shared): Treat a path equal to its directory as inside it"
```

### Task 13: Split and extend the instrumented suite

**Files:**
- Modify: `termux-shared/src/androidTest/java/com/termux/shared/file/tests/FileUtilsTests.java`
- Delete: `termux-shared/src/androidTest/java/com/termux/shared/ExampleInstrumentedTest.java`
- Modify: `app/build.gradle:48-50`, `terminal-view/build.gradle:52-54` (remove `androidTestImplementation` lines; neither module has `src/androidTest`)

- [ ] **Step 1: Split the single test**

Turn `testFileUtils()` (`:29`) into one `@Test` per operation group: `createFiles`, `copyFiles`, `moveFiles`, `deleteFiles`, `emptyDirectory`, `symlinks`. Keep the shared setup in a `@Before` that creates `testRootDirectoryPath` and a `@After` that deletes it.

- [ ] **Step 2: Add the missing cases**

Add: `isPathInDirPath` equality (the Task 12 case), `validateDirectoryFileExistenceAndPermissions` when `filePath` equals `parentDirPath`, `readSerializableObjectFromFile` round-trip, and `copyDirectoryRecursive` over a symlink loop (assert it returns an error instead of recursing).

- [ ] **Step 3: Verify**

Run: `./gradlew :termux-shared:assembleDebugAndroidTest`
Expected: `BUILD SUCCESSFUL`. Execution needs a device (`emulator-5554` is offline); the run is out of scope per the plan.

- [ ] **Step 4: Commit**

```bash
git add termux-shared/src/androidTest app/build.gradle terminal-view/build.gradle
git commit -m "Changed(shared): Split the instrumented FileUtils suite and cover path equality"
```

---

## Phase 4 — `:app` correctness

### Task 14: Configure preferences on the main thread

**Files:**
- Modify: `app/src/main/java/com/termux/app/activities/SettingsActivity.kt:57-66`

- [ ] **Step 1: Implement**

Delete the `Thread { … }.start()` wrapper and call the six `configure*` methods directly. Keep the `Thread` inside `configureAboutPreference` (`:119`): that one builds the report off-main and is correct.

- [ ] **Step 2: Verify**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. There is no test host for `PreferenceFragmentCompat`; the gate is compile plus lint (`FrequentlyChangingValue`/threading has no lint rule, so read the diff).

- [ ] **Step 3: Commit**

```bash
git commit -am "Fixed(app): Configure the settings preferences on the main thread"
```

### Task 15: Copy a received file off the main thread

**Files:**
- Modify: `app/src/main/java/com/termux/app/api/file/FileReceiverActivity.kt:174-200`, `:226-260`

**Interfaces:**
- Consumes: `AppCompatActivity` (`:37`), so `lifecycleScope` is available.
- Produces: `saveStreamWithName` stays public and synchronous; add `saveStreamWithNameAsync(inputStream, name, onResult: (File?) -> Unit)`.

- [ ] **Step 1: Implement**

Add `saveStreamWithNameAsync` that runs `saveStreamWithName` on `Dispatchers.IO` inside `lifecycleScope.launch` and calls `onResult` on `Dispatchers.Main`. Change the two call sites (`:179`, `:204`) to use it, moving the editor-intent code into the callback. Guard with `if (isFinishing || isDestroyed) return@launch`.

- [ ] **Step 2: Verify**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. No unit test host for the activity; the change is mechanical and lint must stay at 74 issues.

- [ ] **Step 3: Commit**

```bash
git commit -am "Fixed(app): Save a shared file without blocking the main thread"
```

### Task 16: Move the backup UI text into resources

**Files:**
- Create: `app/src/main/java/com/termux/app/ui/backup/BackupStrings.kt`
- Create: `app/src/main/java/com/termux/app/ui/backup/AndroidBackupStrings.kt`
- Modify: `app/src/main/java/com/termux/app/ui/backup/BackupViewModel.kt:29-40`, `:70`, `:191-218`, `:282`
- Modify: `app/src/main/java/com/termux/app/ui/backup/BackupActivity.kt:75-85`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/termux/app/ui/backup/BackupViewModelTest.kt`

**Interfaces:**
- Produces:

```kotlin
interface BackupStrings {
    fun transferRunning(): String
    fun created(name: String?, size: String?, packages: Int?): String
    fun restored(name: String?, safety: String?): String
    fun deleted(name: String?): String
    fun pruned(removed: Int?, kept: Int?): String
    fun verified(name: String?, valid: Boolean?): String
    fun commandFailed(): String
}
```

`AndroidBackupStrings(resources: Resources)` implements it with `resources.getString`. `BackupViewModel` takes `strings: BackupStrings` as its fifth constructor parameter.

- [ ] **Step 1: Add the strings**

Add `backup_transfer_running`, `backup_created_detail`, `backup_created`, `backup_restored_detail`, `backup_restored_safety`, `backup_restored`, `backup_deleted_detail`, `backup_deleted`, `backup_pruned_detail`, `backup_pruned`, `backup_verified_detail`, `backup_verification_failed`, `backup_verified`, `backup_command_failed` with the exact current text and positional args (`%1$s`, `%1$d`, `%2$s`).

- [ ] **Step 2: Write the failing test**

Add a `FakeBackupStrings : BackupStrings` whose methods return marker strings (`"MARKER transfer running"`, `"MARKER command failed"`, …). Pass it from the existing `viewModel(...)` helper in `BackupViewModelTest`, and change the three existing message assertions (`:77`, `:115`, `:273`) to the matching markers, e.g.:

```kotlin
assertEquals("MARKER transfer running", viewModel.state.value.message)
```

This proves the ViewModel uses the injected strings and no literal of its own.

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.ui.backup.BackupViewModelTest"`
Expected: FAIL to compile — the constructor has no `strings` parameter.

- [ ] **Step 4: Implement**

Thread `strings` through the ViewModel, replace every literal, and pass `AndroidBackupStrings(resources)` from `BackupActivity`. Delete `formatBytes` from the ViewModel and the screen; add `app/src/main/java/com/termux/app/domain/backup/ByteFormat.kt`:

```kotlin
fun formatBytes(bytes: Long): String
```

with `String.format(Locale.US, "%.1f %s", value, units[unit])`. Add `ByteFormatTest` asserting `0 → "0 B"`, `1024 → "1.0 KiB"`, `1536 → "1.5 KiB"`.

- [ ] **Step 5: Run the tests and commit**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

```bash
git add app/src/main/java/com/termux/app/ui/backup app/src/main/java/com/termux/app/domain/backup \
        app/src/main/res/values/strings.xml app/src/test/java/com/termux/app
git commit -m "Changed(app): Move backup UI text into string resources"
```

### Task 17: Use plurals for counted strings

**Files:**
- Modify: `app/src/main/res/values/strings.xml:407`, `:465`
- Modify: `app/src/main/java/com/termux/app/ui/snippets/SnippetsScreen.kt:84`
- Modify: `app/src/main/java/com/termux/app/ui/backup/BackupScreen.kt:552`

- [ ] **Step 1: Implement**

Replace both `<string>` elements with `<plurals>` carrying `one` and `other` (`"At most %1$d snippet is kept."` / `"At most %1$d snippets are kept."`, `"%1$d package"` / `"%1$d packages"`). Replace `stringResource(R.string.…)` with `pluralStringResource(R.plurals.…, count, count)`.

- [ ] **Step 2: Verify**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. Lint `PluralsCandidate` disappears on the next `:app:lintDebug`.

- [ ] **Step 3: Commit**

```bash
git commit -am "Fixed(app): Use plurals for snippet and package counts"
```

---

## Phase 5 — `:app` polish

### Task 18: Read the scroll-fade state in the draw phase

**Files:**
- Modify: `app/src/main/java/com/termux/app/ui/sessions/SessionsBar.kt:266-290`

- [ ] **Step 1: Implement**

Delete the composition-phase `atStart`, `atEnd`, `scrollFraction` reads and compute them inside `Modifier.drawWithContent { … }`, immediately before the two `edgeFadeAlpha` calls. `drawWithContent` is a draw-phase lambda, so a scroll now triggers a redraw without recomposing `SessionsBar`.

- [ ] **Step 2: Verify**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. `:app:lintDebug` loses the two `FrequentlyChangingValue` warnings.

- [ ] **Step 3: Commit**

```bash
git commit -am "Changed(app): Read the sessions fade state in the draw phase"
```

### Task 19: Do not leading-align the active tab on first layout

**Files:**
- Modify: `app/src/main/java/com/termux/app/ui/sessions/SessionsBar.kt:148-172`

- [ ] **Step 1: Add the helper stub and the failing test**

Add to `SessionsBar.kt`:

```kotlin
internal fun minimalScrollDelta(tabOffset: Int, tabSize: Int, viewportStart: Int, viewportEnd: Int): Int = 0
```

In `SessionsBarLayoutTest`, add cases: a fully visible tab returns 0; a tab starting before the viewport returns the negative leading overlap; a tab ending past the viewport returns the positive trailing overlap; a tab wider than the viewport returns the leading overlap.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.ui.sessions.SessionsBarLayoutTest"`
Expected: FAIL on the three non-zero cases.

- [ ] **Step 3: Implement**

Fill in the delta maths: `0` when `tabOffset >= viewportStart` and `tabOffset + tabSize <= viewportEnd`, the negative leading overlap otherwise, and the positive trailing overlap when past the end. Key the effect on `activeIndex` and `listState.layoutInfo.totalItemsCount`, return while the count is 0, and call `listState.animateScrollBy(minimalScrollDelta(...).toFloat())` when the delta is non-zero. Delete the `animateScrollToItem(activeIndex)` fallback.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -am "Fixed(app): Keep the active session tab in view without leading-aligning it"
```

### Task 20: Key the palette rows and gate the restore handle

**Files:**
- Modify: `app/src/main/java/com/termux/app/ui/ShellHost.kt:216-220`, `:374-390`

- [ ] **Step 1: Implement**

Change the `remember` key from `sessionsState.sessions` to `sessionsState.sessions.map { Triple(it.id, it.title, it.active) }`. For the restore handle, guard the callback: `onExpand = { if (!firstRunOverlayVisible) sessionsBarExpanded = true }`, so a click during the 80 ms exit cannot expand the bar behind the first-run overlay.

- [ ] **Step 2: Verify**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git commit -am "Changed(app): Key palette rows structurally and gate the restore handle"
```

### Task 21: Drop the pager height state and the dead font-size constant

**Files:**
- Modify: `app/src/main/java/com/termux/app/ui/keys/ExtraKeysBar.kt:186-190`
- Modify: `app/src/main/java/com/termux/app/ui/floating/FloatController.kt:360`

- [ ] **Step 1: Implement**

Replace `val pagerHeight by animateDpAsState(..., snap())` with `val pagerHeight = pagerHeightTarget`. Delete the top-level `private const val DEFAULT_FONT_SIZE` at `FloatController.kt:360`; `TermuxService.kt:612` uses the companion constant.

- [ ] **Step 2: Verify**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git commit -am "Changed(app): Remove the snapped pager height state and a dead constant"
```

### Task 22: Make the backup script owner-only

**Files:**
- Modify: `app/src/main/java/com/termux/app/data/backup/BackupScriptProvisioner.kt:196-214`

- [ ] **Step 1: Implement**

Use `rwx------` in the `PosixFilePermissions` branch, `0x1C0` (0700) in the `Os.chmod` branch, and `setReadable(true, true)` / `setExecutable(true, true)` in the fallback. The shell runs under the app's own uid, so world bits buy nothing.

- [ ] **Step 2: Verify**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. Add a case to `BackupScriptProvisionerTest` (`app/src/test/java/com/termux/app/data/backup/BackupScriptProvisionerTest.kt`), e.g. `installed script is owner-only`, asserting `Files.getPosixFilePermissions(script.toPath())` equals `{OWNER_READ, OWNER_WRITE, OWNER_EXECUTE}`. Adapt the fixture to the existing sandbox helpers in that file.

- [ ] **Step 3: Commit**

```bash
git commit -am "Security(app): Install the backup script with owner-only permissions"
```

### Task 23: Add PendingIntent mutability flags

**Files:**
- Modify: `app/src/main/java/com/termux/app/data/notifications/ServiceNotification.kt:35`, `:75`, `:85`, `:92`

- [ ] **Step 1: Implement**

Add `PendingIntent.FLAG_IMMUTABLE` to the four `getActivity`/`getService` calls. If a click needs to mutate extras, use `FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE` and put the data in the intent before creation.

- [ ] **Step 2: Verify**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS; `:app:lintDebug` loses the four `UnspecifiedImmutableFlag` warnings.

- [ ] **Step 3: Commit**

```bash
git commit -am "Security(app): Mark notification PendingIntents immutable"
```

### Task 24: Hold the application context in the settings DataStores

**Files:**
- Modify: the ten `*PreferencesDataStore` declarations, one per fragment file:
  `app/src/main/java/com/termux/app/fragments/settings/TermuxAPIPreferencesFragment.kt:32`,
  `TermuxFloatPreferencesFragment.kt:38`, `TermuxPreferencesFragment.kt:124`,
  `TermuxTaskerPreferencesFragment.kt:32`, `TermuxWidgetPreferencesFragment.kt:32`,
  `termux/DebuggingPreferencesFragment.kt:84`, `termux/DisplayPreferencesFragment.kt:185`,
  `termux/KeyboardPreferencesFragment.kt:74-88`,
  `termux_api/DebuggingPreferencesFragment.kt:48`, `termux_float/DebuggingPreferencesFragment.kt:48`,
  `termux_tasker/DebuggingPreferencesFragment.kt:48`, `termux_widget/DebuggingPreferencesFragment.kt:48`
- Test: `app/src/test/java/com/termux/app/fragments/settings/termux/PreferenceDataStoresTest.kt`

**Interfaces:**
- Produces: every `getInstance(context)` stores `context.applicationContext` in `mContext` and passes it to the `TermuxAppSharedPreferences.build` call.

- [ ] **Step 1: Write the failing test**

In `PreferenceDataStoresTest`:

```kotlin
@Test
fun `stores hold the application context`() {
    clearInstance(KeyboardPreferencesDataStore::class.java)
    val wrapper = ContextWrapper(RuntimeEnvironment.getApplication())
    val store = KeyboardPreferencesDataStore.getInstance(wrapper)
    val field = KeyboardPreferencesDataStore::class.java.getDeclaredField("mContext")
    field.isAccessible = true
    assertSame(wrapper.applicationContext, field.get(store))
}
```

`ContextWrapper.applicationContext` returns the base application context, so the test proves the coercion without an Activity.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.fragments.settings.termux.PreferenceDataStoresTest"`
Expected: FAIL — the stored context is the activity.

- [ ] **Step 3: Implement**

In every `getInstance`, coerce once: `val appContext = context.applicationContext`, construct with it, and keep `mContext`/`mPreferences` built from it. Do not change the public signature.

- [ ] **Step 4: Run the tests and commit**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS; `:app:lintDebug` loses all 12 `StaticFieldLeak` warnings.

```bash
git add app/src/main/java/com/termux/app/fragments/settings app/src/test/java/com/termux/app
git commit -m "Fixed(app): Keep the application context in the settings data stores"
```

### Task 25: Clear the remaining resource and lint nits

**Files:**
- Modify: `app/src/main/res/values/strings.xml:131` (delete `appearance_color_picker_title`)
- Move: `app/src/main/res/drawable/banner.png` → `app/src/main/res/drawable-nodpi/banner.png`
- Modify: `app/src/main/AndroidManifest.xml:50`, `:56`, `:94`
- Modify: `app/src/main/java/com/termux/app/ui/floating/FloatController.kt:69`
- Modify: `app/src/main/java/com/termux/app/ui/keys/ExtraKeysBar.kt:110`, `:341`, `app/src/main/java/com/termux/app/ui/sessions/SessionsBar.kt:120`, `:461`
- Modify: `app/src/main/java/com/termux/app/fragments/settings/TermuxPreferencesFragment.kt:69`
- Modify: `app/src/main/java/com/termux/app/data/backup/SafBackupTransfer.kt:49`

- [ ] **Step 1: Implement**

Delete the unused string and the two `RedundantLabel` attributes; move `banner.png` to `drawable-nodpi`; remove the `SDK_INT` checks lint proves unreachable (`AndroidManifest.xml:50`, `FloatController.kt:69`); move the `modifier` parameter to the first optional position in the four Compose functions; change `SHOW_AS_ACTION_ALWAYS` to `SHOW_AS_ACTION_IF_ROOM`; in `SafBackupTransfer`, use `StorageManager.getAllocatableBytes` above API 26 with the current `usableSpace` as the fallback.

- [ ] **Step 2: Verify**

Run: `./gradlew :app:testDebugUnitTest :app:lintDebug`
Expected: PASS. No `UnusedResources`, `IconLocation`, `ObsoleteSdkInt`, `ModifierParameter`, `RedundantLabel`, `AlwaysShowAction`, or `UsableSpace` entries remain; the count falls by the number of those issues.

- [ ] **Step 3: Commit**

```bash
git add app/src/main
git commit -m "Changed(app): Clear the remaining resource and lint nits"
```

---

### Task 25b: Split the session tab composables out of `SessionsBar`

**Files:**
- Create: `app/src/main/java/com/termux/app/ui/sessions/SessionTab.kt`
- Create: `app/src/main/java/com/termux/app/ui/sessions/SessionActionsMenu.kt`
- Create: `app/src/main/java/com/termux/app/ui/sessions/SessionStateDot.kt`
- Modify: `app/src/main/java/com/termux/app/ui/sessions/SessionsBar.kt`

**Interfaces:**
- Produces: `internal fun SessionTab(...)`, `internal fun SessionActionsMenu(...)`, `internal fun SessionStateDot(...)` in package `com.termux.app.ui.sessions`, with today's parameters unchanged. `SessionsBar`, `SessionsRestoreHandle`, `edgeFadeAlpha`, `starterLabel`, the previews and `TabIndicatorInset` stay in `SessionsBar.kt`.

- [ ] **Step 1: Move `SessionTab`**

Cut `SessionTab` (`SessionsBar.kt:490-712`) and the helpers only it uses (`liftSpec`, the release-offset state) into `SessionTab.kt`. Change `private` to `internal`. Move every comment verbatim; they explain the drag maths.

- [ ] **Step 2: Move the menu and the dot**

Cut `SessionActionsMenu` (`:713-762`) and `SessionStateDot` (`:763-805`) into their own files. If `sessionStateColor` (`:828`) is referenced from more than one of the moved composables, move it into `SessionStateDot.kt` as `internal`; otherwise move it with its only caller.

- [ ] **Step 3: Verify**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (`SessionsBarLayoutTest`, `SessionsStateTest` unchanged).
Run: `wc -l app/src/main/java/com/termux/app/ui/sessions/*.kt`
Expected: no file above 500 lines; `SessionsBar.kt` is roughly 400.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/termux/app/ui/sessions
git commit -m "Changed(app): Split the session tab composables out of SessionsBar"
```

## Phase 6 — Build, CI, publication, backup script

### Task 26: Harden the release workflow

**Files:**
- Modify: `.github/workflows/attach_release_apks_to_release.yml:11-41`, `:60-70`

- [ ] **Step 1: Implement**

1. Remove `GITHUB_TOKEN` from the job `env:`; add `env: GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}` on the two steps that call `gh` (upload at `:99`, delete inside `exit_on_error`).
2. Change `ref: ${{ env.GITHUB_REF }}` to `ref: ${{ github.ref }}`.
3. Replace the whole-release deletion with per-asset deletion: `gh release delete-asset "$RELEASE_VERSION_NAME" "<name>" --yes` for the six assets this leg would have produced, and drop `git push --delete origin`. Keep the tag.
4. Add the version check before `./gradlew assembleRelease`:

```bash
expected="${RELEASE_VERSION_NAME#v}"
actual="$(./gradlew -q versionName | tail -1)"
[ "$expected" = "$actual" ] || exit_on_error "Tag '$expected' does not match app/build.gradle versionName '$actual'"
export TERMUX_APP_VERSION_NAME="$expected"
```

- [ ] **Step 2: Verify**

Run: `grep -n "GH_TOKEN\|github.ref\|delete-asset\|TERMUX_APP_VERSION_NAME" .github/workflows/attach_release_apks_to_release.yml`
Expected: each appears; `git push --delete` is gone. `actionlint` is not installed; use `python3 -c "import yaml,sys;yaml.safe_load(open('.github/workflows/attach_release_apks_to_release.yml'))"` for syntax.

- [ ] **Step 3: Commit**

```bash
git commit -am "Security(ci): Scope the release token and stop cross-leg release deletion"
```

### Task 26b: Reject escaping link entries in the backup archive scan

**Files:**
- Modify: `scripts/termux-backup/lib/archive.sh:302-315`
- Test: `scripts/termux-backup/tests/restore_test.sh`

- [ ] **Step 1: Write the failing test**

Add to `restore_test.sh`:

```bash
test_absolute_symlink_targets_are_rejected() {
    setup_sandbox
    mkdir -p "$SANDBOX/tree"
    ln -s /data/data/com.termux "$SANDBOX/tree/escape"
    ( cd "$SANDBOX" && tar -czf "$SANDBOX/link.tar.gz" tree )
    load_libs
    assert_exit 2 archive_scan "$SANDBOX/link.tar.gz"
    teardown_sandbox
}

test_relative_symlinks_are_accepted() {
    setup_sandbox
    mkdir -p "$SANDBOX/tree"
    echo hi >"$SANDBOX/tree/file.txt"
    ln -s file.txt "$SANDBOX/tree/link"
    ( cd "$SANDBOX" && tar -czf "$SANDBOX/relative.tar.gz" tree )
    load_libs
    assert_success archive_scan "$SANDBOX/relative.tar.gz"
    teardown_sandbox
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `bash scripts/termux-backup/tests/run.sh`
Expected: FAIL — `test_absolute_symlink_targets_are_rejected` fails; the current scan only checks names.

- [ ] **Step 3: Implement**

Read `tar -tvf` instead of `tar -tf`. For each line, take the first character as the type. Keep rejecting names that are absolute or contain `..`. For a link entry (`l`, and `h` where the listing shows one), extract the target after ` -> ` and reject it when it starts with `/` or contains a `..` segment. Do not reject relative symlinks: a real `$PREFIX` backup contains them.

- [ ] **Step 4: Run the suite**

Run: `bash scripts/termux-backup/tests/run.sh`
Expected: PASS, including `test_archive_scan_accepts_normal_archive`, the two new cases, and every other `restore_test.sh` case.

- [ ] **Step 5: Commit**

```bash
git add scripts/termux-backup/lib/archive.sh scripts/termux-backup/tests/restore_test.sh
git commit -m "Security(backup): Reject archive link entries that escape the restore root"
```

### Task 27: Pin actions, fix triggers, fail on HTTP errors

**Files:**
- Modify: `.github/workflows/debug_build.yml:26,29,35,102-162`, `dependency-submission.yml:16-23`, `gradle-wrapper-validation.yml:22-23`, `run_tests.yml:5-11,21-52`, `attach_release_apks_to_release.yml:22`, `trigger_library_builds_on_jitpack.yml:22-24`

- [ ] **Step 1: Implement**

Replace every `uses: owner/action@vN` with the full commit SHA of that tag. Resolve it with `git ls-remote https://github.com/owner/action 'refs/tags/vN*'` and use the commit SHA — the `^{}` line for an annotated tag, the plain line for a lightweight one. Replace `android-10` with `'github-releases/**'` in both trigger lists of `run_tests.yml`. Add `--fail --show-error` to the three `curl` calls.

- [ ] **Step 2: Verify**

Run: `grep -rn "uses: .*@v[0-9]" .github/workflows/` → no output.
Run: `grep -n "github-releases" .github/workflows/run_tests.yml` → two lines.

- [ ] **Step 3: Commit**

```bash
git commit -am "Security(ci): Pin actions by commit and fail on JitPack HTTP errors"
```

### Task 28: Verify the wrapper and stop disabling the stack protector

**Files:**
- Modify: `gradle/wrapper/gradle-wrapper.properties:3`
- Modify: `app/build.gradle:83`, `terminal-emulator/build.gradle:22`

- [ ] **Step 1: Implement**

Add `distributionSha256Sum=<sha256 of gradle-9.3.1-bin.zip>` (compute from `~/.gradle/wrapper/dists/gradle-9.3.1-bin/*/gradle-9.3.1-bin.zip` if present, otherwise download the zip and hash it). Remove `-fno-stack-protector` from both `cFlags` lists. Remove `-Wl,--gc-sections` from `cFlags`; if it is wanted, add it to `LOCAL_LDFLAGS` in the module `Android.mk`.

- [ ] **Step 2: Verify**

Run: `./gradlew help --console=plain`
Expected: `BUILD SUCCESSFUL` (the wrapper re-validates the distribution hash on next download; a wrong hash fails loudly).

- [ ] **Step 3: Commit**

```bash
git commit -am "Security(build): Verify the Gradle distribution and keep the stack protector"
```

### Task 29: Enable build caching and cache the bootstraps in CI

**Files:**
- Modify: `gradle.properties:14-15`
- Modify: `.github/workflows/run_tests.yml`, `debug_build.yml`

- [ ] **Step 1: Implement**

Uncomment `org.gradle.parallel=true`, add `org.gradle.caching=true`. Add an `actions/cache` step before the build in both workflows:

```yaml
- uses: actions/cache@<sha>
  with:
    path: app/src/main/cpp/bootstrap-*.zip
    key: bootstrap-${{ hashFiles('app/build.gradle') }}
```

- [ ] **Step 2: Verify**

Run: `./gradlew test --console=plain`
Expected: `BUILD SUCCESSFUL`; the second run reports `FROM-CACHE`/`UP-TO-DATE` for more tasks.

- [ ] **Step 3: Commit**

```bash
git commit -am "Changed(build): Enable Gradle caching and cache the bootstrap downloads"
```

### Task 30: Publish the API dependencies and drop the redundant sources task

**Files:**
- Modify: `termux-shared/build.gradle:25`, `:27`, `:92-96`
- Modify: `terminal-view/build.gradle:76-80`
- Modify: `terminal-emulator/build.gradle:81-85`
- Modify: `app/build.gradle:36-46`
- Create: `gradle/libs.versions.toml`

- [ ] **Step 1: Implement**

Change `implementation project(":terminal-view")` and `implementation "com.termux:termux-am-library:v2.0.0"` to `api` in `:termux-shared` — its public API references `TerminalView` and `TermuxAm`. Delete the three `sourceJar` tasks and the `artifact(sourceJar)` lines; `withSourcesJar()` already publishes sources. Create the catalog with `annotation`, `junit`, `robolectric`, `appcompat`, `core`, `material`, `guava`, `markwon`, `hiddenapibypass`, `window`, `termux-am-library` and replace the literal coordinates in the four build files.

- [ ] **Step 2: Verify**

Run: `./gradlew :termux-shared:publishReleasePublicationToMavenLocal :terminal-view:publishReleasePublicationToMavenLocal`
Expected: `BUILD SUCCESSFUL`; the POM for `termux-shared` lists `terminal-view` and `termux-am-library` with `<scope>compile</scope>`, and exactly one `-sources.jar` per version (no `-0.118.0-sources.jar`).

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add termux-shared/build.gradle terminal-view/build.gradle terminal-emulator/build.gradle gradle/libs.versions.toml
git commit -m "Changed(build): Publish API dependencies and centralize versions"
```

---

## Phase 7 — Docs and final verification

### Task 31: Correct the ledger and the local agent notes

**Files:**
- Modify: `docs/ledger/outstanding-work.md:175` (working tree)
- Modify: `AGENTS.md:56`, `:110`

- [ ] **Step 1: Implement**

Change `114 \`:terminal-view\`` to `135 \`:terminal-view\``. In `AGENTS.md`, update `versionName`/`versionCode` to `2.6.2` / `123` and delete the claim that `./gradlew clean` deletes the bootstrap zips (it does not; `app/build.gradle:275-277` says so).

- [ ] **Step 2: Verify**

Run: `git diff --stat docs/ledger AGENTS.md`
Expected: both files listed.

- [ ] **Step 3: Commit**

```bash
git add docs/ledger/outstanding-work.md AGENTS.md
git commit -m "Changed(docs): Correct the test count and the toolchain notes"
```

### Task 32: Final verification

**Files:** none.

- [ ] **Step 1: Run the full suite**

Run: `./gradlew test --console=plain`
Expected: `BUILD SUCCESSFUL`; counts at or above 530 / 135 / 377, 0 failures.

- [ ] **Step 2: Run lint**

Run: `./gradlew :app:lintDebug --console=plain`
Expected: `BUILD SUCCESSFUL`; no `StaticFieldLeak`, `UnspecifiedImmutableFlag`, `PluralsCandidate`, `DefaultLocale`, `FrequentlyChangingValue`, `UnusedResources`, or `SetWorldReadable` entries remain.

- [ ] **Step 3: Verify publication**

Run: `./gradlew :terminal-view:publishReleasePublicationToMavenLocal :termux-shared:publishReleasePublicationToMavenLocal`
Expected: `BUILD SUCCESSFUL`; `~/.m2/repository/com/termux/termux-shared/0.118.0/` holds one `-sources.jar`.

- [ ] **Step 4: Verify the workflow files parse**

Run: `python3 -c "import glob,yaml;[yaml.safe_load(open(f)) for f in glob.glob('.github/workflows/*.yml')];print('ok')"`
Expected: `ok`.

- [ ] **Step 5: Review the whole branch**

Run: `git log --oneline master..HEAD` and `git diff --stat master...HEAD`
Expected: one commit per task, Conventional Commits capitalized types, no unrelated files.

No commit.

---

## Out of scope

- Device checks D-13/D-14/D-15 in `docs/ledger/manual-checks.md` — deferred per the review.
- The five rejected claims listed under Global Constraints.
- `:app` and `:terminal-view` instrumented tests need a device; Task 13 only makes them compile.
- Native JNI compilation on this `aarch64` host (ledger §3.2); Task 6 is verified by CI.
