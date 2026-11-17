# Post-Review Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close every finding from the 2026-11-17 review of `v2.0.0-beta..e3f68eb1` (319 commits, 410
files) — one test-first commit per finding, security and crash paths first.

**Architecture:** No new subsystems. Each task narrows an existing boundary: untrusted input gets
validated at the point it becomes a `File`, a broadcast registration gets an explicit export flag, an
emulator parser gets a capacity clamp, a renderer gets a consistent row snapshot. Pure seams are
extracted only where the current code cannot be reached from a JVM unit test.

**Tech Stack:** Kotlin 2.4.21 / Java 17, AGP 9.1.1, JUnit 4, Robolectric 4.17 (`:app`,
`:terminal-emulator`), no new dependencies.

**Review:** the findings below are the verified result of that review; this plan is the fix order.
Read the row for a finding, then jump to its task. Nothing here is speculative — each row was
reproduced against `e3f68eb1` before it was written down.

| Finding | Severity | Where | Task |
|---|---|---|---|
| F1 received file name is used as a path (`../.bashrc`) | Critical | `FileReceiverActivity.saveStreamWithName` | 2 |
| F2 received intent handled twice; content stream never closed | Critical | `FileReceiverActivity.onResume` | 3 |
| F3 report info deserialised from an unvalidated path | High | `ReportActivity.updateUI` | 4 |
| F4 `createDocument` accepts a path; search accepts a sibling prefix | High | `TermuxDocumentsProvider` | 5 |
| F5 internal activity broadcasts are registered exported | High | `TermuxActivity` | 6 |
| F6 sixel raster size sets an unclamped buffer capacity | Critical | `SixelParser` | 8 |
| F7 scrolled-out bitmap walk NPEs; collected cells stay stamped | High | `TerminalBuffer` | 9 |
| F8 bitmap resize allocates a 4× oversized int array | High | `TerminalBitmap.resizeBitmap` | 10 |
| F9 renderer drops the pending run at a bitmap cell | High | `TerminalRenderer` | 11 |
| F10 palette paste dereferences a null emulator | Medium | `TermuxTerminalSessionActivityClient` | 7 |
| F11 rows render without the emulator lock (torn reads) | Medium | `TerminalRenderer` | 12 |
| F12 collapsing the key bar keeps focus on the writing page | Medium | `ExtraKeysBar` | 13 |
| F13 snippet editor has no cap; the store truncates silently | Medium | `SnippetsState` | 14 |
| F14 iTerm import reads an unbounded stream | Medium | `AppearanceActivity` | 15 |
| F15 pending font cache files outlive the screen | Medium | `AppearanceActivity` | 16 |
| F16 selection handle cannot clear its tint back to 0 | Medium | `TextSelectionHandleView` | 18 |
| F17 `/proc` walk runs on every session refresh | Medium | `TermuxActivity` | 20 |
| F18 `collectFilesOlderThan` follows directory symlinks | Low | `FileUtils` | 17 |
| F19 wallpaper decode lets `OutOfMemoryError` escape | Low | `WallpaperSource` | 19 |
| F20 `keycap-visuals` never reaches the view; four dead keys | Low | `ExtraKeysView`, `UiPreferences` | 21 |
| F21 seven commits do not follow the capitalized commit types | Nit | history | 22 |
| F22 no version bump for the hardening release | Nit | `app/build.gradle` | 22 |

## Global Constraints

- Package identity stays `com.termux`. `TermuxConstants.TERMUX_PACKAGE_NAME`, the derived companion
  package names, `TERMUX_PACKAGE_NAME` and `sharedUserId` are untouched in every task.
- Never run `./gradlew clean`: it deletes the four cached `bootstrap-*.zip` files (~115 MB re-download).
- Test first. Each task adds or extends a test that fails before the fix and passes after.
- Commit types are exactly `Added`, `Changed`, `Deprecated`, `Removed`, `Fixed`, `Security`,
  capitalized, present tense: `Security(app): Reject received-file names that escape the downloads
  directory`. One finding, one commit.
- No new Gradle dependencies. `:terminal-view` has no Robolectric, so renderer changes are gated by
  its call-recording tests plus a manual check; do not add Robolectric there (it regenerates
  `gradle/verification-metadata.xml`).
- `:termux-shared` is a published library: keep existing public signatures; add only.
- No `java.nio.file` in `:termux-shared` main sources (Android < 8 breaks on the class reference);
  tests may use it.
- Every commit ends with `./gradlew test` green for the touched modules before the commit is made.

## Review Focus

- **Received-file containment (Task 2)** — the exploitable write path. Verify by hand that
  `../.bashrc` and `sub/x` are both refused and that nothing outside `~/downloads` is created.
- **Renderer bitmap flush (Task 11)** — call-sequence test only; the visual result needs the device
  check in Task 22 (`manual-checks.md` D-23).
- **Row snapshot (Task 12)** — `synchronized` reads must not include drawing. The snapshot copies
  five arrays per row; confirm scrolling does not regress in the same device pass.
- **Sixel clamp (Task 8)** — assert the clamp against `terminalControlArgsMaxLength`, not a literal.
- **Broadcast split (Task 6)** — `ACTION_WALLPAPER_CHANGED` is system-sent; it must stay
  `RECEIVER_EXPORTED`, and the three internal actions must be `RECEIVER_NOT_EXPORTED`.

---

### Task 1: Branch and baseline

**Files:**
- Create: `docs/plans/2026-11-17-post-review-hardening.md` (this plan)
- Modify: none

**Interfaces:**
- Produces: branch `fix/post-review-hardening`, the plan in history, and a recorded baseline.

- [ ] **Step 1: Create the branch**

```bash
git switch -c fix/post-review-hardening
```

- [ ] **Step 2: Record the baseline**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`; note the test count for the final comparison. Do not `clean`.

- [ ] **Step 3: Commit the plan**

```bash
git add docs/plans/2026-11-17-post-review-hardening.md
git commit -m "Added(docs): Add the post-review hardening plan"
```

- [ ] **Step 4: Confirm the working tree is clean before the first fix**

Run: `git status --porcelain`
Expected: no output.

---

### Task 2: Reject received-file names that escape the downloads directory

**Files:**
- Create: `app/src/main/java/com/termux/app/api/file/ReceivedFileNames.kt`
- Test: `app/src/test/java/com/termux/app/api/file/ReceivedFileNamesTest.kt`
- Modify: `app/src/main/java/com/termux/app/api/file/FileReceiverActivity.kt:198-231`

**Interfaces:**
- Produces: `internal fun safeReceivedFileName(raw: String?): String?` and
  `internal fun isDirectChildOf(directory: File, candidate: File): Boolean`.
- Consumes: `File.canonicalFile` only (`java.io`).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.termux.app.api.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReceivedFileNamesTest {

    @Test
    fun `plain names are kept`() {
        assertEquals("notes.txt", safeReceivedFileName("notes.txt"))
        assertEquals("notes.txt", safeReceivedFileName("  notes.txt  "))
        assertEquals("a b.txt", safeReceivedFileName("a b.txt"))
    }

    @Test
    fun `escaping and path names are rejected`() {
        assertNull(safeReceivedFileName(null))
        assertNull(safeReceivedFileName(""))
        assertNull(safeReceivedFileName("   "))
        assertNull(safeReceivedFileName("."))
        assertNull(safeReceivedFileName(".."))
        assertNull(safeReceivedFileName("../.bashrc"))
        assertNull(safeReceivedFileName("sub/file.txt"))
        assertNull(safeReceivedFileName("..\\windows.txt"))
        assertNull(safeReceivedFileName("/data/data/com.termux/.bashrc"))
        assertNull(safeReceivedFileName("a\u0000b"))
        assertNull(safeReceivedFileName("line\nbreak"))
    }

    @Test
    fun `containment compares canonical parents`() {
        val dir = File("/data/data/com.termux/files/home/downloads")
        assertTrue(isDirectChildOf(dir, File(dir, "notes.txt")))
        assertFalse(isDirectChildOf(dir, File(dir, "../.bashrc")))
        assertFalse(isDirectChildOf(dir, File("/data/data/com.termux/files/home/downloads2/x")))
        assertFalse(isDirectChildOf(dir, dir))
    }
}
```

- [ ] **Step 2: Run the test and see it fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.api.file.ReceivedFileNamesTest"`
Expected: compilation failure (`safeReceivedFileName` unresolved).

- [ ] **Step 3: Add the seam**

```kotlin
package com.termux.app.api.file

import java.io.File
import java.io.IOException

/**
 * Returns [raw] only when it names one file directly inside a directory. The name arrives from the
 * sharing app, so separators, `.`, `..` and control characters are refused rather than sanitised:
 * a silently rewritten name is worse than an error dialog.
 */
internal fun safeReceivedFileName(raw: String?): String? {
    val name = raw?.trim() ?: return null
    if (name.isEmpty() || name == "." || name == "..") return null
    if (name.any { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7f }) return null
    return name
}

/** True when [candidate] is a direct child of [directory] after canonicalisation. */
internal fun isDirectChildOf(directory: File, candidate: File): Boolean =
    try {
        candidate.canonicalFile.parentFile == directory.canonicalFile
    } catch (e: IOException) {
        false
    }
```

- [ ] **Step 4: Use it in `saveStreamWithName`**

Replace the name check and the `File(receiveDir, attachmentFileName!!)` construction:

```kotlin
fun saveStreamWithName(inputStream: InputStream, attachmentFileName: String?): File? {
    val receiveDir = File(TERMUX_RECEIVEDIR)
    val fileName = safeReceivedFileName(attachmentFileName)
    if (fileName == null) {
        showErrorDialogAndQuit("File name cannot be empty or contain a path")
        return null
    }

    if (!receiveDir.isDirectory && !receiveDir.mkdirs()) {
        showErrorDialogAndQuit("Cannot create directory: " + receiveDir.getAbsolutePath())
        return null
    }

    val outFile = File(receiveDir, fileName)
    if (!isDirectChildOf(receiveDir, outFile)) {
        showErrorDialogAndQuit("File name cannot be empty or contain a path")
        return null
    }
    // ... existing try/catch, using outFile
}
```

