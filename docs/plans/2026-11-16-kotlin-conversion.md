# Kotlin Conversion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert `:terminal-view` (18 files) and the remaining Java in `:app` (31 files) to Kotlin
with no behavior change and no change to any Java-visible API.

**Architecture:** File-by-file hand translation under a fixed ruleset, in dependency order. Existing
Java unit tests are the behavioral oracle and never change. The Java-visible surface is proven by
compiling Java consumers after every batch, not by inspection.

**Tech Stack:** Kotlin 2.4.21, AGP 9.1.1 built-in Kotlin, JUnit 4, Robolectric (`:app` only).

**Spec:** `docs/specs/2026-11-16-kotlin-conversion-design.md`

## Global Constraints

- Files stay in `src/main/java/`; only the extension changes. No `src/main/kotlin` directory.
- `terminal-view/build.gradle`: remove `enableKotlin = false`; add `jvmDefault = JvmDefaultMode.ALL_COMPATIBILITY`.
- No public class, method, field or package is renamed. No `getX()`/`setX()` becomes a property on a
  class that Java calls.
- `internal` never appears on anything Java calls (Kotlin mangles those names).
- Never run `./gradlew clean` — it deletes the cached `bootstrap-*.zip` files (~115 MB re-download).
- Test sources stay Java. No assertion changes.
- Commit type is `Changed`, capitalized, present tense: `Changed(terminal-view): Convert LinkUris to Kotlin`.
- Every batch ends green and is one revertable commit.

## Review Focus

- Text-selection handle dragging and long-press selection on a real device — no unit test reaches
  `TextSelectionHandleView` positioning. Owned by Task 3, checked via `docs/ledger/manual-checks.md`.
- IME composing-text editing (`deleteSurroundingText`, `finishComposingText`) — Android-only paths in
  `TerminalView`'s anonymous `InputConnection`. Owned by Task 5, manual check.
- Bootstrap extraction through the JNI symbol `Java_com_termux_app_TermuxInstaller_getZip` — a Kotlin
  companion must still emit that static method. Owned by Task 8, compile plus APK smoke.
- Cursor blink clamping at the `TERMINAL_CURSOR_BLINK_RATE_MIN/MAX` bounds, consumed as compile-time
  constants by `TermuxPropertyConstants` — the only cross-module Java constant dependency. Owned by
  Task 5.
- Link tap on unusual URI schemes — `LinkUrisTest` and `TerminalViewLinkHitTest` cover it; they must
  stay green through Tasks 2 and 5.

---

### Task 1: Build wiring and the interface with a default method

Proves the `all-compatibility` interop in one file before any bulk work.

**Files:**
- Modify: `terminal-view/build.gradle:6` (remove `enableKotlin = false`), add `kotlin {}` block
- Create: `terminal-view/src/main/java/com/termux/view/TerminalViewClient.kt`
- Delete: `terminal-view/src/main/java/com/termux/view/TerminalViewClient.java`
- Oracle test: none (proven by Java compile of the implementor)

**Interfaces:**
- Produces: `interface TerminalViewClient` with the same 23 members, including
  `fun onLinkClicked(uri: String) {}` (nullable-free body). Consumed by
  `TermuxTerminalViewClientBase` (Java, `:termux-shared`) and `TermuxTerminalViewClient` (Java, `:app`).

- [ ] **Step 1: Edit `terminal-view/build.gradle`**

Remove the `enableKotlin = false` line and add, inside `android {}`:

```gradle
kotlin {
    compilerOptions {
        jvmDefault = JvmDefaultMode.ALL_COMPATIBILITY
    }
}
```

- [ ] **Step 2: Convert the interface**

Translate all members verbatim (signature shapes unchanged). `onLinkClicked` keeps an empty body so it
stays a default method. All parameters are non-null: the Java bodies never pass null.

- [ ] **Step 3: Prove the Java implementor still binds**

