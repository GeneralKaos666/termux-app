# In-app floating terminal — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a draggable floating bubble that expands into a movable/resizable terminal window running a dedicated shell session, entered from the sessions-bar "+" menu.

**Architecture:** New Compose/Kotlin package `com.termux.app.ui.floating` inside `:app`. A `FloatController` (held by `TermuxService`) owns the overlay windows and the dedicated `TermuxSession` (created via a new `TermuxService.createFloatSession(...)` that does *not* add to the tab list). The terminal is the fork's existing `TerminalView`, embedded via `AndroidView`. Pure geometry and callback routing are extracted so they are JVM-testable; overlay/IME behaviour is device-checked.

**Tech Stack:** Kotlin + Jetpack Compose, `WindowManager` overlays, `TerminalView`/`TerminalSession` from the existing modules, Robolectric for `:app` unit tests.

**Spec:** `docs/specs/2026-11-01-termux-float-in-app-design.md`

## Global Constraints

- Conventional Commits with capital types (`Added`, `Changed`, `Fixed`, …); every commit GPG-signed (`-S`) and verified (`%G?` == `G`).
- minSdk 21, targetSdk 28, compileSdk 36; Java/Kotlin `jvmTarget` 1.8 with core-library desugaring.
- Everything lives in `:app`; do **not** modify `:termux-shared`, `:terminal-view`, or `:terminal-emulator`.
- New UI is Kotlin + Jetpack Compose; reuse the fork's `TerminalView` for rendering (no Java view port).
- Device checks are recorded in `docs/ledger/manual-checks.md` and run only when a test device is connected.

## Review Focus

| # | Input / failure mode | Expected behaviour | Pinned by |
|---|---|---|---|
| 1 | Overlay permission missing when the float is requested | Prompt via `ACTION_MANAGE_OVERLAY_PERMISSION`, do not add a window | Task 5 test |
| 2 | Overlay permission revoked while the window is up | `FloatController` collapses cleanly, no crash | Task 9 (device) |
| 3 | The float shell exits | `onSessionFinished` collapses the window and clears the session ref | Task 2 test |
| 4 | Drag/resize past the screen edge | Position/size clamped to the screen | Task 1 test |
| 5 | Device runs API 21–25 | Window type falls back to `TYPE_PHONE` | Task 1 test |

---

## File Structure

- Create `app/src/main/java/com/termux/app/ui/float/FloatWindowGeometry.kt` — pure geometry + window-type selection.
- Create `app/src/main/java/com/termux/app/ui/float/FloatSessionClient.kt` — session callback routing to a listener.
- Create `app/src/main/java/com/termux/app/ui/float/FloatWindowPrefs.kt` — app-local x/y/width/height persistence.
- Create `app/src/main/java/com/termux/app/ui/float/FloatController.kt` — overlay + session orchestration.
- Create `app/src/main/java/com/termux/app/ui/float/FloatingBubble.kt` — bubble overlay composable.
- Create `app/src/main/java/com/termux/app/ui/float/FloatingWindow.kt` — window overlay composable (hosts `TerminalView`).
- Modify `app/src/main/java/com/termux/app/TermuxService.java` — extract `executeTermuxSession`, add `createFloatSession`.
- Modify `app/src/main/java/com/termux/app/ui/sessions/SessionsBar.kt` — "+" opens a popup menu.
- Modify `app/src/main/java/com/termux/app/ui/ShellHost.kt` — add `onNewFloatSession` to the callback interface.
- Modify `app/src/main/java/com/termux/app/TermuxActivity.java` — implement `onNewFloatSession`.
- Modify `app/src/main/res/values/strings.xml` — menu/notification/float strings.
- Modify `docs/ledger/manual-checks.md` — record deferred device checks.

---

### Task 1: Float window geometry (pure)

**Files:**
- Create: `app/src/main/java/com/termux/app/ui/float/FloatWindowGeometry.kt`
- Test: `app/src/test/java/com/termux/app/ui/float/FloatWindowGeometryTest.kt`