- [ ] **Step 5: Run the test and the module suite**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.api.file.ReceivedFileNamesTest" :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/termux/app/api/file/ReceivedFileNames.kt \
        app/src/main/java/com/termux/app/api/file/FileReceiverActivity.kt \
        app/src/test/java/com/termux/app/api/file/ReceivedFileNamesTest.kt
git commit -m "Security(app): Reject received-file names that escape the downloads directory"
```

---

### Task 3: Handle a received intent once and own the content stream

**Files:**
- Create: `app/src/main/java/com/termux/app/api/file/ReceivedIntentGate.kt`
- Test: `app/src/test/java/com/termux/app/api/file/ReceivedIntentGateTest.kt`
- Modify: `app/src/main/java/com/termux/app/api/file/FileReceiverActivity.kt:47-60, 120-196, 198-235`

**Interfaces:**
- Produces: `internal class ReceivedIntentGate { fun begin(): Boolean }`.
- Consumes: nothing.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.termux.app.api.file

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceivedIntentGateTest {

    @Test
    fun `the first resume handles the intent and later ones do not`() {
        val gate = ReceivedIntentGate()
        assertTrue(gate.begin())
        assertFalse(gate.begin())
        assertFalse(gate.begin())
    }

    @Test
    fun `a fresh gate is open`() {
        assertTrue(ReceivedIntentGate().begin())
    }
}
```

- [ ] **Step 2: Run it and see it fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.api.file.ReceivedIntentGateTest"`
Expected: compilation failure.

- [ ] **Step 3: Add the gate**

```kotlin
package com.termux.app.api.file

/**
 * Lets one activity instance consume its launch intent exactly once. `onResume` re-enters after a
 * dialog or a permission prompt; without the gate the share is saved and the editor is launched a
 * second time.
 */
internal class ReceivedIntentGate {
    private var handled = false

    fun begin(): Boolean {
        if (handled) return false
        handled = true
        return true
    }
}
```

- [ ] **Step 4: Wire the gate and close the stream**

In `FileReceiverActivity`:

```kotlin
private val mIntentGate = ReceivedIntentGate()

override fun onResume() {
    super.onResume()
    if (!mIntentGate.begin()) return
    // ... existing dispatch
}
```

In `handleContentUri`, replace the forced unwrap:

```kotlin
val inputStream = contentResolver.openInputStream(uri)
if (inputStream == null) {
    showErrorDialogAndQuit("Unable to open the shared content")
    return
}
promptNameAndSave(inputStream, attachmentFileName)
```

In `saveStreamWithName`, close the stream with the output (the stream belongs to this method once it
is called):

```kotlin
try {
    inputStream.use { input ->
        FileOutputStream(outFile).use { f ->
            val buffer = ByteArray(4096)
            while (true) {
                val readBytes = input.read(buffer)
                if (readBytes <= 0) break
                f.write(buffer, 0, readBytes)
            }
        }
    }
    return outFile
} catch (e: IOException) { /* existing error path */ }
```

In `promptNameAndSave`, the cancel and dismiss listeners close it too, so a cancelled dialog does not
leak the descriptor:

```kotlin
TextInputDialogUtils.TextSetListener {
    runCatching { inputStream.close() }
    finish()
},
DialogInterface.OnDismissListener {
    runCatching { inputStream.close() }
    if (mFinishOnDismissNameDialog) finish()
},
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.api.file.*" :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/termux/app/api/file/ app/src/test/java/com/termux/app/api/file/
git commit -m "Fixed(app): Handle a received file intent once and close its stream"
```

---

### Task 4: Validate the report-info path before deserialising

**Files:**
- Modify: `termux-shared/src/main/java/com/termux/shared/file/FileUtils.java` (add one helper next to
  `isPathInDirPath`; do not change `isPathInDirPath`, its canonicalisation is asymmetric)
- Modify: `termux-shared/src/main/java/com/termux/shared/activities/ReportActivity.java:118-140, 392-420`
- Test: `termux-shared/src/test/java/com/termux/shared/file/FileUtilsTest.java`

**Interfaces:**
- Produces: `public static boolean FileUtils.isDirectChildPath(String directoryPath, String filePath)`.
- Consumes: `FileUtils.getCanonicalPath`.

- [ ] **Step 1: Write the failing test**

Add to the existing `FileUtilsTest` (it is plain JUnit, no Android needed):

```java
@Test
public void testIsDirectChildPath() throws IOException {
    File dir = Files.createTempDirectory("direct-child").toFile();
    assertTrue(FileUtils.isDirectChildPath(dir.getAbsolutePath(), new File(dir, "a.bin").getAbsolutePath()));
    assertFalse(FileUtils.isDirectChildPath(dir.getAbsolutePath(),
        new File(dir, "../escape.bin").getAbsolutePath()));
    assertFalse(FileUtils.isDirectChildPath(dir.getAbsolutePath(),
        new File(dir.getAbsolutePath() + "2", "a.bin").getAbsolutePath()));
    assertFalse(FileUtils.isDirectChildPath(dir.getAbsolutePath(), dir.getAbsolutePath()));
    assertFalse(FileUtils.isDirectChildPath(null, dir.getAbsolutePath()));
    assertFalse(FileUtils.isDirectChildPath(dir.getAbsolutePath(), null));
}
```

Add `import java.io.IOException;` and `import java.nio.file.Files;` to the test only.

- [ ] **Step 2: Run it and see it fail**

Run: `./gradlew :termux-shared:test --tests "com.termux.shared.file.FileUtilsTest"`
Expected: compilation failure (`isDirectChildPath` unresolved).

- [ ] **Step 3: Add the helper**

```java
/**
 * True when {@code filePath} canonicalises to a file directly inside {@code directoryPath}.
 * Unlike {@link #isPathInDirPath}, both sides are canonicalised, so `/data/data` and `/data/user/0`
 * compare correctly, and the separator keeps a sibling prefix (`dir2`) out.
 */
public static boolean isDirectChildPath(String directoryPath, String filePath) {
    if (directoryPath == null || filePath == null) return false;
    String directory = getCanonicalPath(directoryPath, null);
    String candidate = getCanonicalPath(filePath, null);
    return directory != null && candidate != null
        && candidate.startsWith(directory + File.separator);
}
```

- [ ] **Step 4: Validate both report-info paths with it**

In `updateUI`, before `readSerializableObjectFromFile`:

```java
if (!FileUtils.isDirectChildPath(getReportInfoDirectoryPath(this), mReportInfoFilePath)) {
    Logger.logError(LOG_TAG, "Refusing to read " + ReportInfo.class.getSimpleName()
        + " serialized object from a path outside the report info directory: \"" + mReportInfoFilePath + "\"");
    finish(); return;
}
```

In `deleteReportInfoFile`, replace the inline `equals`/`startsWith` pair with
`FileUtils.isDirectChildPath(reportInfoDirectoryPath, reportInfoFilePath)` and drop the now-unused
canonicalisation of `reportInfoFilePath` (the helper does it).

- [ ] **Step 5: Run the module suite**

Run: `./gradlew :termux-shared:test`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add termux-shared/src/main/java/com/termux/shared/file/FileUtils.java \
        termux-shared/src/main/java/com/termux/shared/activities/ReportActivity.java \
        termux-shared/src/test/java/com/termux/shared/file/FileUtilsTest.java
git commit -m "Security(termux-shared): Read report info only from the cache subdirectory"
```

---

### Task 5: Contain the documents provider and bound its search

**Files:**
- Modify: `app/src/main/java/com/termux/filepicker/TermuxDocumentsProvider.kt:107-127, 144-190`
- Test: `app/src/test/java/com/termux/filepicker/TermuxDocumentsProviderTest.java`

**Interfaces:**
- Produces: `private fun isInsideRoot(rootCanonicalPath: String, candidate: File): Boolean` and a
  `displayName` guard in `createDocument`.
- Consumes: the existing `root` fixture in the test (`@Before` creates a temp dir and the provider
  resolves it).

- [ ] **Step 1: Read the existing fixtures**

Run: `sed -n '30,70p' app/src/test/java/com/termux/filepicker/TermuxDocumentsProviderTest.java`
Expected: `root` is a `TemporaryFolder`/temp dir and `file(...)`/`dir(...)` helpers exist. Reuse them;
do not add a second fixture.

- [ ] **Step 2: Write the failing tests**

```java
@Test
public void testSearchDoesNotLeaveTheRootThroughASiblingPrefix() throws Exception {
    // A sibling that only shares the root's prefix is reached through a symlink inside the root:
    // a plain `startsWith` sees it as inside, a separator-aware check does not.
    File sibling = new File(root.getParentFile(), root.getName() + "2");
    assertTrue(sibling.mkdirs());
    file(sibling, "secret.txt");
    File link = new File(root, "escape");
    try {
        java.nio.file.Files.createSymbolicLink(link.toPath(), sibling.toPath());
    } catch (IOException | UnsupportedOperationException e) {
        return; // a filesystem without symlinks cannot exercise the boundary
    }

    try {
        List<String> names = names(provider.querySearchDocuments(root.getAbsolutePath(), "secret", null));

        assertFalse(names.toString(), names.contains("secret.txt"));
    } finally {
        link.delete();
        deleteRecursively(sibling);
    }
}

@Test(expected = java.io.FileNotFoundException.class)
public void testCreateDocumentRejectsATraversingName() throws Exception {
    provider.createDocument(root.getAbsolutePath(), "text/plain", "../escape.txt");
}

@Test
public void testCreateDocumentRejectsASubdirectoryName() throws Exception {
    // `sub` must exist, otherwise today's code fails for the wrong reason (no parent directory).
    dir("sub");

    try {
        provider.createDocument(root.getAbsolutePath(), "text/plain", "sub/child.txt");
        fail("expected FileNotFoundException");
    } catch (java.io.FileNotFoundException expected) {
        assertFalse("a rejected name must not create a file",
            new File(new File(root, "sub"), "child.txt").exists());
    }
}
```

`names(...)`, `file(...)`, `dir(...)` and `deleteRecursively(...)` already exist in the class; add
`import static org.junit.Assert.fail;` (the file imports names individually).

- [ ] **Step 3: Run them and see them fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.filepicker.TermuxDocumentsProviderTest"`
Expected: the sibling-prefix test finds `secret.txt`; the create tests succeed instead of throwing.