Run: `./gradlew :termux-shared:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL. If `TermuxTerminalViewClientBase` complains about `onLinkClicked` being
abstract, the `jvmDefault` block is missing or misplaced — fix that, not the interface.

- [ ] **Step 4: Run the module test suite**

Run: `./gradlew :terminal-view:test`
Expected: BUILD SUCCESSFUL, all 15 test classes pass.

- [ ] **Step 5: Commit**

```bash
git add -A terminal-view/build.gradle terminal-view/src/main/java/com/termux/view
git commit -m "Changed(terminal-view): Enable Kotlin and convert TerminalViewClient"
```

---

### Task 2: Leaf classes with no Java consumers

**Files (convert in this order):**
`LinkUris.java` (40), `KeyCodePoints.java` (63), `TerminalGeometry.java` (40), `CursorFx.java` (39),
`RunAdvancesSource.java` (33), `RenderBand.java` (47), `ShapedRunPolicy.java` (57),
`RowInvalidation.java` (141), `ImeTextEncoder.java` (93), `HandleTint.java` (57),
`support/PopupWindowCompatGingerbread.java` (75) — all under
`terminal-view/src/main/java/com/termux/view/`.

**Oracle tests:** `LinkUrisTest`, `KeyCodePointsTest`, `TerminalGeometryTest`, `CursorFxTest`,
`RenderBandTest`, `ShapedRunPolicyTest`, `RowInvalidationTest`, `RowInvalidationBufferTest`,
`ImeTextEncoderTest`, `HandleTintTest`.

**Interfaces:**
- Produces, Java-visible and unchanged: `LinkUris.sanitize(uri: String): String`,
  `HandleTint.handleTint(terminalBackground: Int, shellAccent: Int): Int` (both `object` + `@JvmStatic`),
  `PopupWindowCompatGingerbread.setWindowLayoutType(...)` / `.getWindowLayoutType(...)`.
- Consumes: nothing from Task 1.

- [ ] **Step 1: Convert the 11 files**

Per-file notes the ruleset does not decide:

- `LinkUris`, `HandleTint`, `PopupWindowCompatGingerbread` are static-only holders → `object`, each
  member `@JvmStatic`. Keep the classes' names and package.
- `PopupWindowCompatGingerbread` remains a public name even though it is `api`-invisible; no
  deprecation is added.
- `RunAdvancesSource` is declared package-private today. Make it a plain top-level `interface`
  (public), never `internal`: Java classes implement it in this batch, and Kotlin mangles `internal`
  member names so those overrides would stop binding.
- `RowInvalidation.Result` and `RowInvalidation.Decision` are nested types → nested (`Decision` was
  `static final`, so no `inner`).
- `ImeTextEncoder.KeyStroke` was `static final` → nested class.
- `KeyCodePoints` and `ImeTextEncoder` each contain a `switch` over code points → `when`.
- `CursorFx`, `RenderBand`, `ShapedRunPolicy`, `TerminalGeometry` are small data holders used per
  frame: no nullable primitives, no boxing, no `Array<Int>`.

- [ ] **Step 2: Run the oracle tests**

Run: `./gradlew :terminal-view:test --tests "*LinkUrisTest" --tests "*KeyCodePointsTest" --tests "*TerminalGeometryTest" --tests "*CursorFxTest" --tests "*RenderBandTest" --tests "*ShapedRunPolicyTest" --tests "*RowInvalidation*" --tests "*ImeTextEncoderTest" --tests "*HandleTintTest"`
Expected: all PASS. A failure here means a translation changed behavior — fix the translation, never
the test.

- [ ] **Step 3: Prove the Java binding**

Run: `./gradlew :termux-shared:compileDebugJavaWithJavac :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. `HandleTint.handleTint` is called from Java at
`app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java:575`.

- [ ] **Step 4: Commit**

```bash
git add -A terminal-view/src/main/java/com/termux/view
git commit -m "Changed(terminal-view): Convert leaf classes to Kotlin"
```

---

### Task 3: Text selection package

**Files:** `textselection/CursorController.java` (55), `textselection/TextSelectionCursorController.java` (427), `textselection/TextSelectionHandleView.java` (370).

**Oracle test:** `TextSelectionCursorControllerTest`.

