package com.termux.app.ui.keys

import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.R
import com.termux.app.ui.ShellHostCallbacks
import com.termux.shared.termux.extrakeys.ExtraKeysView
import com.termux.shared.view.KeyboardUtils

/** Natural height of the writing (text-send) page of the bar, in dp. */
internal val WritingPageHeight = 56.dp

/**
 * Target height of the extra-keys bar's pager for the given [page]: each key grid page uses its own
 * configured grid height ([gridHeight] for [ExtraKeysState.PAGE_KEYS], [grid2Height] for
 * [ExtraKeysState.PAGE_KEYS_2]), the writing page its own natural height, and the whole pager
 * collapses to nothing while the keys are hidden (only the floating chevron restore handle stays
 * visible).
 */
internal fun extraKeysPageHeight(
    page: Int,
    keysVisible: Boolean,
    gridHeight: Dp,
    grid2Height: Dp = gridHeight,
): Dp = when {
    !keysVisible -> 0.dp
    page == ExtraKeysState.PAGE_KEYS -> gridHeight
    page == ExtraKeysState.PAGE_KEYS_2 -> grid2Height
    else -> WritingPageHeight
}

/**
 * The bottom extra-keys bar: a three-page pager (two key grids with independent layouts, plus the
 * text-send field) with the collapse chevron floating over its top-end corner. Pages are reached by
 * swiping the pager; while the keys are collapsed the bar has no height left, so the same chevron
 * floats at the bottom-end corner as a restore handle.
 *
 * Both key grids are the shared `ExtraKeysView` engine, embedded through [AndroidView] so a user's
 * custom `extra-keys` / `extra-keys-page2` layouts, modifier buttons, swipe-up popups and long-press
 * repeat keep working unchanged. All three pages stay composed while the bar is up, so the field
 * keeps its draft text and the view contracts below fire only on whole-bar hide or teardown.
 *
 * @param state Shell-owned bar state (visibility, grid heights, current page).
 * @param initialText Text to restore into the send field on first show (e.g. after rotation).
 * @param callbacks Bridge back into the activity.
 * @param onLeaveWritingPage The user paged or collapsed off the text-send field; hand focus back
 *   to the terminal so the keyboard follows it. Java handles the same when it hides the field.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExtraKeysBar(
    state: ExtraKeysState,
    initialText: String?,
    animationsEnabled: Boolean,
    callbacks: ShellHostCallbacks,
    onLeaveWritingPage: () -> Unit,
    backgroundAlpha: Float = 1f,
    modifier: Modifier = Modifier,
) {
    // Hoisted above the pager so restored text is applied once when the writing page's view is
    // created, and not re-applied on every recomposition of the bar.
    val pendingText = remember { Ref<String?>(initialText) }
    // The writing page's EditText, set once its AndroidView factory has run (which happens at bar
    // creation since every page stays composed).
    val editTextRef = remember { Ref<EditText?>(null) }
    // Whether the key-grid portion of the pager is expanded. Only the floating chevron stays
    // visible when collapsed; it re-expands the bar.
    var keysVisible by rememberSaveable { mutableStateOf(true) }

    val density = LocalDensity.current
    val gridHeight = with(density) { state.gridHeightPx.toDp() }
    val grid2Height = with(density) { state.grid2HeightPx.toDp() }

    // Not rememberSaveable: the activity-owned state resets to the keys page when the activity is
    // recreated (rotation, process death — the draft text is restored separately through
    // initialText), so a pager that saved its own position would disagree with the state on the
    // first frame and animate back. Both sides always start on the keys page.
    val pagerState = rememberPagerState(initialPage = state.page, pageCount = { ExtraKeysState.PAGE_WRITING + 1 })

    // Collapsing returns to the first keys page: the pager is hidden behind the strip, and leaving
    // the writing page behind it would keep the IME bound to an invisible field. The settle effect
    // below then hands focus back to the terminal.
    LaunchedEffect(keysVisible) {
        if (!keysVisible && state.page != ExtraKeysState.PAGE_KEYS) {
            state.page = ExtraKeysState.PAGE_KEYS
        }
    }

    // External writes (Java's setExtraKeysTextInputVisible, the back arrow) move the pager. While
    // it is collapsed there is nothing to animate, so jump instead.
    LaunchedEffect(state.page) {
        if (pagerState.settledPage != state.page) {
            if (keysVisible) pagerState.animateScrollToPage(state.page)
            else pagerState.scrollToPage(state.page)
        }
    }

    // The pager is the source of truth while the user drives it: adopt the settled page into the
    // state so text-input visibility and the IME input-mode logic in TermuxActivity follow.
    // Arriving on a *visible* writing page focuses the field and shows the IME; leaving it hands
    // focus back to the terminal (mirroring setExtraKeysTextInputVisible(false), which handles the
    // Java-initiated direction). Visibility is part of the key: the writing page can also be reached
    // while the keys are collapsed, so the page index alone does not change and the field would stay
    // unfocused behind the keyboard binding to the terminal.
    LaunchedEffect(pagerState) {
        var previousWriting = false
        snapshotFlow {
            pagerState.settledPage == ExtraKeysState.PAGE_WRITING && keysVisible
        }.collect { writing ->
            val page = pagerState.settledPage
            if (state.page != page) state.page = page
            if (previousWriting && !writing && page != ExtraKeysState.PAGE_WRITING) {
                onLeaveWritingPage()
            }
            if (writing) {
                editTextRef.value?.let { editText ->
                    // Posted, not called directly: the pages stay composed beyond the viewport, so
                    // the writing page's AndroidView can still be unattached when the pager settles
                    // here. A focus request made then is dropped and the IME stays bound to the
                    // terminal; posted, it runs once the view is in the window.
                    editText.post {
                        editText.requestFocus()
                        KeyboardUtils.showSoftKeyboard(editText.context, editText)
                    }
                }
            }
            previousWriting = writing
        }
    }

    // Animated target of the pager (collapse, page changes); its unanimated form drives the
    // semantics below so a hidden pager flips to invisible immediately, not after the animation.
    val pagerHeightTarget = extraKeysPageHeight(pagerState.settledPage, keysVisible, gridHeight, grid2Height)
    val pagerHeight by animateDpAsState(
        targetValue = pagerHeightTarget,
        animationSpec = if (animationsEnabled) tween(180) else snap(),
        label = "extraKeysPagerHeight",
    )
    val pagerAlpha by animateFloatAsState(
        targetValue = if (keysVisible) 1f else 0f,
        animationSpec = if (animationsEnabled) tween(180) else snap(),
        label = "extraKeysPagerAlpha",
    )

    val collapseLabel = stringResource(R.string.action_hide_keys)
    val expandLabel = stringResource(R.string.action_show_keys)
    val backLabel = stringResource(R.string.action_back_to_keys)

    Box(
        modifier = modifier
            .fillMaxWidth()
            // Only the bar's own background fades (the pager and the floating chevron live under
            // it); the icon and key tints stay opaque for readability.
            .background(MaterialTheme.colorScheme.surface.copy(alpha = backgroundAlpha)),
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = keysVisible,
            modifier = Modifier
                .fillMaxWidth()
                .height(pagerHeight)
                .clipToBounds()
                .graphicsLayer { alpha = pagerAlpha }
                .then(
                    if (pagerHeightTarget > 0.dp) Modifier else Modifier.semantics { invisibleToUser() },
                ),
        ) { page ->
            when (page) {
                ExtraKeysState.PAGE_KEYS -> KeysPage(
                    page = page,
                    gridHeight = gridHeight,
                    callbacks = callbacks,
                )

                ExtraKeysState.PAGE_KEYS_2 -> KeysPage(
                    page = page,
                    gridHeight = grid2Height,
                    callbacks = callbacks,
                )

                else -> TextInputPage(
                    pendingText = pendingText,
                    editTextRef = editTextRef,
                    callbacks = callbacks,
                    // The back arrow leaves the writing page for the first key grid; the settle
                    // effect above then hands focus (and so the IME) back to the terminal.
                    onBack = { state.page = ExtraKeysState.PAGE_KEYS },
                    backLabel = backLabel,
                )
            }
        }

        if (keysVisible) {
            KeysBarChevron(
                label = collapseLabel,
                icon = QuickActionIcons.ChevronDown,
                backgroundAlpha = backgroundAlpha,
                onToggle = { keysVisible = false },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp),
            )
        } else {
            // Collapsed, the bar itself is zero-height at the bottom edge of the shell, so the
            // handle aligns there and reaches up over the terminal (like SessionsRestoreHandle).
            KeysBarChevron(
                label = expandLabel,
                icon = QuickActionIcons.ChevronUp,
                backgroundAlpha = backgroundAlpha,
                onToggle = { keysVisible = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp),
            )
        }
    }
}

/**
 * A key-grid page of the pager ([ExtraKeysState.PAGE_KEYS] or [ExtraKeysState.PAGE_KEYS_2]): the
 * shared [ExtraKeysView] engine embedded through [AndroidView] so a user's custom layout for that
 * page, modifier buttons, swipe-up popups and long-press repeat keep working unchanged. All pages
 * stay composed (see [ExtraKeysBar]), so the create/release contract with the activity fires only
 * when the whole bar is hidden or the activity is torn down, not on page changes or key collapse.
 *
 * @param page The pager page this grid renders, so the activity can tell the two instances apart
 *   when it wires them to their own layout.
 */