- [ ] **Step 4: Fix the search boundary**

```kotlin
private fun isInsideRoot(rootCanonicalPath: String, candidate: File): Boolean =
    try {
        val canonical = candidate.canonicalPath
        canonical == rootCanonicalPath || canonical.startsWith(rootCanonicalPath + File.separator)
    } catch (e: IOException) {
        false
    }
```

Use it in `querySearchDocuments` in place of the `startsWith`/fail-open pair, and keep the existing
comment about the root bounding the walk. An unreadable path is skipped, not trusted.

- [ ] **Step 5: Validate the name in `createDocument`**

```kotlin
override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
    if (displayName.isEmpty() || displayName == "." || displayName == ".." ||
        displayName.contains('/') || displayName.contains('\\') ||
        displayName.any { it.code < 0x20 }) {
        throw FileNotFoundException("Invalid display name: $displayName")
    }
    val parent = File(parentDocumentId)
    var newFile = File(parent, displayName)
    if (!isInsideRoot(parent.canonicalPath, newFile)) {
        throw FileNotFoundException("Refusing to create a document outside $parentDocumentId")
    }
    // ... existing conflict loop, with the same isInsideRoot check after each rename
}
```

Wrap the `canonicalPath` calls in a `try`/`catch (IOException)` that throws `FileNotFoundException`, so
an unreadable parent fails closed.

- [ ] **Step 6: Run the class and the module suite**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.filepicker.TermuxDocumentsProviderTest" :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/termux/filepicker/TermuxDocumentsProvider.kt \
        app/src/test/java/com/termux/filepicker/TermuxDocumentsProviderTest.java
git commit -m "Security(app): Contain document creation and search inside the document root"
```

---

### Task 6: Register the internal activity broadcasts as not exported

**Files:**
- Create: `app/src/main/java/com/termux/app/TermuxActivityFilters.kt`
- Test: `app/src/test/java/com/termux/app/TermuxActivityFiltersTest.kt`
- Modify: `app/src/main/java/com/termux/app/TermuxActivity.kt:1540-1553`

**Interfaces:**
- Produces: `internal fun internalActivityFilter(): IntentFilter` and
  `internal fun systemActivityFilter(): IntentFilter`.
- Consumes: `com.termux.shared.termux.TermuxConstants.TERMUX_ACTIVITY`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.termux.app

import android.content.Intent
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class TermuxActivityFiltersTest {

    @Test
    fun `internal filter carries only the in-process actions`() {
        val filter = internalActivityFilter()
        assertTrue(filter.hasAction(TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH))
        assertTrue(filter.hasAction(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE))
        assertTrue(filter.hasAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS))
        assertFalse(filter.hasAction(Intent.ACTION_WALLPAPER_CHANGED))
    }

    @Test
    fun `system filter carries only the wallpaper change`() {
        val filter = systemActivityFilter()
        assertTrue(filter.hasAction(Intent.ACTION_WALLPAPER_CHANGED))
        assertFalse(filter.hasAction(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE))
    }
}
```

`TERMUX_ACTIVITY` is nested under `TERMUX_APP`, and `@ConscryptMode(OFF)` is required on this
host: without it Robolectric loads a Conscrypt JNI library that cannot resolve `libpthread.so.0`.

- [ ] **Step 2: Run it and see it fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.TermuxActivityFiltersTest"`
Expected: compilation failure.

- [ ] **Step 3: Add the filters**

```kotlin
package com.termux.app

import android.content.Intent
import android.content.IntentFilter
import com.termux.shared.termux.TermuxConstants.TERMUX_ACTIVITY

/** Actions sent by this app to its own activity; they must never cross the app boundary. */
internal fun internalActivityFilter(): IntentFilter = IntentFilter().apply {
    addAction(TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH)
    addAction(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE)
    addAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS)
}

/** The one system broadcast this activity still listens to. */
internal fun systemActivityFilter(): IntentFilter = IntentFilter(Intent.ACTION_WALLPAPER_CHANGED)
```

- [ ] **Step 4: Register each filter with its flag**

```kotlin
private fun registerTermuxActivityBroadcastReceiver() {
    ContextCompat.registerReceiver(
        this, mTermuxActivityBroadcastReceiver, internalActivityFilter(),
        ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    ContextCompat.registerReceiver(
        this, mTermuxActivityBroadcastReceiver, systemActivityFilter(),
        ContextCompat.RECEIVER_EXPORTED,
    )
}
```

Add `import androidx.core.content.ContextCompat`. `unregisterReceiver` stays as it is: it removes
every filter registered with that instance.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.TermuxActivityFiltersTest" :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/termux/app/TermuxActivityFilters.kt \
        app/src/main/java/com/termux/app/TermuxActivity.kt \
        app/src/test/java/com/termux/app/TermuxActivityFiltersTest.kt
git commit -m "Security(app): Register the internal activity actions as not exported"
```

---

### Task 7: Make palette paste survive a terminal that is not ready

**Files:**
- Create: `app/src/main/java/com/termux/app/terminal/TerminalPaste.kt`
- Test: `app/src/test/java/com/termux/app/terminal/TerminalPasteTest.kt`
- Modify: `app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.kt:201-207`

**Interfaces:**
- Produces: `internal fun pasteText(emulator: TerminalEmulator?, text: String?): Boolean`.
- Consumes: `TerminalEmulator.paste`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.termux.app.terminal

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class TerminalPasteTest {

    @Test
    fun `no emulator and no text are no-ops`() {
        assertFalse(pasteText(null, "ls"))
        assertFalse(pasteText(null, null))
    }

    @Test
    fun `text reaches a live emulator`() {
        val emulator = TerminalEmulator(NoOutput(), 80, 24, 13, 15, 48, null)
        assertTrue(pasteText(emulator, "ls"))
        assertFalse(pasteText(emulator, null))
    }

    private class NoOutput : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) = Unit
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }
}
```

Match the exact `TerminalOutput` member signatures in
`terminal-emulator/src/main/java/com/termux/terminal/TerminalOutput.java`; copy them, do not guess.

- [ ] **Step 2: Run it and see it fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.terminal.TerminalPasteTest"`
Expected: compilation failure.

- [ ] **Step 3: Add the seam**

```kotlin
package com.termux.app.terminal

import com.termux.terminal.TerminalEmulator

/** Pastes [text] into [emulator]; false when there is nothing to paste into or nothing to paste. */
internal fun pasteText(emulator: TerminalEmulator?, text: String?): Boolean {
    if (emulator == null || text == null) return false
    emulator.paste(text)
    return true
}
```

- [ ] **Step 4: Use it**

```kotlin
override fun onPasteTextFromClipboard(session: TerminalSession?) {
    if (!mActivity.isVisible()) return
    val text = ShareUtils.getTextStringFromClipboardIfSet(mActivity, true)
    pasteText(mActivity.getTerminalView().mEmulator, text)
}
```

The `session` parameter stays in the signature (it is part of the client interface); do not rename it.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.terminal.TerminalPasteTest" :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/termux/app/terminal/TerminalPaste.kt \
        app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.kt \
        app/src/test/java/com/termux/app/terminal/TerminalPasteTest.kt
git commit -m "Fixed(app): Do not crash when pasting before the terminal is ready"
```

---

### Task 8: Clamp the sixel arguments capacity

**Files:**
- Modify: `terminal-emulator/src/main/java/com/termux/terminal/SixelParser.java:306-317`
- Test: `terminal-emulator/src/test/java/com/termux/terminal/SixelParserTest.java`

**Interfaces:**
- Produces: `newArgsCapacity <= terminalControlArgsMaxLength` for every accepted raster command.
- Consumes: the existing `parse(String)` helper (passes `1024` as the max length).

- [ ] **Step 1: Write the failing test**

```java
@Test
public void rasterSizeCannotRequestMoreThanTheArgsBufferMaximum() {
    // `Ph;Pv` are untrusted; without a clamp this asks for ~2 GB of char storage.
    SixelParser.Result result = parse("\"1;1;2000000000;2000000000");
    assertTrue(result.valid);
    assertEquals(1024, result.newArgsCapacity);
}

@Test
public void rasterSizeStillChoosesThePartsLimitedCapacity() {
    // Unchanged behaviour for a plausible image: the 1024 max length still wins over 100x100.
    SixelParser.Result result = parse("\"1;1;100;100");
    assertTrue(result.valid);
    assertEquals(624, result.newArgsCapacity);
}
```

The second case duplicates `rasterAttributesComputeCapacity`; keep only the first if the duplicate
feels redundant, but do keep the existing `624` test untouched — it is the guard that the clamp does
not change normal behaviour.

- [ ] **Step 2: Run it and see it fail**

Run: `./gradlew :terminal-emulator:testDebugUnitTest --tests "com.termux.terminal.SixelParserTest"`
Expected: the huge-raster test fails with `expected:<1024> but was:<-1>` — the `int` product
overflows to a negative number, so today no capacity is set at all; a merely large raster
(`20000x20000`) allocates ~400M chars instead.

`:terminal-emulator:test` is a lifecycle aggregate and rejects `--tests`; the variant task is the
one that runs a single class.

- [ ] **Step 3: Clamp, and compute the product as a `long`**

```java
if (sixelWidth > 0 && sixelHeight > 0) {
    // 2% extra for sixel commands/parameters in addition to image data. The product is computed
    // in long arithmetic: `Ph`/`Pv` are untrusted, so an int product overflows long before the
    // clamp below can apply.
    long sixelArgsExpectedLength = (long) ((double) sixelWidth * sixelHeight * 1.02);
    long sixelArgsPartsExpectedLength = (long) ((((double) terminalControlArgsMaxLength / 2) + sixelWidth) * 1.02);
    long sixelArgsExpectedCapacity = Math.min(sixelArgsPartsExpectedLength, sixelArgsExpectedLength);
    if (sixelArgsExpectedCapacity > SIXEL_ARGS__INITIAL_CAPACITY) {
        // The control args buffer is split at terminalControlArgsMaxLength, so a larger capacity
        // can never be used; without the clamp a hostile raster size allocates hundreds of
        // megabytes of char storage.
        newArgsCapacity = (int) Math.min(sixelArgsExpectedCapacity, terminalControlArgsMaxLength);
    }
    screen.sixelResize(sixelWidth, sixelHeight);
}
```

- [ ] **Step 4: Run the class and the module suite**

Run: `./gradlew :terminal-emulator:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add terminal-emulator/src/main/java/com/termux/terminal/SixelParser.java \
        terminal-emulator/src/test/java/com/termux/terminal/SixelParserTest.java
