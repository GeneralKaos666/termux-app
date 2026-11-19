package com.termux.app.ui

import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.R
import com.termux.app.domain.background.BackgroundFx
import com.termux.app.domain.palette.PaletteEntry
import com.termux.app.domain.palette.PaletteLabels
import com.termux.app.domain.palette.paletteEntries
import com.termux.app.ui.background.BlurLayer
import com.termux.app.ui.background.TerminalBackgroundState
import com.termux.app.ui.firstrun.FirstRunOverlay
import com.termux.app.ui.firstrun.FirstRunState
import com.termux.app.ui.keys.ExtraKeysBar
import com.termux.app.ui.keys.ExtraKeysHandle
import com.termux.app.ui.keys.ExtraKeysState
import com.termux.app.ui.palette.CommandPalette
import com.termux.app.ui.snippets.SnippetChipsState
import com.termux.app.ui.snippets.SnippetChipsRow
import com.termux.app.ui.splash.IntroSplash
import com.termux.app.ui.terminal.LinkActionSheet
import com.termux.app.ui.terminal.LinkActionState
import com.termux.app.ui.palette.PaletteState
import com.termux.app.ui.sessions.SessionsBar
import com.termux.app.ui.sessions.SessionsRestoreHandle
import com.termux.app.ui.sessions.SessionsState
import com.termux.app.ui.theme.ShellEaseOut
import com.termux.app.ui.theme.TermuxShellTheme
import com.termux.app.ui.theme.shellAnimationsEnabled
import com.termux.shared.termux.extrakeys.ExtraKeysView

/**
 * The chrome bars show and hide on a fade only; their height is not animated. A bar shares the
 * `Column` with the terminal, so an animated height resizes the terminal on every frame, and every
 * size change runs `TerminalView.updateSize` → `TerminalSession.updateSize` → `JNI.setPtyWindowSize`
 * plus a full emulator repaint, so a running shell reflows several times per toggle. A fade keeps
 * the height change to one frame (at the start when a bar appears, at the end when it leaves) and
 * still avoids a hard pop. Enter is slower than exit: showing a bar is the deliberate act, hiding is
 * a response.
 */
private val ChromeFadeIn = tween<Float>(durationMillis = 150, easing = ShellEaseOut)
private val ChromeFadeOut = tween<Float>(durationMillis = 120, easing = ShellEaseOut)

/**
 * Callbacks from the Compose shell back to the hosting Java activity, so the Compose tree never has
 * to hold Android session-service references.
 */
interface ShellHostCallbacks {
    /** Switch the terminal to the session with the given [id]. */
    fun onSessionSelected(id: Int)

    /** Open the rename prompt for the session with the given [id]. */
    fun onSessionRename(id: Int)

    /** Close the session with the given [id], confirming first if it is still running. */
    fun onSessionClose(id: Int)

    /** Move the session with the given [id] to the target position [toIndex]. */
    fun onSessionMoved(id: Int, toIndex: Int)

    /** Start a new session. */
    fun onNewSession()

    /** Start a new floating session. */
    fun onNewFloatSession()

    /** Start a session and insert [command] (the empty bar's starter chips). */
    fun onStarterCommand(command: String)

    /** Open the command palette. */
    fun onOpenPalette()

    /** Run the palette row the user chose; [payload] is the session id or snippet command. */
    fun onPaletteAction(action: com.termux.app.domain.palette.PaletteAction, payload: String?)

    /** Insert [text] into the current session without pressing Enter (snippets, palette). */
    fun onInsertText(text: String)

    /**
     * Wire the given [view] for pager [page] to its client and load that page's key layout. Called
     * once per creation, and once per key-grid page (both grids stay composed together).
     */
    fun onExtraKeysViewCreated(page: Int, view: ExtraKeysView)

    /** Drop the activity's reference to the key-grid view for pager [page] it is no longer hosting. */
    fun onExtraKeysViewReleased(page: Int)

    /** Write [text] (or a newline when empty) to the current session. */
    fun onExtraKeysTextSend(text: String)

    /** The user pressed Undo on the last snackbar with an undo action. */
    fun onSnackbarUndo()

    /** The user pressed Install on the first-run overlay; starts the bootstrap install. */
    fun onFirstRunInstall()