**Interfaces:**
- Produces: `CursorController` interface unchanged (`extends ViewTreeObserver.OnTouchModeChangeListener` → Kotlin implements the same supertype); `TextSelectionHandleView.LEFT = 0` and `.RIGHT = 2` as `const val` in a companion — Java reaches them as `TextSelectionHandleView.LEFT`.
- Consumes: `HandleTint` from Task 2.

- [ ] **Step 1: Convert the three files**

Notes: `switch (item.getItemId())` and `switch (orientation)` → `when`. `TextSelectionHandleView`
keeps `extends View` and its `public static final int` pair as `const val`. Anonymous classes holding
state become `object :` expressions.

- [ ] **Step 2: Run the oracle test**

Run: `./gradlew :terminal-view:test --tests "*TextSelectionCursorControllerTest"`
Expected: PASS.

- [ ] **Step 3: Record the device check**

Append to `docs/ledger/manual-checks.md`:

```markdown
- [ ] Text selection: long-press to start selection, drag both handles, copy from the menu, and
      confirm handles track the finger after a rotation. Covers `TextSelectionHandleView` conversion.
```

- [ ] **Step 4: Compile gate and commit**

Run: `./gradlew :app:compileDebugKotlin`

```bash
git add -A terminal-view/src/main/java/com/termux/view/textselection docs/ledger/manual-checks.md
git commit -m "Changed(terminal-view): Convert the text selection package to Kotlin"
```

---

### Task 4: Gesture recognizer and renderer

**Files:** `GestureAndScaleRecognizer.java` (112), `TerminalRenderer.java` (647).

**Oracle tests:** `TerminalRendererRunTest`, `TerminalRendererCursorTest`, `TerminalLigatureTest`.

**Interfaces:**
- Consumes: `RenderBand`, `RunAdvancesSource`, `ShapedRunPolicy` (Task 2).
- Produces: `TerminalRenderer` public class, constructor and method signatures unchanged;
  `GestureAndScaleRecognizer.Listener` nested interface unchanged.

- [ ] **Step 1: Convert `GestureAndScaleRecognizer`**

`switch (event.getAction())` → `when`. The nested `Listener` interface stays nested and public.

- [ ] **Step 2: Run its tests, then convert `TerminalRenderer`**

Run: `./gradlew :terminal-view:test --tests "*TerminalRenderer*" --tests "*TerminalLigatureTest"`
Expected: PASS after both files.

- [ ] **Step 3: Preserve the hot path**

Rules for `TerminalRenderer` specifically: no lambda allocated inside a draw loop, no `Int?`, no
`Array<Int>`, and no new object per frame. `switch (underlineStyle)` → `when`. Convert to shorter
idioms only where no allocation appears. If a `when` would need a temporary, keep the `if/else`.

- [ ] **Step 4: Compile gate and commit**

Run: `./gradlew :termux-shared:compileDebugJavaWithJavac :app:compileDebugKotlin`

```bash
git add -A terminal-view/src/main/java/com/termux/view
git commit -m "Changed(terminal-view): Convert the renderer and gesture recognizer to Kotlin"
```

---

### Task 5: `TerminalView`

**Files:** `TerminalView.java` (1,824 lines).

**Oracle test:** `TerminalViewLinkHitTest`.

**Interfaces:**
- Produces: `TerminalView : View` with every public method preserved, including the
  `public synchronized` family (`requestAutoFill`, `cancelRequestAutoFill`,
  `setTerminalCursorBlinkerRate`, `setTerminalCursorBlinkerState`, `resetAutoFill`) → `@Synchronized
  fun`. `TERMINAL_CURSOR_BLINK_RATE_MIN = 100` and `TERMINAL_CURSOR_BLINK_RATE_MAX = 2000` become
  `const val` in a companion, because
  `termux-shared/src/main/java/com/termux/shared/termux/settings/properties/TermuxPropertyConstants.java:198`
  reads them as Java compile-time constants.

- [ ] **Step 1: Convert the class**

Notes: `private class TerminalCursorBlinkerRunnable implements Choreographer.FrameCallback` is a
non-static inner class → `inner class`. The touch, key and gesture listeners are anonymous classes
that capture fields → `object :` expressions, not lambdas. Six `synchronized` methods →
`@Synchronized`. `switch` over key codes and actions → `when`. `Menu`/`InputConnection` overrides keep
their exact Java signatures.