git commit -m "Security(terminal-emulator): Clamp the sixel arguments capacity to the buffer maximum"
```

---

### Task 9: Walk scrolled-out bitmap rows safely and clear their cells

**Files:**
- Modify: `terminal-emulator/src/main/java/com/termux/terminal/TerminalBuffer.java:1113-1145`
- Test: `terminal-emulator/src/test/java/com/termux/terminal/TerminalBufferPlacementTest.java`

**Interfaces:**
- Produces: `removeScrolledOutTerminalBitmaps(int row)` tolerates a null row and wraps at the last
  internal row; the collected cells are blanked through `blankStampedCells`.
- Consumes: `blankStampedCells(Set<Integer>)`, `TerminalBuffer.setChar`.

- [ ] **Step 1: Write the failing tests**

```java
@Test
public void lastInternalRowDoesNotOverrunTheLineArray() {
    // totalRows == screenRows, so internal and external row numbers coincide.
    TerminalBuffer buffer = new TerminalBuffer(10, 10, 10);
    buffer.setChar(0, 9, ' ', TextStyle.encodeTerminalBitmap(7, 0, 0));

    // The call site passes an internal row; the last one has no `row + 1` slot.
    buffer.removeScrolledOutTerminalBitmaps(buffer.mTotalRows - 1);

    assertFalse(TextStyle.isTerminalBitmap(buffer.getStyleAt(9, 0)));
}

@Test
public void theNextRowWrapsToTheFirstSlot() {
    TerminalBuffer buffer = new TerminalBuffer(10, 10, 10);
    buffer.setChar(0, 0, ' ', TextStyle.encodeTerminalBitmap(8, 0, 0));

    buffer.removeScrolledOutTerminalBitmaps(buffer.mTotalRows - 1);

    assertFalse(TextStyle.isTerminalBitmap(buffer.getStyleAt(0, 0)));
}

@Test
public void anUnallocatedNextRowIsTolerated() {
    TerminalBuffer buffer = new TerminalBuffer(10, 10, 10);
    // A bitmap on a row whose physical successor was never written: `mLines[row + 1]` is null.
    buffer.setChar(0, 5, ' ', TextStyle.encodeTerminalBitmap(9, 0, 0));

    buffer.removeScrolledOutTerminalBitmaps(5);

    assertFalse(TextStyle.isTerminalBitmap(buffer.getStyleAt(5, 0)));
}
```

`TerminalBuffer(columns, screenRows, totalRows)` keeps `mTotalRows` and the screen equal here, so
internal and external row numbers coincide and `setChar` accepts row 9. `getStyleAt(externalRow,
column)` is the buffer's accessor; `mTotalRows` is package-private and the test lives in
`com.termux.terminal`. Keep the `RobolectricTestRunner`/`@ConscryptMode(OFF)` annotations already on
the class.

- [ ] **Step 2: Run them and see them fail**

Run: `./gradlew :terminal-emulator:testDebugUnitTest --tests "com.termux.terminal.TerminalBufferPlacementTest"`
Expected: all three fail — the last row never wraps to row 0 and `mLines[row + 1]` is dereferenced
without a null check; each end state is a cell that still carries the stamp of a bitmap that was just
removed.

- [ ] **Step 3: Fix the walk**

```java
public synchronized void removeScrolledOutTerminalBitmaps(int row) {
    if (row < 0 || row >= mTotalRows) return;

    Set<Integer> bitmapsToRemove = new HashSet<>();
    collectTerminalBitmapNums(row, bitmapsToRemove);
    collectTerminalBitmapNums((row + 1) % mTotalRows, bitmapsToRemove);
    if (bitmapsToRemove.isEmpty()) return;

    for (Integer bitmapNum : bitmapsToRemove) {
        mTerminalBitmaps.remove(bitmapNum);
        removeImagePlacement(bitmapNum);
    }
    // The cells that referenced the removed bitmaps must not keep a dangling bitmap style.
    blankStampedCells(bitmapsToRemove);
}

private void collectTerminalBitmapNums(int internalRow, Set<Integer> out) {
    TerminalRow line = mLines[internalRow];
    if (line == null || !line.mHasTerminalBitmap) return;
    for (int column = 0; column < mColumns; column++) {
        int bitmapNum = TextStyle.getTerminalBitmapNum(line.getStyle(column));
        if (bitmapNum >= TERMINAL_BITMAP__NUM_START) out.add(bitmapNum);
    }
}
```

The empty-set return matters: the method runs for every scrolled row, and `blankStampedCells` walks
every row and column, so a bare call would add a full-texture scan to each scroll line.

- [ ] **Step 4: Run the module suite**

Run: `./gradlew :terminal-emulator:test`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add terminal-emulator/src/main/java/com/termux/terminal/TerminalBuffer.java \
        terminal-emulator/src/test/java/com/termux/terminal/TerminalBufferPlacementTest.java
git commit -m "Fixed(terminal-emulator): Walk scrolled-out bitmap rows safely and clear their cells"
```

---

### Task 10: Size the resize pixel array by pixels, not bytes

**Files:**
- Modify: `terminal-emulator/src/main/java/com/termux/terminal/TerminalBitmap.java:447-470`
- Test: `terminal-emulator/src/test/java/com/termux/terminal/TerminalBitmapResizeTest.java`

**Interfaces:**
- Produces: `resizeBitmap` allocates `width * height` ints and rejects oversized targets with a long
  comparison; both decisions go through package-private statics so a JVM test can pin them.
- Consumes: `MAX_BITMAP_SIZE`.

- [ ] **Step 1: Write the failing test**

```java
package com.termux.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class TerminalBitmapResizeTest {

    @Test
    public void thePixelBufferHoldsOneIntPerPixel() {
        assertEquals(16, TerminalBitmap.pixelBufferLength(4, 4));
        assertEquals(1, TerminalBitmap.pixelBufferLength(1, 1));
    }

    @Test
    public void theSizeCheckUsesLongArithmetic() {
        // 32768 * 32768 * 4 overflows int to 0, so an int check would accept a 4 GiB bitmap.
        assertTrue(TerminalBitmap.requiredBitmapBytes(32768, 32768) > TerminalBitmap.MAX_BITMAP_SIZE);
        assertEquals(64L, TerminalBitmap.requiredBitmapBytes(4, 4));
    }

    @Test
    public void growthKeepsTheSourcePixels() {
        Bitmap source = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        source.setPixel(0, 0, 0xFFFF0000);
        Bitmap resized = TerminalBitmap.resizeBitmap("test", "bitmap", null, source, 8, 8);
        assertNotNull(resized);
        assertEquals(8, resized.getWidth());
        assertEquals(8, resized.getHeight());
        assertEquals(0xFFFF0000, resized.getPixel(0, 0));
    }

    @Test
    public void anOversizedTargetIsRejectedWithoutThrowing() {
        Bitmap source = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        assertNull(TerminalBitmap.resizeBitmap("test", "bitmap", null, source, 32768, 32768));
    }
}
```

- [ ] **Step 2: Run it and see it fail**

Run: `./gradlew :terminal-emulator:testDebugUnitTest --tests "com.termux.terminal.TerminalBitmapResizeTest"`
Expected: compilation failure (`pixelBufferLength` / `requiredBitmapBytes` unresolved). The two
behaviour tests pass today; they are the regression guards for the change. Every Robolectric class in
this module needs `@ConscryptMode(OFF)`: without it the Conscrypt JNI library fails to load
`libpthread.so.0` on this host and all four tests error out.

- [ ] **Step 3: Fix the sizing**

```java
/** Bytes needed for an ARGB_8888 bitmap, in long arithmetic so the product cannot wrap. */
static long requiredBitmapBytes(int width, int height) {
    return (long) width * height * 4;
}

/** Number of {@code int} pixels in a {@code width * height} bitmap. */
static int pixelBufferLength(int width, int height) {
    return width * height;
}

public static Bitmap resizeBitmap(String logTag, String label, TerminalSessionClient client, Bitmap bitmap,
                                  int bitmapWidth, int bitmapHeight) {
    try {
        long newBitmapSize = requiredBitmapBytes(bitmapWidth, bitmapHeight);
        if (newBitmapSize > MAX_BITMAP_SIZE) { /* existing error + return null */ }

        // One int per pixel, not one int per byte.
        int[] pixels = new int[pixelBufferLength(bitmap.getWidth(), bitmap.getHeight())];
        // ... unchanged from here
```

Keep `catch (Throwable)` and the `OutOfMemoryError` `System.gc()`.

- [ ] **Step 4: Run the module suite**

Run: `./gradlew :terminal-emulator:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add terminal-emulator/src/main/java/com/termux/terminal/TerminalBitmap.java \
        terminal-emulator/src/test/java/com/termux/terminal/TerminalBitmapResizeTest.java
git commit -m "Fixed(terminal-emulator): Size the resize pixel array by pixel count"
```

---

### Task 11: Flush the pending text run at a bitmap cell

**Files:**
- Modify: `terminal-view/src/main/java/com/termux/view/TerminalRenderer.kt:215-237`
- Test: `terminal-view/src/test/java/com/termux/view/TerminalRendererRunTest.java`

**Interfaces:**
- Produces: one glyph run ends at the bitmap column and the next starts at the following column.
- Consumes: `TextStyle.encodeTerminalBitmap`, `TerminalBuffer.setChar(column, row, codePoint, style)`.

- [ ] **Step 1: Write the failing test**