**Interfaces:**
- Produces: `FloatWindowGeometry.windowType(sdkInt: Int): Int`, `.clampX(x: Int, width: Int, maxX: Int): Int`, `.clampY(y: Int, height: Int, maxY: Int): Int`, `.clampWidth(width: Int, minWidth: Int, maxWidth: Int): Int`, `.clampHeight(height: Int, minHeight: Int, maxHeight: Int): Int`; constants `TYPE_APPLICATION_OVERLAY = 2038`, `TYPE_PHONE = 2002`.

- [ ] **Step 1: Write the failing test** — `FloatWindowGeometryTest` with `windowType` (asserts 2038 for `sdkInt` 26/28/33 and 2002 for 21/24), and the four clamps (asserts `clampX(500, 200, 400) == 200`, `clampX(-5, 200, 400) == 0`, `clampWidth(50, 200, 1000) == 200`, `clampHeight(1200, 100, 800) == 800`, and a within-bounds passthrough for each).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.ui.floating.FloatWindowGeometryTest" --console=plain`
Expected: FAIL — `FloatWindowGeometry` unresolved.

- [ ] **Step 3: Implement `FloatWindowGeometry` as a pure `object`** with the five functions above. `windowType` returns `TYPE_APPLICATION_OVERLAY` when `sdkInt >= 26` else `TYPE_PHONE`. `clampX`/`clampY` clamp to `[0, max - size]` (floor at 0); `clampWidth`/`clampHeight` clamp to `[min, max]`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.ui.floating.FloatWindowGeometryTest" --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/termux/app/ui/float/FloatWindowGeometry.kt app/src/test/java/com/termux/app/ui/float/FloatWindowGeometryTest.kt
git commit -S -m "Added(float): Add pure float window geometry helpers"
```

---

### Task 2: Float session client (callback routing)

**Files:**
- Create: `app/src/main/java/com/termux/app/ui/float/FloatSessionClient.kt`
- Test: `app/src/test/java/com/termux/app/ui/float/FloatSessionClientTest.kt`

**Interfaces:**
- Produces: `FloatSessionClient(listener: Listener) : TermuxTerminalSessionClientBase`; nested `interface Listener { fun onTextChanged(); fun onTitleChanged(); fun onColorsChanged(); fun onSessionFinished() }`. Overrides `onTextChanged`, `onTitleChanged`, `onColorsChanged`, `onSessionFinished` (each forwards to the matching listener method and ignores the `TerminalSession` argument).

- [ ] **Step 1: Write the failing test** — `FloatSessionClientTest` creates a recording `Listener` (four booleans/counters), a `FloatSessionClient(listener)`, then calls `client.onTextChanged(null)`, `client.onTitleChanged(null)`, `client.onColorsChanged(null)`, `client.onSessionFinished(null)` and asserts each listener method fired exactly once.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.ui.floating.FloatSessionClientTest" --console=plain`
Expected: FAIL — `FloatSessionClient` unresolved.

- [ ] **Step 3: Implement `FloatSessionClient`** extending `com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase`, forwarding the four overrides to the injected `Listener`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.ui.floating.FloatSessionClientTest" --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/termux/app/ui/float/FloatSessionClient.kt app/src/test/java/com/termux/app/ui/float/FloatSessionClientTest.kt
git commit -S -m "Added(float): Add FloatSessionClient callback routing"
```

---

### Task 3: Float window preferences

**Files:**
- Create: `app/src/main/java/com/termux/app/ui/float/FloatWindowPrefs.kt`
- Test: `app/src/test/java/com/termux/app/ui/float/FloatWindowPrefsTest.kt`

**Interfaces:**
- Produces: `data class FloatWindowState(val x: Int, val y: Int, val width: Int, val height: Int)` with `val DEFAULT = FloatWindowState(200, 200, 300, 420)`; `FloatWindowPrefs(prefs: SharedPreferences)` with `fun read(): FloatWindowState` and `fun write(state: FloatWindowState)`.
- Consumes: nothing from earlier tasks.

- [ ] **Step 1: Write the failing test** — `FloatWindowPrefsTest` (Robolectric) creates a `SharedPreferences` via `ApplicationProvider.getApplicationContext().getSharedPreferences("termux_float_window", MODE_PRIVATE)`, asserts a fresh `read()` returns `FloatWindowState.DEFAULT`, then `write(FloatWindowState(1,2,3,4))` and asserts `read()` returns `FloatWindowState(1,2,3,4)`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.ui.floating.FloatWindowPrefsTest" --console=plain`
Expected: FAIL — `FloatWindowPrefs` unresolved.