    /** The user chose [action] for [url] in the link action sheet. */
    fun onLinkAction(action: com.termux.app.domain.terminal.LinkAction, url: String)
}

/**
 * Installs the Compose shell host into [composeView], rendering the sessions bar above the existing
 * [terminalView] (a [com.termux.view.TerminalView]) which is embedded through [AndroidView], and the
 * extra-keys bar below it.
 *
 * This is called from the Java [TermuxActivity]; composables and
 * [ComposeView.setContent] cannot be invoked from Java directly, hence this non-composable entry
 * point.
 *
 * The terminal is the single leaf Android view inside the Compose tree. Its focus and IME
 * behaviour must be validated on device (see the shell workstream plan, tasks A2/A3).
 */
fun installTerminalShellHost(
    composeView: ComposeView,
    terminalView: View,
    sessionsState: SessionsState,
    extraKeysState: ExtraKeysState,
    snackbarState: SnackbarState,
    terminalBackgroundState: TerminalBackgroundState,
    shellChromeState: ShellChromeState,
    firstRunState: FirstRunState,
    paletteState: PaletteState,
    snippetChipsState: SnippetChipsState,
    linkActionState: LinkActionState,
    savedTextInput: String?,
    callbacks: ShellHostCallbacks,
) {
    composeView.setContent {
        TerminalShellHost(
            terminalView,
            sessionsState,
            extraKeysState,
            snackbarState,
            terminalBackgroundState,
            shellChromeState,
            firstRunState,
            paletteState,
            snippetChipsState,
            linkActionState,
            savedTextInput,
            callbacks,
        )
    }
}

