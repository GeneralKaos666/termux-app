# Shell UI motion — design

**Date:** 2026-11-14
**Scope:** make the Compose shell's motion fluid and springy where it is currently abrupt, and add the
transitions that are missing. All changes are in `:app` Compose UI under
`app/src/main/java/com/termux/app/ui/`.
**Status:** design approved in chat 2026-11-14; implemented 2026-11-14 (`75e605d3`, `5c98ef31`,
`3f923e3d`). Device check D-15 outstanding.
**Ledger item:** `outstanding-work.md` §1.4; device check D-15 in `manual-checks.md`.

## 1. Why

The shell already animates a little, but the motion is either a default tween or absent:

- `ShellHost.kt` collapses/expands the sessions bar and the extra-keys bar with
  `AnimatedVisibility` using `expandVertically() + fadeIn()` / `shrinkVertically() + fadeOut()`
  (lines 199–215 and 250–267). Those specs use the default tween, which feels mechanical.
- `SessionsBar.kt` swaps the active tab's `containerColor` instantly (line 265–269); there is no
  sliding indicator. Tab add/remove/reorder in the `LazyRow` pops rather than animating.
- The floating terminal swaps a bubble `ComposeView` for a window `ComposeView` (`FloatingBubble.kt`
  / `FloatingWindow.kt`) with no in/out transition; the window appears and disappears abruptly.

The goal is "cooler" motion, not faster: everything stays gated on
`Theme.kt#shellAnimationsEnabled()`, which reads the system animator scale and already turns shell
motion off when the user has disabled animations (an accessibility requirement that must not regress).

## 2. Goals and non-goals

Goals:

- **Springy chrome** — replace the default tween on the two `AnimatedVisibility` transitions with
  `spring(...)` specs.
- **Animated session tabs** — slide/fade tabs on add, remove and reorder, and animate the
  active-tab indicator as it moves.
- **Float transition** — animate the bubble-to-window and window-to-bubble morph.

Non-goals:

- **No terminal content motion.** The terminal canvas (`TerminalView`) is untouched; only the Compose
  chrome around it moves.
- **No change to gesture handling** (drag-to-reorder, bubble drag, tap-to-expand) or to the
  `WindowManager` layout maths in `FloatController`.
- **No new dependency.** Uses Compose foundation/animation already in the tree
  (`compose-bom 2026.09.00`, foundation 1.12.1).
- **No JVM unit tests.** The change is declarative Compose animation; `:app` has no Compose UI test
  infra (ledger §3.1), so verification is build + device visual check, not unit tests.

## 3. Design

All animation specs are chosen through `shellAnimationsEnabled()` so they collapse to
`EnterTransition.None` / `ExitTransition.None` / instant when system animations are off.

### 3.1 Springy chrome

Replace the default tween in `ShellHost.kt`:

```kotlin
val springSpec = spring(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMedium,
)

AnimatedVisibility(
    visible = sessionsBarExpanded,
    enter = if (animationsEnabled) expandVertically(springSpec) + fadeIn(springSpec) else EnterTransition.None,
    exit  = if (animationsEnabled) shrinkVertically(springSpec) + fadeOut(springSpec) else ExitTransition.None,
) { /* SessionsBar */ }
```

The extra-keys bar gets the same spec. Nothing else in the collapse/expand path changes: the
`rememberSaveable` expanded state, the floating restore handles and the `onCollapse`/`onToggle`
callbacks stay as they are.

### 3.2 Animated session tabs

Three effects in `SessionsBar.kt`:

- **Add/remove/reorder** — give the `LazyRow`'s item composable `Modifier.animateItem()`
  (foundation 1.12.1), which slides and fades an item into place when it appears, is removed, or
  moves because of a reorder. The dragged tab is excluded while the drag is in flight (it already
  uses its own `graphicsLayer.translationX`), so a reorder animates the *other* tabs around it; on
  drop the residual drag offset decays to 0 over the same spring, so the tab continues from where
  the finger released it instead of snapping back to its old slot first.
