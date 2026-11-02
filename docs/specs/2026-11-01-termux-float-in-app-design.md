# In-app floating terminal (Termux:Float folded into `:app`) — design

Status: draft, pending user review.

## 1. Goal

Bring the Termux:Float experience into the app itself: a draggable floating bubble that expands
into a movable, resizable terminal window running its **own** shell session, shown over other apps.
No second APK, no `sharedUserId` companion. The float is a first-class part of this fork's UX,
driven by the existing `TermuxService` and the Compose shell.

## 2. Decisions (locked)

- **Shape:** bubble + window, upstream-style. A draggable bubble expands into a floating terminal
  window that can collapse back to the bubble.
- **Session:** a dedicated float session (its own shell), independent of the main tabs. It is *not*
  shown in the sessions bar.
- **Entry point:** the sessions-bar "new session" (`+`) button opens a popup menu with two options —
  "New tab session" (existing behaviour) and "New floating session" (spawns the float session).
- **Chrome tech:** Compose chrome over the fork's existing `TerminalView` (embedded via
  `AndroidView`), not a port of the upstream Java views.
- **Location:** everything lives in `:app` (no new Gradle module), consistent with the
  "concentrate changes in `:app`" rule.
- **Device checks:** deferred to `docs/ledger/manual-checks.md` (pending), run only when a test
  device is connected — the same policy as earlier workstreams.

## 3. Components (new, in `com.termux.app.ui.floating`)

| Component | Responsibility |
|---|---|
| `FloatController` | Orchestrator held by `TermuxService`. Owns the overlay lifecycle, the bubble/window views, and the dedicated float session. Exposes `show()`, `hide()`, `stop()`, `isShown()`. |
| `FloatingBubble` | A small circular `ComposeView` overlay; draggable by touch, tap to expand. |
| `FloatingWindow` | A `ComposeView` overlay hosting the terminal; header (drag handle, collapse-to-bubble, keyboard toggle, close) + resize corner. |
| `FloatTerminalView` | A dedicated instance of the fork's `TerminalView`, bound to the float session. |
| `FloatSessionClient` | Extends `TermuxTerminalSessionClientBase`; routes callbacks to the float view. |
| `FloatViewClient` | View client for text selection / links, mirroring the fork's `TermuxTerminalViewClient`. |
| `FloatWindowGeometry` | Pure functions for drag/resize clamping and window-type selection (unit-testable). |

## 4. Session wiring

- New `TermuxService.createFloatSession()` mirrors `createTermuxSession(String, String[], String,
  String, boolean, String)` but constructs the `TermuxSession` with `FloatSessionClient` instead of
  the activity client, running the default shell.
- The float session is **not** added to `mShellManager.mTermuxSessions`. It is held by
  `FloatController` as a single `mFloatSession`. When it exits, `FloatSessionClient.onSessionFinished`
  collapses the window and clears the reference. The main tabs are untouched.
- The float session dies with `TermuxService` (acceptable — the float is a feature of the running
  app, and the service already outlives the activity).

## 5. Entry point — new-session menu

- `SessionsBar` currently calls `ShellHostCallbacks.onNewSession()` from the `+` button. Change this
  to open a Material3 `DropdownMenu` anchored to the button with two items:
  - **"New tab session"** → existing `onNewSession()` (`TermuxTerminalSessionActivityClient.addNewSession(false, null)`).
  - **"New floating session"** → new `ShellHostCallbacks.onNewFloatSession()`, which asks
    `TermuxService` to create the float session and show the float.
- New string resources for the two menu labels. The float option is always shown; if overlay
  permission is missing, tapping it launches the permission screen with a short explainer (see §7).

## 6. Overlay & chrome

- Window type: `TYPE_APPLICATION_OVERLAY` on API 26+, `TYPE_PHONE` on 21–25 (minSdk 21, targetSdk
  28). `SYSTEM_ALERT_WINDOW` is already declared in the manifest.
- Both bubble and window are added/removed via `WindowManager` by `FloatController`.
- **Bubble:** drag = update the bubble's `LayoutParams.x/y`. Tap = collapse window → bubble, or
  expand bubble → window.
- **Window:** drag = header touch moves `LayoutParams.x/y`; resize = corner touch updates
  `LayoutParams.width/height`, clamped to a min size. Position/size are persisted in **app-local**
  preferences (a new app-local file, not `TermuxFloatAppSharedPreferences`, which reads the
  `com.termux.window` package that no longer exists).
- **Show/hide:** a "Float" action on `TermuxService`'s existing foreground notification, plus the
  `termux_float` Settings entry — both toggle the float via the same
  `TermuxService.ACTION_TOGGLE_FLOAT` service action. No second service is introduced.

## 7. Permissions & error handling

- Before showing, check `Settings.canDrawOverlays()`. If false, launch
  `ACTION_MANAGE_OVERLAY_PERMISSION` (with a small explainer) and do not add the window — unlike
  upstream, which swallowed the failure (flagged in the work ledger).
- If the overlay is revoked while the window is up, `FloatController` collapses cleanly on the next
  draw failure rather than crashing.

## 8. IME & insets

The soft keyboard does not bind to overlay windows by default. The `FloatingWindow` composable must:

- clear `isFocusable` / `isFocusableInTouchMode` on the Compose host and set
  `descendantFocusability = FOCUS_AFTER_DESCENDANTS`, exactly as `ShellHost` does;
- ensure the window `LayoutParams` do **not** set `FLAG_NOT_FOCUSABLE` while expanded, so the
  `TerminalView` receives the input connection;
- show/hide the keyboard via the header's keyboard toggle (which drives the view's IME request).

This is the riskiest part of the change and is covered by a device check.

## 9. Non-goals

- No `com.termux.window` APK, no `sharedUserId`, no port of the upstream Java `TermuxFloatView`.
- No multiple simultaneous float windows in the first pass (one float session, one window).
- No per-session floats (the float is a single dedicated session, not "float any tab").
- No change to `:termux-shared` / `:terminal-view` / `:terminal-emulator` (the float reuses the
  published session/view APIs as-is).

## 10. Testing

- **Unit (JVM):** `FloatSessionClient` callback routing (text → invalidate, colours → repaint,
  exit → collapse); `FloatWindowGeometry` drag/resize clamping and window-type selection;
  `createFloatSession` session-list behaviour via Robolectric (float session not in
  `mTermuxSessions`, uses `FloatSessionClient`).
- **Device checks (deferred):** recorded in `docs/ledger/manual-checks.md` and run only when a test
  device is connected — overlay appears, drag/resize, keyboard shows over the overlay, permission
  prompt, collapse/expand, float session survives closing the main activity.