- [ ] **Step 3: Implement `FloatWindowPrefs`** over keys `window_x`, `window_y`, `window_width`, `window_height`, defaulting to `FloatWindowState.DEFAULT` per key.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.ui.floating.FloatWindowPrefsTest" --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/termux/app/ui/float/FloatWindowPrefs.kt app/src/test/java/com/termux/app/ui/float/FloatWindowPrefsTest.kt
git commit -S -m "Added(float): Persist float window position and size"
```

---

### Task 4: Session creation (`TermuxService.createFloatSession`)

**Files:**
- Modify: `app/src/main/java/com/termux/app/TermuxService.java` (extract `executeTermuxSession`, add `createFloatSession`)

**Interfaces:**
- Produces: `TermuxService.createFloatSession(TerminalSessionClient client): TermuxSession?` — builds a default-shell `ExecutionCommand`, runs it through the same creation path as `createTermuxSession(ExecutionCommand)` but with the supplied `client` and **without** adding the result to `mShellManager.mTermuxSessions`.
- Consumes: `com.termux.terminal.TerminalSessionClient` (the interface `FloatSessionClient` implements).

- [ ] **Step 1: Confirm the baseline suite is green** — `createFloatSession` spawns a real shell (PTY), which Robolectric cannot run, so its "uses the supplied client and does not join the tab list" behaviour is asserted on-device (added to Task 9). This task's automated gate is a behaviour-preserving refactor, proven by the existing `:app` suite staying green.

Run: `./gradlew :app:testDebugUnitTest --console=plain`
Expected: PASS (baseline before the refactor).

- [ ] **Step 2: Extract `executeTermuxSession`** — pull a private `TermuxSession executeTermuxSession(ExecutionCommand, TerminalSessionClient)` out of `createTermuxSession(ExecutionCommand)`, containing everything from the `null`/runner validation through the `TermuxSession.execute(...)` call and the `null`-result error log (but **not** the `mTermuxSessions.add`, plugin-pending removal, `termuxSessionListNotifyUpdated`, `updateNotification`, or `updateTermuxActivityStyling` steps). `createTermuxSession(ExecutionCommand)` now calls the helper with `getTermuxTerminalSessionClient()` and keeps those side effects unchanged.

- [ ] **Step 3: Add `createFloatSession`** — a public method that builds `new ExecutionCommand(TermuxShellManager.getNextShellId(), null, null, null, null, Runner.TERMINAL_SESSION.getName(), false)`, calls `executeTermuxSession(command, client)`, and returns the result with no list-add, notification, or styling side effects.

- [ ] **Step 4: Run the app suite to verify the refactor preserved behaviour**

Run: `./gradlew :app:testDebugUnitTest --console=plain`
Expected: PASS (existing `TermuxService`/session tests unchanged).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/termux/app/TermuxService.java
git commit -S -m "Changed(terminal): Add createFloatSession without tab-list side effects"
```

---

### Task 5: Float controller (overlay + session orchestration)

**Files:**
- Create: `app/src/main/java/com/termux/app/ui/float/FloatController.kt`
- Test: `app/src/test/java/com/termux/app/ui/float/FloatControllerTest.kt`

**Interfaces:**
- Produces: `FloatController(context, service)` with `fun showNewSession()`, `fun show()`, `fun hide()`, `fun stop()`, `fun isShown(): Boolean`. Holds `mFloatSession: TermuxSession?`, the `FloatingBubble`/`FloatingWindow` views, and a `FloatSessionClient.Listener` that collapses on `onSessionFinished`.
- Consumes: `FloatSessionClient` + `FloatSessionClient.Listener` (Task 2), `FloatWindowState`/`FloatWindowPrefs` (Task 3), `TermuxService.createFloatSession(...)` (Task 4), `FloatWindowGeometry` (Task 1).