```java
public void testBitmapCellEndsTheRunAndTheNextRunStartsAtTheFollowingColumn() {
    enter("AAAAAAAAAA\r\n");
    // A sixel cell in the middle of a text row: the bitmap branch draws it, not the glyph stage.
    mTerminal.getScreen().setChar(3, 0, ' ', TextStyle.encodeTerminalBitmap(0, 0, 0));
    render(null);

    // Two runs: columns 0-2, then columns 4-9. Today a third, one-column run is recorded at column 5.
    assertEquals(glyphDump(), 2, mCanvas.glyphs.size());
    assertEquals(glyphDump(), 3f, glyphStartingAtChar(0)[1], 0.001f);
    assertEquals(glyphDump(), 40f, glyphStartingAtChar(4)[2], 0.001f);
    assertEquals(glyphDump(), 6f, glyphStartingAtChar(4)[1], 0.001f);
}
```

`enter`, `render(null)`, `glyphDump()` and `glyphStartingAtChar(int)` are already in the class; the
test uses the same 10×3 terminal and `StubPaint` as its neighbours. `render(null)` disables the run
advances policy, which is what the other structural tests do.

The run before the bitmap is dropped entirely: the bitmap branch never flushes the pending run, so
the cells to its left are never drawn and the following run starts at the bitmap's own character
index. The fix closes the run before the bitmap draw and restarts it after the column is advanced.

- [ ] **Step 2: Run it and see it fail**

Run: `./gradlew :terminal-view:testDebugUnitTest --tests "com.termux.view.TerminalRendererRunTest"`
Expected: `expected:<2> but was:<1>` — only `{4,6,40.0}` is recorded, so columns 0-2 are missing.

- [ ] **Step 3: Flush, then advance, then reset**

```kotlin
if (TextStyle.isTerminalBitmap(style)) {
    // A bitmap cell is not text: close the pending run at this column, so the cells before it are
    // drawn and the run after it starts at the next column.
    if (lastRunStartColumn >= 0 && column != lastRunStartColumn) {
        val columnWidthSinceLastRun = column - lastRunStartColumn
        val charsSinceLastRun = currentCharIndex - lastRunStartIndex
        val cursorColor = if (lastRunInsideCursor) mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] else 0
        var invertCursorTextColor = false
        if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
            invertCursorTextColor = true
        }
        drawTextRun(
            canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun,
            lastRunStartIndex, charsSinceLastRun,
            cursorColor, cursorShape, lastRunStyle, reverseVideo || lastRunInsideSelection,
            invertCursorTextColor, lastRunLinkId, lastRunUnderlineColor,
        )
    }
    val bitmap = mEmulator.screen.getSixelBitmap(style)
    if (bitmap != null) {
        // ... existing draw
    }
    column += 1
    currentCharIndex += charsForCodePoint
    lastRunStyle = 0
    lastRunLinkId = 0
    lastRunUnderlineColor = TextStyle.COLOR_INDEX_FOREGROUND
    lastRunInsideCursor = false
    lastRunFontWidthMismatch = false
    lastRunStartColumn = column
    lastRunStartIndex = currentCharIndex
    continue
}
```

- [ ] **Step 4: Run the class and the module suite**

Run: `./gradlew :terminal-view:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add terminal-view/src/main/java/com/termux/view/TerminalRenderer.kt \
        terminal-view/src/test/java/com/termux/view/TerminalRendererRunTest.java
git commit -m "Fixed(terminal-view): Flush the text run before a bitmap cell"
```

---

### Task 12: Read a consistent row snapshot while rendering

**Files:**
- Modify: `terminal-emulator/src/main/java/com/termux/terminal/TerminalBuffer.java`
- Modify: `terminal-view/src/main/java/com/termux/view/TerminalRenderer.kt:193-195, 239-262`
- Test: `terminal-emulator/src/test/java/com/termux/terminal/RowSnapshotTest.java`

**Interfaces:**
- Produces: `TerminalBuffer.snapshotRow(int externalRow)` returning
  `TerminalBuffer.RowSnapshot { char[] text; long[] styles; int[] linkIds; int[] underlineColors; int spaceUsed }`.
- Consumes: `TerminalRow` package fields `mText`, `mStyle`, `mLinkIds`, `mUnderlineColors`,
  `spaceUsed`; confirm the exact field names before writing the copy.

- [ ] **Step 1: Confirm the field names**

Run: `grep -n 'mStyle\b\|mLinkIds\|mUnderlineColors\|public int spaceUsed\|int getSpaceUsed' terminal-emulator/src/main/java/com/termux/terminal/TerminalRow.java`
Expected: the four arrays and the used-characters count. Adapt the code below to the real names.

- [ ] **Step 2: Write the failing test**

```java
package com.termux.terminal;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class RowSnapshotTest {

    @Test
    public void aSnapshotDoesNotFollowLaterWrites() {
        TerminalBuffer screen = new TerminalBuffer(10, 10, 10);
        screen.setChar(0, 0, 'a', TextStyle.encode(TextStyle.COLOR_INDEX_FOREGROUND,
            TextStyle.COLOR_INDEX_BACKGROUND, TextStyle.CHARACTER_ATTRIBUTE_BOLD));

        TerminalBuffer.RowSnapshot snapshot = screen.snapshotRow(0);
        screen.setChar(0, 0, 'b', TextStyle.NORMAL);

        assertEquals('a', snapshot.text[0]);
        assertEquals(TextStyle.encode(TextStyle.COLOR_INDEX_FOREGROUND,
            TextStyle.COLOR_INDEX_BACKGROUND, TextStyle.CHARACTER_ATTRIBUTE_BOLD), snapshot.styles[0]);
    }

    @Test
    public void anUntouchedRowSnapshotsAsBlankCells() {
        TerminalBuffer screen = new TerminalBuffer(10, 10, 10);

        TerminalBuffer.RowSnapshot snapshot = screen.snapshotRow(5);

        assertEquals(' ', snapshot.text[0]);
        assertEquals(TextStyle.NORMAL, snapshot.styles[0]);
        assertEquals(10, snapshot.spaceUsed);
        assertEquals(10, snapshot.linkIds.length);
        assertEquals(10, snapshot.underlineColors.length);
    }
}
```

`TerminalBuffer(columns, screenRows, totalRows)` with all three equal keeps external and internal row
numbers identical. The second test fails today with `NullPointerException` inside a naive
`line.mLinkIds.clone()`: those arrays are allocated lazily and stay `null` on a row that never stored a
link or an underline colour.

- [ ] **Step 3: Run it and see it fail**

Run: `./gradlew :terminal-emulator:testDebugUnitTest --tests "com.termux.terminal.RowSnapshotTest"`
Expected: compilation failure, then the lazy-array `NullPointerException` once the types exist.

- [ ] **Step 4: Add the snapshot to `TerminalBuffer`**

```java
/** A copy of one row, taken under the buffer lock so a renderer never sees a half-written cell. */
public static final class RowSnapshot {
    public final char[] text;
    public final long[] styles;
    public final int[] linkIds;
    public final int[] underlineColors;
    public final int spaceUsed;

    RowSnapshot(char[] text, long[] styles, int[] linkIds, int[] underlineColors, int spaceUsed) {
        this.text = text;
        this.styles = styles;
        this.linkIds = linkIds;
        this.underlineColors = underlineColors;
        this.spaceUsed = spaceUsed;
    }
}

public synchronized RowSnapshot snapshotRow(int externalRow) {
    TerminalRow line = allocateFullLineIfNecessary(externalToInternalRow(externalRow));
    // The link and underline arrays are allocated lazily, so a row that never stored one has to be
    // expanded with the values its getters report: no link, and "no underline color".
    int[] linkIds = line.mLinkIds != null ? line.mLinkIds.clone() : new int[mColumns];
    int[] underlineColors;
    if (line.mUnderlineColors != null) {
        underlineColors = line.mUnderlineColors.clone();
    } else {
        underlineColors = new int[mColumns];
        Arrays.fill(underlineColors, TextStyle.COLOR_INDEX_FOREGROUND);
    }
    return new RowSnapshot(
        Arrays.copyOf(line.mText, line.mText.length),
        line.mStyle.clone(),
        linkIds,
        underlineColors,
        line.getSpaceUsed());
}
```

- [ ] **Step 5: Render from the snapshot**

In `TerminalRenderer.drawRows`, replace the four live reads:

```kotlin
// Taken under the buffer lock: the emulator writes rows from its own thread, and a live read could
// pair a new char with an old style (or the reverse) mid-cell.
val rowSnapshot = screen.snapshotRow(row)
val line = rowSnapshot.text
val charsUsedInLine = rowSnapshot.spaceUsed
// inside the loop:
val style = rowSnapshot.styles[column]
val linkId = rowSnapshot.linkIds[column]
val underlineColor = rowSnapshot.underlineColors[column]
```

Keep `screen.getSixelBitmap(style)` and `screen.getSixelRect(style)` as they are: both are `synchronized`
and a null bitmap is already handled.

- [ ] **Step 6: Run both modules**

Run: `./gradlew :terminal-emulator:testDebugUnitTest :terminal-view:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add terminal-emulator/src/main/java/com/termux/terminal/TerminalBuffer.java \
        terminal-emulator/src/test/java/com/termux/terminal/RowSnapshotTest.java \
        terminal-view/src/main/java/com/termux/view/TerminalRenderer.kt
git commit -m "Fixed(terminal-view): Render from a consistent row snapshot"
```

---

### Task 13: Hand focus back when the key bar collapses on the writing page

**Files:**
- Create: `app/src/main/java/com/termux/app/domain/keys/WritingPageFocus.kt`
- Test: `app/src/test/java/com/termux/app/domain/keys/WritingPageFocusTest.kt`
- Modify: `app/src/main/java/com/termux/app/ui/keys/ExtraKeysBar.kt:152-160`

**Interfaces:**
- Produces: `internal fun shouldHandBackFocus(previousWriting: Boolean, writing: Boolean): Boolean`.
- Consumes: nothing.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.termux.app.domain.keys

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WritingPageFocusTest {

    @Test
    fun `leaving the writing page hands focus back`() {
        assertTrue(shouldHandBackFocus(previousWriting = true, writing = false))
    }

    @Test
    fun `staying or arriving does not`() {
        assertFalse(shouldHandBackFocus(previousWriting = true, writing = true))
        assertFalse(shouldHandBackFocus(previousWriting = false, writing = true))
        assertFalse(shouldHandBackFocus(previousWriting = false, writing = false))
    }
}
```

- [ ] **Step 2: Run it and see it fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.domain.keys.WritingPageFocusTest"`
Expected: compilation failure.