@Composable
private fun KeysPage(page: Int, gridHeight: Dp, callbacks: ShellHostCallbacks) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(gridHeight),
    ) {
        AndroidView(
            factory = { context ->
                val extraKeysView = LayoutInflater.from(context)
                    .inflate(R.layout.view_terminal_toolbar_extra_keys, null) as ExtraKeysView
                callbacks.onExtraKeysViewCreated(page, extraKeysView)
                extraKeysView
            },
            onRelease = { callbacks.onExtraKeysViewReleased(page) },
            modifier = Modifier
                .fillMaxWidth()
                // Fixed intrinsic height so the grid keeps re-flowing its keys only when the
                // preference changes, not while the pager's height animates above it.
                .height(gridHeight),
        )
    }
}

/**
 * Page 3 of the pager (the old field above the keys): an [EditText] that writes a whole line to the
 * current session on send, with a back arrow that returns to the first key grid. Inflated from the
 * same layout as before so [com.termux.app.TermuxTerminalViewClient] can still find it by id. Focus
 * and the IME are driven by the bar when the pager settles here, not by the factory, which would
 * grab focus at bar creation while every page is composed.
 */
@Composable
private fun TextInputPage(
    pendingText: Ref<String?>,
    editTextRef: Ref<EditText?>,
    callbacks: ShellHostCallbacks,
    onBack: () -> Unit,
    backLabel: String,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        KeysBarIcon(
            label = backLabel,
            icon = QuickActionIcons.ArrowBack,
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .padding(start = 4.dp),
        )

        AndroidView(
            factory = { context ->
                val editText = LayoutInflater.from(context)
                    .inflate(R.layout.view_terminal_toolbar_text_input, null) as EditText
                editText.hint = context.getString(R.string.extra_keys_text_input_hint)
                pendingText.value?.takeIf { it.isNotEmpty() }?.let { editText.setText(it) }
                pendingText.value = null
                editText.setOnEditorActionListener { v, _, _ ->
                    callbacks.onExtraKeysTextSend(v.text.toString())
                    v.setText("")
                    true
                }
                editTextRef.value = editText
                editText
            },
            onRelease = { editTextRef.value = null },
            modifier = Modifier
                .weight(1f)
                .fillMaxSize(),
        )
    }
}

/**
 * The bar's collapse chevron: a small circled icon floating over the pager's top-end corner while
 * the keys are up, and at the bottom-end corner of the shell as a restore handle once they are
 * collapsed and the bar itself has no height left.
 */
@Composable
private fun KeysBarChevron(
    label: String,
    icon: ImageVector,
    backgroundAlpha: Float,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    KeysBarIcon(
        label = label,
        icon = icon,
        onClick = onToggle,
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = backgroundAlpha)),
    )
}

/**
 * A small clickable circled bar icon with a spoken label. A plain clickable [Icon] rather than an
 * IconButton, both to stay small enough for the chrome and because Material3's IconButton enforces
 * a 48dp minimum interactive size.
 */
@Composable
private fun KeysBarIcon(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .clip(CircleShape)
            .clickable {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
            .padding(4.dp)
            .semantics { contentDescription = label },
    )
}

/** Simple observable holder for a value crossing a composition boundary. */
private class Ref<T>(var value: T?)