- [ ] **Step 1: Write the failing test** — extract the *decision* logic so it is JVM-testable: `FloatController.canShowOverlay(context: Context): Boolean` (delegates to `Settings.canDrawOverlays`, stubbed via a `canDrawOverlays: () -> Boolean` constructor param) and `FloatController.onSessionFinished()` (clears `mFloatSession` and hides). Test: with `canDrawOverlays = false`, `showNewSession()` does not create a session; with `true` it proceeds; `onSessionFinished()` sets `isShown() == false`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.ui.floating.FloatControllerTest" --console=plain`
Expected: FAIL — `FloatController` unresolved.

- [ ] **Step 3: Implement `FloatController`** — constructor takes `(context, service, canDrawOverlays: () -> Boolean = { Settings.canDrawOverlays(context) })`. `showNewSession()` checks overlay permission first; if missing, launches `ACTION_MANAGE_OVERLAY_PERMISSION` and returns. Otherwise creates the session via `service.createFloatSession(sessionClient)` (a `FloatSessionClient` wired to this controller's listener), stores it in `mFloatSession`, binds it to a `TerminalView`, and shows the `FloatingWindow` (via `WindowManager`, using `FloatWindowGeometry.windowType(Build.VERSION.SDK_INT)`). `hide()` removes the window; `stop()` removes window + finishes the session; `onSessionFinished()` collapses and clears `mFloatSession`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.ui.floating.FloatControllerTest" --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/termux/app/ui/float/FloatController.kt app/src/test/java/com/termux/app/ui/float/FloatControllerTest.kt
git commit -S -m "Added(float): Add FloatController for overlay and session lifecycle"
```

---

### Task 6: Bubble + window chrome (Compose over `TerminalView`)

**Files:**
- Create: `app/src/main/java/com/termux/app/ui/float/FloatingBubble.kt`
- Create: `app/src/main/java/com/termux/app/ui/float/FloatingWindow.kt`

**Interfaces:**
- Produces: `FloatingBubble(onTap: () -> Unit, onDrag: (IntOffset) -> Unit)` and `FloatingWindow(terminalView: View, onDrag: (IntOffset) -> Unit, onResize: (IntSize) -> Unit, onCollapse: () -> Unit, onClose: () -> Unit, onToggleKeyboard: () -> Unit)`, both composable; `FloatingWindow` embeds `terminalView` via `AndroidView` and clears the Compose host's focusability (the `SideEffect` from `ShellHost.kt`), so the `TerminalView` keeps the input connection for IME.
- Consumes: `TerminalView` (existing), `FloatWindowGeometry` clamp functions for the min size, `FloatWindowState` for position. `IntOffset`/`IntSize` are `androidx.compose.ui.unit`.

- [ ] **Step 1: Implement both composables** — the bubble is a circular drag-target reporting `IntOffset` deltas; the window is a header (drag handle + collapse/close/keyboard icons) over the `AndroidView(terminalView)` with a resize corner reporting `IntSize` deltas. `FloatingWindow` must include the focus `SideEffect` so the embedded `TerminalView` keeps the IME input connection.

- [ ] **Step 2: Compile and run the app suite**

Run: `./gradlew :app:testDebugUnitTest --console=plain`
Expected: PASS (the pure geometry is already covered by Task 1; drag/resize/IME behaviour is device-checked in Task 9).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/termux/app/ui/float/FloatingBubble.kt app/src/main/java/com/termux/app/ui/float/FloatingWindow.kt
git commit -S -m "Added(float): Add floating bubble and window chrome"
```

---

### Task 7: New-session popup menu

**Files:**
- Modify: `app/src/main/java/com/termux/app/ui/sessions/SessionsBar.kt`
- Modify: `app/src/main/java/com/termux/app/ui/ShellHost.kt`
- Modify: `app/src/main/java/com/termux/app/TermuxActivity.java`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Produces: `ShellHostCallbacks.onNewFloatSession()`; `SessionsBar` gains an `onNewFloatSession: () -> Unit` param and the "+" `Icon` opens a `DropdownMenu` with two items — "New tab session" (`onNewSession`) and "New floating session" (`onNewFloatSession`).
- Consumes: `TermuxService.getFloatController()` (or equivalent accessor added in Task 5) to start the float.

- [ ] **Step 1: Add the strings** — `action_new_tab_session` = "New tab session", `action_new_float_session` = "New floating session".

- [ ] **Step 2: Extend the callback interface and plumbing** — add `fun onNewFloatSession()` to `ShellHostCallbacks`, pass it through `installTerminalShellHost` → `TerminalShellHost` → `SessionsBar`, and implement it in `TermuxActivity.mShellHostCallbacks` to call the float controller.

- [ ] **Step 3: Change the "+" button** — wrap the existing `Icon(SessionIcons.Plus)` in a `Box` and open a `DropdownMenu` (new `var newSessionMenuExpanded by remember { mutableStateOf(false) }`) offering the two actions; the empty-state "start session" text keeps `onNewSession`.

- [ ] **Step 4: Compile and run the app suite**

Run: `./gradlew :app:testDebugUnitTest --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/termux/app/ui/sessions/SessionsBar.kt app/src/main/java/com/termux/app/ui/ShellHost.kt app/src/main/java/com/termux/app/TermuxActivity.java app/src/main/res/values/strings.xml
git commit -S -m "Added(float): Add new-session menu with floating option"
```

---

### Task 8: Notification action + Settings toggle + manifest

**Files:**
- Modify: `app/src/main/java/com/termux/app/TermuxService.java` (add a "Float" action to the existing foreground notification)
- Modify: `app/src/main/java/com/termux/app/fragments/settings/TermuxFloatPreferencesFragment.java` (or the hidden `termux_float` entry) to drive the float on/off
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Produces: a "Float" notification action (reusing `TERMUX_FLOAT_APP_NOTIFICATION_CHANNEL_ID`/`_ID` 1339) that toggles `FloatController.show()`/`hide()`; the existing hidden `termux_float` Settings entry enables/disables the float.
- Consumes: `FloatController` (Task 5).

- [ ] **Step 1: Add the notification action** — in `TermuxService.updateNotification()`, add a "Float" action whose `PendingIntent` routes to the float controller's `show()`.

- [ ] **Step 2: Wire the Settings toggle** — the `termux_float` preference now enables/disables the float in-app (no `com.termux.window` context lookup).

- [ ] **Step 3: Compile and run the app suite**

Run: `./gradlew :app:testDebugUnitTest --console=plain`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/termux/app/TermuxService.java app/src/main/java/com/termux/app/fragments/settings/TermuxFloatPreferencesFragment.java app/src/main/res/values/strings.xml
git commit -S -m "Added(float): Add notification action and settings toggle"
```

---

### Task 9: Device checks (deferred)

**Files:**
- Modify: `docs/ledger/manual-checks.md`

- [ ] **Step 1: Record the deferred checks** — add a D-check (next in sequence) listing: overlay appears; drag/resize clamps at screen edges; keyboard shows over the overlay (IME binding); permission prompt when `canDrawOverlays` is false; overlay permission revoked while the window is up collapses cleanly; collapse/expand to bubble; float session survives closing the main activity; session exit collapses the window; the float session is **not** shown in the main tab bar (`createFloatSession` leaves `mTermuxSessions` unchanged). Mark it pending (run only with a test device connected, consistent with prior workstreams).

- [ ] **Step 2: Commit**

```bash
git add docs/ledger/manual-checks.md
git commit -S -m "Added(docs): Record pending float device checks"
```

---

### Task 10: Update the work ledger

**Files:**
- Modify: `docs/ledger/outstanding-work.md`

- [ ] **Step 1: Mark §5.2 as implemented** — flip the `Termux:Float` row and §5.2 heading to `[done]` with a one-line summary (in-app float via `:app`, bubble+window, dedicated session, entered from the "+" menu), and note the device checks are deferred to `manual-checks.md`.

- [ ] **Step 2: Commit**

```bash
git add docs/ledger/outstanding-work.md
git commit -S -m "Changed(docs): Mark in-app float as done"
```