- [ ] **Step 3: Add the seam and use it**

```kotlin
package com.termux.app.domain.keys

/**
 * True when the writing page stopped being visible. The pager can settle on the writing page while
 * the bar collapses (`keysVisible` false), so the page index alone cannot decide this.
 */
internal fun shouldHandBackFocus(previousWriting: Boolean, writing: Boolean): Boolean =
    previousWriting && !writing
```

In `ExtraKeysBar`:

```kotlin
if (shouldHandBackFocus(previousWriting, writing)) onLeaveWritingPage()
```

Delete the `page != ExtraKeysState.PAGE_WRITING` condition and leave the rest of the collector alone.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.domain.keys.WritingPageFocusTest" :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/termux/app/domain/keys/WritingPageFocus.kt \
        app/src/main/java/com/termux/app/ui/keys/ExtraKeysBar.kt \
        app/src/test/java/com/termux/app/domain/keys/WritingPageFocusTest.kt
git commit -m "Fixed(app): Return terminal focus when the key bar collapses from the writing page"
```

---

### Task 14: Cap the snippet editor and surface the limit

**Files:**
- Modify: `app/src/main/java/com/termux/app/ui/snippets/SnippetsState.kt:29-46`
- Modify: `app/src/main/java/com/termux/app/ui/snippets/SnippetsScreen.kt:60-90`
- Modify: `app/src/main/res/values/strings.xml` (after `snippets_add`)
- Test: `app/src/test/java/com/termux/app/ui/snippets/SnippetsStateTest.kt`

**Interfaces:**
- Produces: `SnippetsState.atLimit: Boolean`, `add(...)`/`addBlank()` no-ops at the cap.
- Consumes: `MAX_SNIPPETS` from `com.termux.app.domain.snippets`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `the editor stops at the store cap`() {
    val state = SnippetsState(store)
    repeat(MAX_SNIPPETS) { state.addBlank() }
    assertTrue(state.atLimit)
    assertFalse(state.add("label", "command"))
    assertEquals(MAX_SNIPPETS, state.items.size)
}

@Test
fun `saving more rows than the cap keeps the cap and drops the extra`() {
    val state = SnippetsState(store)
    repeat(MAX_SNIPPETS + 3) { state.addBlank() }
    assertEquals(MAX_SNIPPETS, state.save().size)
}
```

- [ ] **Step 2: Run them and see them fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.ui.snippets.SnippetsStateTest"`
Expected: the first test fails (21 rows accepted), the second fails or hangs on the intended behaviour.

- [ ] **Step 3: Enforce the cap in the state**

```kotlin
/** True when no more rows can be added; the store keeps at most [MAX_SNIPPETS]. */
val atLimit: Boolean
    get() = items.size >= MAX_SNIPPETS

fun add(label: String, command: String): Boolean {
    if (atLimit) return false
    val trimmedLabel = label.trim()
    val trimmedCommand = command.trim()
    if (trimmedLabel.isEmpty() || trimmedCommand.isEmpty()) return false
    items = items + Snippet(id = newDraftId(), label = trimmedLabel, command = trimmedCommand)
    return true
}

fun addBlank(): Boolean {
    if (atLimit) return false
    items = items + Snippet(id = newDraftId(), label = "", command = "")
    return true
}
```

Update the existing callers in the test and in `SnippetsScreen` (`Button(onClick = { state.addBlank() })`).

- [ ] **Step 4: Show the limit in the screen**

Add to `strings.xml`:

```xml
<string name="snippets_limit">At most %1$d snippets are kept.</string>
```

Under the list, above the Add row:

```kotlin
if (state.atLimit) {
    Text(
        text = stringResource(R.string.snippets_limit, MAX_SNIPPETS),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}
Button(onClick = { state.addBlank() }, enabled = !state.atLimit) { Text(stringResource(R.string.snippets_add)) }
```

Import `com.termux.app.domain.snippets.MAX_SNIPPETS`.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.ui.snippets.SnippetsStateTest" :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/termux/app/ui/snippets/ \
        app/src/main/res/values/strings.xml \
        app/src/test/java/com/termux/app/ui/snippets/SnippetsStateTest.kt
git commit -m "Changed(app): Cap the snippet editor at the stored limit and say so"
```

---

### Task 15: Bound the iTerm colour import

**Files:**
- Create: `app/src/main/java/com/termux/app/domain/appearance/BoundedRead.kt`
- Test: `app/src/test/java/com/termux/app/domain/appearance/BoundedReadTest.kt`
- Modify: `app/src/main/java/com/termux/app/domain/appearance/FontValidation.kt:13`
- Modify: `app/src/main/java/com/termux/app/ui/appearance/AppearanceActivity.kt:60-72`

**Interfaces:**
- Produces: `internal fun readBounded(input: InputStream?, limit: Long): String?` and
  `const val MAX_ITERM_BYTES = 256L * 1024`.
- Consumes: nothing.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.termux.app.domain.appearance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class BoundedReadTest {

    @Test
    fun `small input is read whole`() {
        assertEquals("<plist/>", readBounded(ByteArrayInputStream("<plist/>".toByteArray()), 64))
    }

    @Test
    fun `oversized input is refused`() {
        val big = ByteArray(100) { 'a'.code.toByte() }
        assertNull(readBounded(ByteArrayInputStream(big), 64))
    }

    @Test
    fun `no stream is refused`() {
        assertNull(readBounded(null, 64))
    }
}
```

- [ ] **Step 2: Run it and see it fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.domain.appearance.BoundedReadTest"`
Expected: compilation failure.

- [ ] **Step 3: Add the seam**

```kotlin
package com.termux.app.domain.appearance

import java.io.InputStream

/** Reads at most [limit] bytes of UTF-8 text; `null` when the stream is absent or larger. */
internal fun readBounded(input: InputStream?, limit: Long): String? {
    if (input == null) return null
    val buffer = ByteArray(8192)
    val out = java.io.ByteArrayOutputStream()
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        out.write(buffer, 0, read)
        if (out.size() > limit) return null
    }
    return out.toString(Charsets.UTF_8.name())
}
```

- [ ] **Step 4: Use it for the import**

In `FontValidation.kt`: `const val MAX_ITERM_BYTES = 256L * 1024`.

In `AppearanceActivity`:

```kotlin
val xml = runCatching {
    contentResolver.openInputStream(uri)?.use { readBounded(it, FontValidation.MAX_ITERM_BYTES) }
}.getOrNull()
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.domain.appearance.BoundedReadTest" :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/termux/app/domain/appearance/ \
        app/src/main/java/com/termux/app/ui/appearance/AppearanceActivity.kt \
        app/src/test/java/com/termux/app/domain/appearance/BoundedReadTest.kt
git commit -m "Fixed(app): Bound the iTerm colour import to a fixed size"
```

---

### Task 16: Release pending font files when the screen goes away

**Files:**
- Create: `app/src/main/java/com/termux/app/domain/appearance/PendingFontFile.kt`
- Test: `app/src/test/java/com/termux/app/domain/appearance/PendingFontFileTest.kt`
- Modify: `app/src/main/java/com/termux/app/ui/appearance/AppearanceActivity.kt:40-62, 82-110`

**Interfaces:**
- Produces: `internal class PendingFontFile { var file: File?; fun replace(next: File); fun clear() }`.
- Consumes: `File.delete`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.termux.app.domain.appearance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PendingFontFileTest {

    @Test
    fun `replacing a candidate deletes the previous one`() {
        val first = File.createTempFile("font-a", ".ttf").apply { writeText("a") }
        val second = File.createTempFile("font-b", ".ttf").apply { writeText("b") }
        val pending = PendingFontFile()

        pending.replace(first)
        pending.replace(second)

        assertFalse(first.exists())
        assertTrue(second.exists())
        assertNotNull(pending.file)
    }

    @Test
    fun `clearing deletes the current candidate`() {
        val file = File.createTempFile("font-c", ".ttf").apply { writeText("c") }
        val pending = PendingFontFile()
        pending.replace(file)
        pending.clear()
        assertFalse(file.exists())
        assertNull(pending.file)
    }
}
```

- [ ] **Step 2: Run it and see it fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.domain.appearance.PendingFontFileTest"`
Expected: compilation failure.

- [ ] **Step 3: Add the holder**

```kotlin
package com.termux.app.domain.appearance

import java.io.File

/**
 * The font picked but not yet installed. Each pick copies into a fresh cache file, so the holder
 * deletes the file it replaces and the one it abandons when the screen closes or applies.
 */
internal class PendingFontFile {
    var file: File? = null
        private set

    fun replace(next: File) {
        clear()
        file = next
    }

    fun clear() {
        file?.delete()
        file = null
    }
}
```

- [ ] **Step 4: Wire it in `AppearanceActivity`**

```kotlin
private val pendingFontCandidate = PendingFontFile()
```

In `pickFont`: `pendingFontCandidate.replace(candidate)` and `controller.chooseFont(candidate)`; on the
validation failure path `candidate.delete()` becomes `pendingFontCandidate.clear()`.

In `onAppearanceApplied`: call `pendingFontCandidate.clear()` before `finish()`.

Add:

```kotlin
override fun onDestroy() {
    pendingFontCandidate.clear()
    super.onDestroy()
}
```

Leave `AppearanceScreen.pendingFontSource` alone: it stages the path and is cleared by `apply()` /
`discard()`.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.domain.appearance.PendingFontFileTest" :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/termux/app/domain/appearance/PendingFontFile.kt \
        app/src/main/java/com/termux/app/ui/appearance/AppearanceActivity.kt \
        app/src/test/java/com/termux/app/domain/appearance/PendingFontFileTest.kt