- **Active-tab surface** — replace the instant `containerColor` swap with `animateColorAsState(...)`
  on the active tab's `containerColor`/`contentColor`, so selection fades in. Colours use a 200 ms
  tween rather than the shell spring: a bouncy spec would overshoot the blend past the target colour.
- **Active indicator** — a thin rounded pill under the active tab that slides between tabs with
  `animateDpAsState(...)`/`animateIntOffsetAsState(...)` keyed to the active tab's measured bounds
  (already captured in `tabBounds`). The pill is a sibling to the `LazyRow`, so its slide does not
  fight the row's own layout. The bounds are read through a `snapshotFlow`, not during composition,
  because that map is written from layout and reading it there would schedule another layout on
  every write. The pill snaps while the row is scrolling (the bounds move every frame) and springs
  otherwise, so it never trails behind a fling. It is inset by the tab's corner radius, uses a tween
  for its width (a spring would briefly overshoot past the tab), and lives in a clipped layer over
  the row so it cannot draw over the chevron or the "+" when the active tab is scrolled away.

### 3.3 Float transition

`FloatingWindow.kt` and `FloatingBubble.kt` each get a scale+fade **in** on first composition: the
root composable's `graphicsLayer` is driven by a remembered `Animatable(0f)` animating to `1f` with the
shell spring in `LaunchedEffect(progress)` (both roots read one helper,
`theme/Motion.kt#floatEnterScaleAndAlpha`, which clamps the alpha because the spring overshoots past
1f). There is no exit animation: the `FloatController` swaps the two overlay `ComposeView`s
synchronously, so the outgoing window is removed before an exit could be observed. The two enter
animations share one spring spec so the swap still reads as a single morph.

## 4. Data flow

```
Settings.Global.ANIMATOR_DURATION_SCALE
        │  shellAnimationsEnabled()  →  animation specs (spring / None)
        │
sessionsState.sessions ──► LazyRow items ──► animateItem / animateColorAsState / indicator slide
float expand/collapse   ──► FloatingWindow / FloatingBubble ──► scale + fade
```

## 5. Testing

- Declarative Compose animation; no JVM unit tests (`:app` has no Compose UI test infra and none is
  added here).
- Verification: `./gradlew :app:assembleDebug` compiles; the motion is checked on device as D-15.
- **Device check D-15** (added to `manual-checks.md`, run in the device phase): sessions bar and
  extra-keys bar collapse/expand with a spring; tab add/close/reorder slides and fades; the active
  indicator glides between tabs; the float bubble↔window morphs; and, with "remove animations" on,
  every transition is instant (the `shellAnimationsEnabled()` gate).

## 6. Edge cases

- System animations off (`ANIMATOR_DURATION_SCALE == 0`) → all specs become instant, no motion.
- Empty sessions list → the `LazyRow` is not composed; the indicator and `animateItem` paths are inert.
- Reorder while a tab is dragged → the dragged tab keeps its own `graphicsLayer` translation; the
  other tabs animate around it, and the two never conflict (dragged tab `zIndex` already raised).
- Rapid add/close → `animateItem` handles interrupted animations; no state is lost (session order
  lives in `SessionsState`, not in the animation).

## 7. Verification

1. `./gradlew :app:assembleDebug` green. On this host that step cannot run: the NDK `ndk-build`
   fails with `ERROR: Unknown host CPU architecture: aarch64` (pre-existing, unrelated). The gate
   used instead is `./gradlew test :app:compileDebugKotlin :app:compileDebugJavaWithJavac`.
2. Full `./gradlew test` green (no new tests; the existing suite must stay green).
3. Device check D-15 in the device phase (kept last, per the work order).

## 8. Documentation and rollback

- `outstanding-work.md` gains a short entry under §1.4 (appearance, next to the cursor polish) for the
  feature, plus its D-15 check.
- `manual-checks.md` gains D-15.
- Each of the three effects is an independent, small change (spring specs; tab animation; float
  transition), so any one can be reverted without the others.