@Composable
private fun TerminalShellHost(
    terminalView: View,
    sessionsState: SessionsState,
    extraKeysState: ExtraKeysState,
    snackbarState: SnackbarState,
    terminalBackgroundState: TerminalBackgroundState,
    shellChromeState: ShellChromeState,
    firstRunState: FirstRunState,
    paletteState: PaletteState,
    snippetChipsState: SnippetChipsState,
    linkActionState: LinkActionState,
    savedTextInput: String?,
    callbacks: ShellHostCallbacks,
) {
    val hostView = LocalView.current
    val snackbarHostState = remember { SnackbarHostState() }
    val animationsEnabled = shellAnimationsEnabled()

    // While the first-run overlay is up there are no sessions yet; it is the only content, so the
    // empty-state "start a session" button in the bar is not composed underneath it (it would be
    // an unreachable, focusable duplicate).
    val firstRunOverlayVisible = firstRunState.visible && sessionsState.sessions.isEmpty()

    // Rows are rebuilt from localized labels; snippets join this list once their store exists.
    val paletteLabels = PaletteLabels(
        newSession = stringResource(R.string.palette_action_new_session),
        newFloatSession = stringResource(R.string.palette_action_new_float_session),
        openAppearance = stringResource(R.string.palette_action_appearance),
        openSettings = stringResource(R.string.palette_action_settings),
        toggleKeys = stringResource(R.string.palette_action_toggle_keys),
        paste = stringResource(R.string.palette_action_paste),
    )
    val paletteRows = remember(sessionsState.sessions, snippetChipsState.items, paletteLabels) {
        paletteEntries(sessionsState.sessions, snippetChipsState.items, paletteLabels)
    }
    LaunchedEffect(paletteRows) { paletteState.updateEntries(paletteRows) }

    // Whether the sessions bar is expanded. Persisted across rotation; when collapsed, only the
    // floating restore handle stays on screen.
    var sessionsBarExpanded by rememberSaveable { mutableStateOf(true) }

    // Whether the extra-keys grids are expanded. Collapsed, the bar keeps no height and the
    // terminal grows down to the bottom edge, where the floating handle restores the keys.
    var extraKeysExpanded by rememberSaveable { mutableStateOf(true) }

    // Reused across draws: the blur is recorded into this layer once per changed picture/size/
    // crop/radius/opacity and drawn as one layer. Below API 31 the load-time downscale is the blur.
    val blurLayer = remember { BlurLayer() }

    // Dark-first chrome: the terminal and the extra-keys grid are always dark, so the shell follows
    // them rather than the system light/dark setting. Material You (dark variant) on API 31+,
    // Termux palette fallback elsewhere; see the theme module.
    TermuxShellTheme {
        // The terminal background picture (plus dim) covers the whole shell, not just the terminal
        // area: the sessions tab bar above and the extra-keys bar below draw over it with a
        // configurable alpha, so a transparent bar reveals the same picture the terminal shows.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val picture = terminalBackgroundState.bitmap
                    val dstW = size.width.toInt()
                    val dstH = size.height.toInt()
                    if (picture != null && dstW > 0 && dstH > 0) {
                        val srcW = picture.width
                        val srcH = picture.height
                        // Centre-crop the picture to fill the shell area. The crop is never empty,
                        // so a 1x1 live-wallpaper fallback still fills the screen.
                        val crop = BackgroundFx.centerCrop(srcW, srcH, dstW, dstH)
                        val srcOffset = IntOffset(crop.left, crop.top)
                        val cropW = crop.width
                        val cropH = crop.height
                        val opacity = terminalBackgroundState.pictureOpacity
                        val blurRadius = terminalBackgroundState.blurRadius
                        if (blurRadius > 0f) {
                            // Blur is a cached RenderEffect layer (API 31+); below that the filtered
                            // upscale of the already downscaled bitmap is the fallback blur.
                            drawIntoCanvas { canvas ->
                                blurLayer.draw(
                                    canvas.nativeCanvas,
                                    picture,
                                    crop,
                                    dstW,
                                    dstH,
                                    blurRadius,
                                    opacity,
                                )
                            }
                        } else {
                            drawImage(
                                image = picture.asImageBitmap(),
                                srcOffset = srcOffset,
                                srcSize = IntSize(cropW, cropH),
                                dstOffset = IntOffset.Zero,
                                dstSize = IntSize(dstW, dstH),
                                alpha = opacity,
                            )
                        }

                        val dim = terminalBackgroundState.dimPercent
                        if (dim > 0) drawRect(Color.Black.copy(alpha = dim / 100f))
                    }
                },
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (!firstRunOverlayVisible) {
                    AnimatedVisibility(
                        visible = sessionsBarExpanded,
                        enter = if (animationsEnabled) fadeIn(ChromeFadeIn) else EnterTransition.None,
                        exit = if (animationsEnabled) fadeOut(ChromeFadeOut) else ExitTransition.None,
                    ) {
                        SessionsBar(
                            sessions = sessionsState.sessions,
                            onSelect = callbacks::onSessionSelected,
                            onRename = callbacks::onSessionRename,
                            onClose = callbacks::onSessionClose,
                            onMove = callbacks::onSessionMoved,
                            onNewSession = callbacks::onNewSession,
                            onNewFloatSession = callbacks::onNewFloatSession,
                            onCollapse = { sessionsBarExpanded = false },
                            onStarterCommand = callbacks::onStarterCommand,
                            onOpenPalette = callbacks::onOpenPalette,
                            backgroundAlpha = chromeBackgroundAlpha(shellChromeState.barTransparencyPercent),
                            unseen = sessionsState.unseen,
                            starterCommands = sessionsState.starterCommands,
                            bellFlash = shellChromeState.bellFlash,
                        )
                    }
                }

                // The terminal view is painted transparent (its default background is the window
                // decor colour behind the Compose host), so the background picture drawn behind
                // the whole shell shows through it.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        // Terminal margin, not shell margin: the bars stay flush with the screen
                        // edges, and the decor background (the terminal background colour) is only
                        // exposed around the terminal itself, where it belongs.
                        .padding(
                            horizontal = shellChromeState.terminalMarginHorizontalDp.dp,
                            vertical = shellChromeState.terminalMarginVerticalDp.dp,
                        ),
                ) {
                    AndroidView(factory = { terminalView })

                    // The extra-keys collapse handle floats over the terminal at the bottom-end
                    // corner instead of inside the bar, so it never covers the last key of the
                    // grid; collapsed, it sits at the bottom edge like SessionsRestoreHandle.
                    if (extraKeysState.visible) {
                        ExtraKeysHandle(
                            keysVisible = extraKeysExpanded,
                            onToggle = { extraKeysExpanded = !extraKeysExpanded },
                            backgroundAlpha = chromeBackgroundAlpha(shellChromeState.barTransparencyPercent),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp),
                        )
                    }
                }

                AnimatedVisibility(
                    visible = extraKeysState.visible,
                    enter = if (animationsEnabled) fadeIn(ChromeFadeIn) else EnterTransition.None,
                    exit = if (animationsEnabled) fadeOut(ChromeFadeOut) else ExitTransition.None,
                ) {
                    ExtraKeysBar(
                        snippets = snippetChipsState.items,
                        snippetsRowVisible = snippetChipsState.rowVisible,
                        onInsertSnippet = callbacks::onInsertText,
                        state = extraKeysState,
                        keysVisible = extraKeysExpanded,
                        initialText = savedTextInput,
                        animationsEnabled = animationsEnabled,
                        callbacks = callbacks,
                        // The pager adopted a page change that is not the writing field (a swipe
                        // or collapse, mirroring setExtraKeysTextInputVisible(false) in
                        // TermuxActivity): the keyboard should now follow the terminal again.
                        onLeaveWritingPage = { terminalView.requestFocus() },
                        backgroundAlpha = chromeBackgroundAlpha(shellChromeState.barTransparencyPercent),
                    )
                }
            }

            // While the sessions bar is collapsed, a small floating handle at the top corner
            // restores it. Hidden with the bar under the first-run overlay. It fades in only after
            // the bar has finished leaving, so a control does not pop into the corner while the bar
            // is still on screen; expanding is immediate, since the user just asked for the bar.
            AnimatedVisibility(
                visible = !firstRunOverlayVisible && !sessionsBarExpanded,
                enter = if (animationsEnabled) {
                    fadeIn(tween(120, delayMillis = 120, easing = ShellEaseOut))
                } else {
                    EnterTransition.None
                },
                exit = if (animationsEnabled) fadeOut(tween(80, easing = ShellEaseOut)) else ExitTransition.None,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                SessionsRestoreHandle(
                    onExpand = { sessionsBarExpanded = true },
                    backgroundAlpha = chromeBackgroundAlpha(shellChromeState.barTransparencyPercent),
                    bellFlash = shellChromeState.bellFlash,
                    modifier = Modifier.padding(4.dp),
                )
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            // First-run onboarding covers the whole shell until the first session exists.
            if (firstRunOverlayVisible) {
                FirstRunOverlay(
                    installing = firstRunState.installing,
                    stage = firstRunState.stage,
                    onInstall = callbacks::onFirstRunInstall,
                )
            }

            IntroSplash(
                visible = shellChromeState.splashVisible,
                onDone = shellChromeState::dismissSplash,
            )

            CommandPalette(
                state = paletteState,
                onChoose = { entry: PaletteEntry ->
                    paletteState.close()
                    callbacks.onPaletteAction(entry.action, entry.payload)
                },
                onDismiss = paletteState::close,
            )

            LinkActionSheet(
                state = linkActionState,
                onAction = { action, url ->
                    linkActionState.dismiss()
                    callbacks.onLinkAction(action, url)
                },
                onDismiss = linkActionState::dismiss,
            )
        }
    }

    // The visual bell lights the chrome for a moment; a bell arriving during the flash bumps the
    // sequence, restarting the timer instead of being lost.
    LaunchedEffect(shellChromeState.bellSequence) {
        if (shellChromeState.bellSequence <= 0) return@LaunchedEffect
        delay(ShellChromeState.BELL_FLASH_MILLIS)
        shellChromeState.clearBellFlash()
    }

    LaunchedEffect(snackbarState.sequence) {
        if (snackbarState.sequence <= 0) return@LaunchedEffect

        val message = snackbarState.message ?: return@LaunchedEffect
        val undoLabel = snackbarState.undoLabel
        val result = if (undoLabel != null) {
            snackbarHostState.showSnackbar(message, actionLabel = undoLabel, duration = SnackbarDuration.Short)
        } else {
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
        if (result == SnackbarResult.ActionPerformed) callbacks.onSnackbarUndo()
    }

    SideEffect {
        // The Compose host (AndroidComposeView) is itself focusable(-in-touch-mode), so it keeps
        // focus for the Compose tree. An embedded Android view that calls requestFocus() (the
        // terminal does so on tap) would then never become the focused Android view, and the IME
        // would bind its input connection to the host instead of the terminal, so the soft
        // keyboard would never show. Hand focus to descendants instead.
        hostView.isFocusable = false
        hostView.isFocusableInTouchMode = false
        (hostView as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }
}