git commit -m "Fixed(app): Delete pending font cache files when the appearance screen closes"
```

---

### Task 17: Do not follow directory symlinks while collecting old files

**Files:**
- Modify: `termux-shared/src/main/java/com/termux/shared/file/FileUtils.java:2067-2081`
- Test: `termux-shared/src/test/java/com/termux/shared/file/FileUtilsTest.java`

**Interfaces:**
- Produces: `collectFilesOlderThan` recurses only into directories whose canonical parent is the
  current directory.
- Consumes: `java.io` only in main sources.

- [ ] **Step 1: Write the failing test**

```java
@Test
public void deleteFilesOlderThanXDaysDoesNotFollowDirectorySymlinks() throws Exception {
    File root = Files.createTempDirectory("old-files").toFile();
    File outside = Files.createTempDirectory("outside").toFile();
    File victim = new File(outside, "keep.txt");
    assertTrue(victim.createNewFile());
    assertTrue(victim.setLastModified(0));

    File link = new File(root, "link");
    Files.createSymbolicLink(link.toPath(), outside.toPath());

    FileUtils.deleteFilesOlderThanXDays("test", root.getAbsolutePath, null, 1, true,
        FileType.REGULAR.getValue());

    assertTrue("a file reached through a symlink must survive", victim.exists());
}
```

Add the `java.nio.file.Files` import to the test only. Production code stays on `java.io`.

- [ ] **Step 2: Run it and see it fail**

Run: `./gradlew :termux-shared:test --tests "com.termux.shared.file.FileUtilsTest"`
Expected: `victim` is deleted.

Note: today `deleteFilesOlderThanXDays` only recurses when `dirFilter != null`, and this call passes
`null`. Keep the test but pass a filter that accepts everything
(`dirFilter = File::isDirectory`), cast to `FileFilter`:

```java
FileFilter allDirs = File::isDirectory;
FileUtils.deleteFilesOlderThanXDays("test", root.getAbsolutePath(), allDirs, 1, true,
    FileType.REGULAR.getValue());
```

- [ ] **Step 3: Skip entries reached through a link**

```java
private static void collectFilesOlderThan(@NonNull File directory, long cutoffTime,
                                          @Nullable FileFilter dirFilter, @NonNull List<File> filesToDelete) {
    File[] files = directory.listFiles();
    if (files == null)
        return;

    String canonicalDirectory;
    try {
        canonicalDirectory = directory.getCanonicalPath();
    } catch (IOException e) {
        return; // Fail closed: an unresolvable directory cannot be proven to be the same one.
    }

    for (File file : files) {
        try {
            // A symlinked directory's canonical parent is its target's parent, so it is skipped;
            // the same test keeps files under a symlinked path prefix.
            String canonicalEntry = file.getCanonicalPath();
            if (!canonicalDirectory.equals(new File(canonicalEntry).getParent())) continue;
        } catch (IOException e) {
            continue;
        }
        if (file.isDirectory()) {
            if (dirFilter != null && dirFilter.accept(file))
                collectFilesOlderThan(file, cutoffTime, dirFilter, filesToDelete);
        } else if (file.lastModified() <= cutoffTime) {
            filesToDelete.add(file);
        }
    }
}
```

Add `import java.io.IOException;` if it is not already present.

- [ ] **Step 4: Run the module suite**

Run: `./gradlew :termux-shared:test`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add termux-shared/src/main/java/com/termux/shared/file/FileUtils.java \
        termux-shared/src/test/java/com/termux/shared/file/FileUtilsTest.java
git commit -m "Fixed(termux-shared): Skip symlinked directories when collecting old files"
```

---

### Task 18: Let a zero tint clear the selection handles

**Files:**
- Create: `terminal-view/src/main/java/com/termux/view/textselection/HandleTintList.kt`
- Test: `terminal-view/src/test/java/com/termux/view/textselection/HandleTintListTest.java`
- Modify: `terminal-view/src/main/java/com/termux/view/textselection/TextSelectionHandleView.kt:104-108`

**Interfaces:**
- Produces: `internal object HandleTintList { fun tintOrNull(tint: Int): Int? }` — pure, so it can be
  tested in `:terminal-view` without Robolectric.
- Consumes: `android.content.res.ColorStateList` (only in the view, not in the tested seam).

- [ ] **Step 1: Write the failing test**

```java
package com.termux.view.textselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** A zero tint must clear a previous tint, not keep it. */
public class HandleTintListTest {

    @Test
    public void zeroMeansClear() {
        assertNull(HandleTintList.INSTANCE.tintOrNull(0));
    }

    @Test
    public void anOpaqueColourIsKept() {
        assertEquals(Integer.valueOf(0xFF00FF00), HandleTintList.INSTANCE.tintOrNull(0xFF00FF00));
    }
}
```

`:terminal-view` has no Robolectric, so the seam must not touch `ColorStateList`; keep that call in
`TextSelectionHandleView`.

- [ ] **Step 2: Run it and see it fail**

Run: `./gradlew :terminal-view:test --tests "com.termux.view.textselection.HandleTintListTest"`
Expected: compilation failure.

- [ ] **Step 3: Add the seam and use it**

```kotlin
package com.termux.view.textselection

/** The tint to apply, or `null` to clear; 0 is the "no tint" value the callers pass. */
internal object HandleTintList {
    fun tintOrNull(tint: Int): Int? = if (tint == 0) null else tint
}
```

In `applyHandleTint`:

```kotlin
private fun applyHandleTint(tint: Int) {
    val tintList = HandleTintList.tintOrNull(tint)?.let { ColorStateList.valueOf(it) }
    mHandleLeftDrawable!!.mutate().setTintList(tintList)
    mHandleRightDrawable!!.mutate().setTintList(tintList)
}
```

Add `import android.content.res.ColorStateList`, delete the `if (tint == 0) return` early exit, and
update the KDoc to say 0 removes the tint.

- [ ] **Step 4: Run the module suite**

Run: `./gradlew :terminal-view:test`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add terminal-view/src/main/java/com/termux/view/textselection/ \
        terminal-view/src/test/java/com/termux/view/textselection/HandleTintListTest.java
git commit -m "Fixed(terminal-view): Clear the selection handle tint when the value is zero"
```

---

### Task 19: Contain wallpaper decode failures

**Files:**
- Modify: `app/src/main/java/com/termux/app/data/WallpaperSource.kt:58-87`
- Test: `app/src/test/java/com/termux/app/data/WallpaperSourceTest.kt`

**Interfaces:**
- Produces: `customImage` returns `null` for a missing path, a non-image file and a decode error.
- Consumes: `AndroidWallpaperSource(context)`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.termux.app.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class WallpaperSourceTest {

    private val source = AndroidWallpaperSource(ApplicationProvider.getApplicationContext())

    @Test
    fun `a missing path yields no bitmap`() {
        assertNull(source.customImage("/no/such/image.png"))
        assertNull(source.customImage(null))
        assertNull(source.customImage(""))
    }

    @Test
    fun `a file that is not an image yields no bitmap`() {
        val file = File.createTempFile("wallpaper", ".png").apply { writeText("not a png") }
        assertNull(source.customImage(file.absolutePath))
    }
}
```

If `androidx.test.core` is not on the `:app` test classpath, use
`RuntimeEnvironment.getApplication()` from Robolectric instead; check the existing
`app/src/test/java/com/termux/app/ui/appearance/AppearanceActivityTest.kt` for the pattern it uses.

- [ ] **Step 2: Run it and see it fail or pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.data.WallpaperSourceTest"`
Expected: passes today for the missing path; the non-image case may pass through `decodeFile`
returning `null`. If both already pass, keep the tests as the regression gate and skip Step 3's
behaviour change; the remaining gap is the `Error` path.

- [ ] **Step 3: Widen the catch**

```kotlin
override fun customImage(path: String?): Bitmap? {
    if (path.isNullOrEmpty()) return null
    return try {
        // ... existing decode
    } catch (e: OutOfMemoryError) {
        System.gc()
        com.termux.shared.logger.Logger.logStackTraceWithMessage(LOG_TAG, "Not enough memory to load the custom background", e)
        null
    } catch (t: Throwable) {
        com.termux.shared.logger.Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load the custom terminal background image", t)
        null
    }
}
```

- [ ] **Step 4: Run the module suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/termux/app/data/WallpaperSource.kt \
        app/src/test/java/com/termux/app/data/WallpaperSourceTest.kt
git commit -m "Fixed(app): Contain wallpaper decode failures including out-of-memory"
```

---

### Task 20: Cache the foreground command between session refreshes

**Files:**
- Create: `app/src/main/java/com/termux/app/domain/sessions/ForegroundCommandCache.kt`
- Test: `app/src/test/java/com/termux/app/domain/sessions/ForegroundCommandCacheTest.kt`
- Modify: `app/src/main/java/com/termux/app/TermuxActivity.kt:930`

**Interfaces:**
- Produces:
  `internal class ForegroundCommandCache(ttlMillis: Long = 1000L, clock: () -> Long = { SystemClock.elapsedRealtime() }, read: (Int) -> String? = ::readForegroundCommand) { fun command(pid: Int): String? }`.
- Consumes: `readForegroundCommand`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.termux.app.domain.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundCommandCacheTest {

    @Test
    fun `a value is reused within the ttl`() {
        var reads = 0
        var now = 0L
        val cache = ForegroundCommandCache(ttlMillis = 1000L, clock = { now }) {
            reads++
            "vim"
        }

        assertEquals("vim", cache.command(10))
        now = 900L
        assertEquals("vim", cache.command(10))
        assertEquals(1, reads)
    }

    @Test
    fun `a value is re-read after the ttl and on a pid change`() {
        var reads = 0
        var now = 0L
        val cache = ForegroundCommandCache(ttlMillis = 1000L, clock = { now }) { pid ->
            reads++
            if (pid == 10) "vim" else "top"
        }

        cache.command(10)
        now = 1001L
        cache.command(10)
        assertEquals(2, reads)

        cache.command(11)
        assertEquals(3, reads)
        assertEquals("top", cache.command(11))
    }

    @Test
    fun `a missing command is cached as well`() {
        var reads = 0
        val cache = ForegroundCommandCache(ttlMillis = 1000L, clock = { 0L }) {
            reads++
            null
        }
        assertNull(cache.command(10))
        assertNull(cache.command(10))
        assertEquals(1, reads)
    }
}
```