- [ ] **Step 2: Run the module suite and the Java binding**

Run: `./gradlew :terminal-view:test :termux-shared:compileDebugJavaWithJavac :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL, all 15 classes pass. `TermuxPropertyConstants` failing to compile means
the blink-rate constants are not `const val`.

- [ ] **Step 3: Verify the publication still carries sources**

Run: `./gradlew :terminal-view:sourceJar && unzip -l terminal-view/build/libs/*sources*.jar | grep -c "\.kt"`
Expected: a count greater than zero — the sources jar is not empty.

- [ ] **Step 4: Whole-module gate, device note, commit**

Run: `./gradlew :terminal-view:assembleRelease`

Append to `docs/ledger/manual-checks.md`:

```markdown
- [ ] TerminalView conversion: type text with the soft keyboard open, delete text mid-word with the
      IME's backspace, paste, and toggle the terminal cursor blink rate to both extremes in Settings.
```

```bash
git add -A terminal-view/src/main/java/com/termux/view docs/ledger/manual-checks.md
git commit -m "Changed(terminal-view): Convert TerminalView to Kotlin"
```

---

### Task 6: `:app` terminal package, activity and service

**Files:** `terminal/TermuxActivityRootView.java`, `terminal/TermuxTerminalSessionActivityClient.java`,
`terminal/TermuxTerminalSessionServiceClient.java`, `terminal/TermuxTerminalViewClient.java`,
`terminal/io/FullScreenWorkAround.java`, `terminal/io/KeyboardShortcut.java`,
`terminal/io/TermuxTerminalExtraKeys.java`, `TermuxActivity.java` (1,715), `TermuxService.java` (961).

**Oracle tests:** the `:app` suite (`./gradlew :app:testDebugUnitTest`), including
`com.termux.app.terminal` tests.

**Interfaces:**
- Produces, Java- and Kotlin-visible and unchanged: `TermuxActivity.newInstance(context): Intent`,
  `TermuxActivity.startTermuxActivity(context)`, `TermuxActivity.updateTermuxActivityStyling(...)`
  (companion + `@JvmStatic`; `newInstance` is called from Kotlin at
  `app/src/main/java/com/termux/app/data/notifications/ServiceNotification.kt:34`);
  `TermuxService.ACTION_TOGGLE_FLOAT` as `const val` (read from Kotlin at `ServiceNotification.kt:88`).
- Consumes: `TerminalViewClient`, `TerminalView` from Tasks 1 and 5.

- [ ] **Step 1: Convert the seven `terminal/` files**

`TermuxTerminalViewClient` and `TermuxTerminalSessionActivityClient` implement interfaces that are now
Kotlin: `override` on every member, parameter nullability taken from the interface, not re-guessed.
`TermuxTerminalExtraKeys` extends `TerminalExtraKeys` (Java, `:termux-shared`) — keep `override`
signatures byte-identical.

- [ ] **Step 2: Convert `TermuxActivity` and `TermuxService`**

They are constructed by the framework, so both stay classes with no-argument constructors; the
`Activity`/`Service` supertypes and every lifecycle override keep their exact signatures.

- [ ] **Step 3: Run the suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 2–3 minutes.

- [ ] **Step 4: Keep the statics Java-reachable**

`TermuxActivity`'s three statics, `TermuxService`'s `ACTION_*` constants, and any `public static`
helper → companion with `@JvmStatic` / `const val`. No `object` conversion for `Activity` or `Service`.

- [ ] **Step 5: Commit**

```bash
git add -A app/src/main/java/com/termux/app
git commit -m "Changed(app): Convert the terminal package, TermuxActivity and TermuxService to Kotlin"
```

---

### Task 7: Settings fragments

**Files (12):** `fragments/settings/TermuxPreferencesFragment.java`,
`TermuxAPIPreferencesFragment.java`, `TermuxFloatPreferencesFragment.java`,
`TermuxTaskerPreferencesFragment.java`, `TermuxWidgetPreferencesFragment.java`,
`termux/DebuggingPreferencesFragment.java`, `termux/DisplayPreferencesFragment.java`,
`termux/KeyboardPreferencesFragment.java`, `termux_api/DebuggingPreferencesFragment.java`,
`termux_float/DebuggingPreferencesFragment.java`, `termux_tasker/DebuggingPreferencesFragment.java`,
`termux_widget/DebuggingPreferencesFragment.java`.

**Interfaces:**
- Produces: each fragment's `public static synchronized getInstance(context)` → companion
  `@JvmStatic @Synchronized fun getInstance(context: Context)`. Note in the commit body that
  `@Synchronized` in a companion locks the companion instance, not the `Class` object — mutual
  exclusion still holds for every call through that function.
- Class names must not change: the framework instantiates them by name.

- [ ] **Step 1: Convert the five top-level fragments, then the seven nested ones**

Shared shape: `PreferenceFragmentCompat` subclasses, `onCreatePreferences` override, nested
`*PreferencesDataStore` classes that are `public static` → nested (non-`inner`).

- [ ] **Step 2: Run the suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add -A app/src/main/java/com/termux/app/fragments
git commit -m "Changed(app): Convert the settings fragments to Kotlin"
```

---

### Task 8: The `:app` tail

**Files (10):** `RunCommandService.java`, `TermuxApplication.java`, `TermuxInstaller.java`,
`TermuxOpenReceiver.java`, `event/SystemEventReceiver.java`, `activities/HelpActivity.java`,
`activities/SettingsActivity.java`, `api/file/FileReceiverActivity.java`, `models/UserAction.java`,
`filepicker/TermuxDocumentsProvider.java`.

**Interfaces:**
- Produces: `TermuxInstaller` must still expose the JNI entry point as a static method named `getZip`
  — the native side binds `Java_com_termux_app_TermuxInstaller_getZip`
  (`app/src/main/cpp/termux-bootstrap.c:6`). Use `companion object { @JvmStatic external fun
  getZip(): ByteArray }`; do not make it an `object` method or a top-level function.
- `SettingsActivity.RootPreferencesFragment` is a `public static` nested class → nested class.
- `TermuxOpenReceiver.ContentProvider` is a `public static` nested `ContentProvider` → nested class,
  name unchanged (the manifest may reference it).

- [ ] **Step 1: Convert the ten files**

Notes: `SystemEventReceiver` and the fragment data stores use `public static synchronized getInstance`
→ companion `@JvmStatic @Synchronized`. `UserAction` is an enum → enum class.

- [ ] **Step 2: Full suite and APK**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, all three modules.

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL, APK at `app/build/outputs/apk/debug/`.

- [ ] **Step 3: Verify the JNI symbol survived**

The native side binds `Java_com_termux_app_TermuxInstaller_getZip`, which only resolves if the Kotlin
declaration is a static method named `getZip` on `TermuxInstaller`.

Run: `unzip -p app/build/outputs/apk/debug/app-*-debug.apk classes.dex | strings | grep -c "TermuxInstaller;.getZip" | head -1`

Expected: at least 1. The definitive check is a smoke test on a clean install: launch the app and
confirm the bootstrap extraction screen completes without a linker error.

- [ ] **Step 4: Confirm no Java remains**

Run: `find terminal-view/src/main app/src/main -name '*.java' | wc -l`
Expected: `0`.

- [ ] **Step 5: Commit**

```bash
git add -A app/src/main/java/com/termux/app
git commit -m "Changed(app): Convert the remaining app classes to Kotlin"
```

---

### Task 9: Ledger and final verification

**Files:** `docs/ledger/outstanding-work.md`, `docs/ledger/manual-checks.md`.

- [ ] **Step 1: Record the outcome**

Add to `docs/ledger/outstanding-work.md` a closed entry naming the spec path, the batch commits, and
the two deferred modules with their reasons (`:terminal-emulator` hot loop, `:termux-shared` published
statics).

- [ ] **Step 2: Final gate**

Run: `./gradlew test assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add docs/ledger
git commit -m "Changed(docs): Record the Kotlin conversion as complete"
```