- [ ] **Step 2: Run it and see it fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.domain.sessions.ForegroundCommandCacheTest"`
Expected: compilation failure.

- [ ] **Step 3: Add the cache**

```kotlin
package com.termux.app.domain.sessions

import android.os.SystemClock

/**
 * Keeps the last foreground-command read per process for a short while. The tab bar refreshes on
 * every session change, and each read walks `/proc`; the label does not need to be fresher than a
 * second.
 */
internal class ForegroundCommandCache(
    private val ttlMillis: Long = 1000L,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val read: (Int) -> String? = { readForegroundCommand(it) },
) {
    private var pid = -1
    private var value: String? = null
    private var readAt = Long.MIN_VALUE

    fun command(pid: Int): String? {
        val now = clock()
        if (pid == this.pid && now - readAt < ttlMillis) return value
        val fresh = read(pid)
        this.pid = pid
        value = fresh
        readAt = now
        return fresh
    }
}
```

`SystemClock.elapsedRealtime()` in a JVM test is stubbed by Robolectric; the tests inject a clock, so
they need no runner.

- [ ] **Step 4: Use it in `TermuxActivity`**

Add a field `private val mForegroundCommands = ForegroundCommandCache()` and change line 930:

```kotlin
if (isActive) mForegroundCommands.command(terminalSession.getPid()) else null,
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.domain.sessions.ForegroundCommandCacheTest" :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/termux/app/domain/sessions/ForegroundCommandCache.kt \
        app/src/main/java/com/termux/app/TermuxActivity.kt \
        app/src/test/java/com/termux/app/domain/sessions/ForegroundCommandCacheTest.kt
git commit -m "Changed(app): Cache the foreground command between session refreshes"
```

---

### Task 21: Wire `keycap-visuals` and drop the dead preference keys

**Files:**
- Modify: `termux-shared/src/main/java/com/termux/shared/termux/settings/properties/TermuxPropertyConstants.java:126-165, 390-472`
- Modify: `termux-shared/src/main/java/com/termux/shared/termux/settings/properties/TermuxSharedProperties.java:601-603`
- Modify: `termux-shared/src/main/java/com/termux/shared/termux/extrakeys/ExtraKeysView.java:192-196`
- Modify: `app/src/main/java/com/termux/app/TermuxActivity.kt:364, 1601-1605`
- Modify: `app/src/main/java/com/termux/app/data/ui/UiPreferences.kt:23, 27, 29, 30`
- Test: `termux-shared/src/test/java/com/termux/shared/termux/settings/properties/KeycapVisualsPropertyTest.java`

**Interfaces:**
- Produces: `TermuxPropertyConstants.KEY_KEYCAP_VISUALS` (`"keycap-visuals"`) in
  `TERMUX_APP_PROPERTIES_LIST` and `TERMUX_DEFAULT_TRUE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST`;
  `TermuxSharedProperties.shouldKeycapVisualsBeEnabled()`.
- Consumes: `ExtraKeysView.setKeycapVisuals`.

- [ ] **Step 1: Write the failing test**

```java
package com.termux.shared.termux.settings.properties;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The keycap drawing flag is only reachable if the loader knows the key and its default. */
public class KeycapVisualsPropertyTest {

    @Test
    public void theKeyIsLoadedAndDefaultsToTrue() {
        String key = TermuxPropertyConstants.KEY_KEYCAP_VISUALS;
        assertTrue(TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST.contains(key));
        assertTrue(TermuxPropertyConstants.TERMUX_DEFAULT_TRUE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST.contains(key));
    }
}
```

- [ ] **Step 2: Run it and see it fail**

Run: `./gradlew :termux-shared:test --tests "com.termux.shared.termux.settings.properties.KeycapVisualsPropertyTest"`
Expected: compilation failure.

- [ ] **Step 3: Add the constant, the lists and the getter**

In `TermuxPropertyConstants`, next to `KEY_EXTRA_KEYS_TEXT_ALL_CAPS`:

```java
public static final String KEY_KEYCAP_VISUALS = "keycap-visuals"; // Default: "keycap-visuals"
```

Add it to `TERMUX_APP_PROPERTIES_LIST` and to `TERMUX_DEFAULT_TRUE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST`,
and extend the class Javadoc's "add a key" note the same way `KEY_EXTRA_KEYS_TEXT_ALL_CAPS` is listed.

In `TermuxSharedProperties`, below `shouldExtraKeysTextBeAllCaps()`:

```java
public boolean shouldKeycapVisualsBeEnabled() {
    return (boolean) getInternalPropertyValue(TermuxPropertyConstants.KEY_KEYCAP_VISUALS, true);
}
```

- [ ] **Step 4: Read it in the activity**

At `TermuxActivity.kt:364`, after `setButtonTextAllCaps`:

```kotlin
view.setKeycapVisuals(mProperties.shouldKeycapVisualsBeEnabled())
```

In `reloadActivityStyling`, next to the all-caps pair:

```kotlin
val keycapVisuals = mProperties.shouldKeycapVisualsBeEnabled()
mExtraKeysView?.setKeycapVisuals(keycapVisuals)
mExtraKeysView2?.setKeycapVisuals(keycapVisuals)
```

Fix the `ExtraKeysView` field comment: "Fed from the `keycap-visuals` property by the activity; the
default keeps them on."

- [ ] **Step 5: Remove the four dead keys**

Delete `ACTIVITY_BADGES`, `KEYCAP_VISUALS`, `VISUAL_BELL` and `FIRST_RUN_TIPS` from
`UiPreferenceKeys`. They have no reader anywhere in the app; `keycap-visuals` now lives in
`termux.properties`, where the engine and the settings documentation can see it.

- [ ] **Step 6: Run both module suites**

Run: `./gradlew :termux-shared:test :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add termux-shared/src/main/java/com/termux/shared/termux/ \
        termux-shared/src/test/java/com/termux/shared/termux/settings/properties/KeycapVisualsPropertyTest.java \
        app/src/main/java/com/termux/app/TermuxActivity.kt \
        app/src/main/java/com/termux/app/data/ui/UiPreferences.kt
git commit -m "Changed(termux-shared): Read the keycap-visuals property and drop the unused UI keys"
```

---

### Task 22: Bump the version and record the outcome

**Files:**
- Modify: `app/build.gradle:55-56`
- Modify: `docs/ledger/outstanding-work.md` (§3, new subsection at the end of the code-and-test gaps)
- Modify: `docs/ledger/manual-checks.md` (status table and a new section)

**Interfaces:**
- Produces: `versionCode 122`, `versionName "2.6.1"`; one ledger entry; six manual checks.

- [ ] **Step 1: Bump**

```groovy
versionCode 122
versionName "2.6.1"
```

- [ ] **Step 2: Record the ledger entry**

At the end of §3 in `docs/ledger/outstanding-work.md`:

```markdown
### 3.x Post-review hardening — closed 2026-11-17

- `[done 2026-11-17]` the 20 code findings of the `v2.0.0-beta..e3f68eb1` review are fixed in
  `plans/2026-11-17-post-review-hardening.md`: received-file containment and one-shot handling,
  report-info read containment, documents-provider containment, broadcast export flags, sixel
  capacity, bitmap row walking and resize sizing, renderer run flush and row snapshots, key-bar
  focus handback, snippet cap, bounded iTerm import, pending-font cleanup, symlink walk, handle tint,
  wallpaper error containment, foreground-command cache, `keycap-visuals` wiring.
- `[non-goal]` rewriting the seven historical commits that do not use the capitalized conventional
  types: the messages are in the past and rewriting `master` would break every clone; the rule is
  enforced from here on.
- The settings-search defect stays `[open 2026-11-17]` above; it is independent of this review.
```

- [ ] **Step 3: Add the manual checks**

In the `manual-checks.md` status table, append:

```markdown
| D-21 | Received file with a hostile display name is refused and nothing is written outside `~/downloads` | pending | §19 below |
| D-22 | Absurd sixel raster size neither crashes nor stalls the terminal | pending | §19 below |
| D-23 | Text either side of a sixel cell renders with no missing, doubled or shifted cell | pending | §19 below |
| D-24 | `keycap-visuals = false` removes the keycap inset and the popup chevron | pending | §19 below |
| D-25 | A share is saved and opened exactly once (no second editor launch on resume) | pending | §19 below |
| D-26 | The snippet screen stops at 20 rows and shows the limit line | pending | §19 below |
```

and a `## 19. Post-review hardening` section listing each check as a numbered step with its
reproduction command or tap path.

- [ ] **Step 4: Verify the version gate**

Run: `./gradlew :app:versionName`
Expected: `2.6.1`.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle docs/ledger/outstanding-work.md docs/ledger/manual-checks.md
git commit -m "Changed(app): Bump to 2.6.1 and record the post-review hardening"
```

---

### Task 23: Full verification and final review

**Files:**
- Modify: none (fix anything the gates find inside the task that owns it)

**Interfaces:**
- Produces: a green tree and a review pass over the whole branch.

- [ ] **Step 1: Run every unit test**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`; the test count is higher than the Task 1 baseline by the new classes.

- [ ] **Step 2: Build the debug APK**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`; `app/build/outputs/apk/debug/` contains an APK. Do not `clean`.

- [ ] **Step 3: Check the diff for accidental scope**

Run: `git diff --stat v2.6.0..HEAD` and `git log --oneline v2.6.0..HEAD`
Expected: one commit per finding, every subject matching
`^(Added|Changed|Deprecated|Removed|Fixed|Security)\([a-z-]+\): [A-Z]`.

- [ ] **Step 4: Review the adversarial paths once more**

For each of F1-F6, read the final code and confirm the rejection is explicit (an error dialog, a
`return`, or a thrown `FileNotFoundException`) rather than a silent rewrite or a fallback that
trusts the input. Record the file:line of each check in the final commit body.

- [ ] **Step 5: Hand over the device phase**

Point the user at `docs/ledger/manual-checks.md` D-21..D-26. Do not claim the branch is done until
Steps 1-3 pass and the user accepts that the device checks are pending.
