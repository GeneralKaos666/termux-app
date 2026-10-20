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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.R
import com.termux.app.ui.ShellHostCallbacks
import com.termux.app.ui.keys.QuickAction.KEYBOARD
import com.termux.app.ui.keys.QuickAction.TEXT_INPUT
import com.termux.shared.termux.extrakeys.ExtraKeysView
import com.termux.shared.view.KeyboardUtils

/**
 * Quick actions for the terminal chrome. [TEXT_INPUT] moves the pager to the writing page (through
 * the activity's toggle); the others fall through to the activity like before.
 */
enum class QuickAction { KEYBOARD, TEXT_INPUT, SETTINGS }

/** Natural height of the quick-actions page of the bar, in dp. */
internal val QuickActionsPageHeight = 76.dp

/** Natural height of the writing (text-send) page of the bar, in dp. */
internal val WritingPageHeight = 56.dp

/**
 * Target height of the extra-keys bar's pager for the given [page]: the key grid page uses the
 * configured grid height, the quick-actions and writing pages their own natural heights, and the
 * whole pager collapses to nothing while the keys are hidden (only the strip with the page dots
 * and the collapse chevron stays visible).
 */
internal fun extraKeysPageHeight(page: Int, keysVisible: Boolean, gridHeight: Dp): Dp = when {
    !keysVisible -> 0.dp
    page == ExtraKeysState.PAGE_KEYS -> gridHeight
    page == ExtraKeysState.PAGE_ACTIONS -> QuickActionsPageHeight
    else -> WritingPageHeight
}

/**
 * The bottom extra-keys bar: a slim strip of page dots and a collapse chevron above a three-page
 * pager (key grid, quick actions, text-send field).
 *
 * The key grid is still the shared `ExtraKeysView` engine, embedded through [AndroidView] so a
 * user's custom `extra-keys` layout, modifier buttons, swipe-up popups and long-press repeat keep
 * working unchanged. All three pages stay composed while the bar is up, so the field keeps its
 * draft text and the view contracts below fire only on whole-bar hide or teardown.
 *
 * @param state Shell-owned bar state (visibility, grid height, current page).
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
    // Whether the key-grid portion of the pager is expanded. Only the strip stays visible when
    // collapsed; its dots re-expand the bar onto the chosen page.
    var keysVisible by rememberSaveable { mutableStateOf(true) }

    val density = LocalDensity.current
    val gridHeight = with(density) { state.gridHeightPx.toDp() }

    // Not rememberSaveable: the activity-owned state resets to the keys page when the activity is
    // recreated (rotation, process death — the draft text is restored separately through
    // initialText), so a pager that saved its own position would disagree with the state on the
    // first frame and animate back. Both sides always start on the keys page.
    val pagerState = rememberPagerState(initialPage = state.page, pageCount = { ExtraKeysState.PAGE_WRITING + 1 })

    // Collapsing returns to the keys page: the pager is hidden behind the strip, and leaving the
    // writing page behind it would keep the IME bound to an invisible field. The settle effect
    // below then hands focus back to the terminal.
    LaunchedEffect(keysVisible) {
        if (!keysVisible && state.page != ExtraKeysState.PAGE_KEYS) {
            state.page = ExtraKeysState.PAGE_KEYS
        }
    }

    // External writes (Java's setExtraKeysTextInputVisible, the page dots) move the pager. While
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
    // Java-initiated direction). Visibility is part of the key: a dot tapped while the keys are
    // collapsed re-expands the bar onto the writing page the pager never left, so the page index
    // alone does not change and the field would stay unfocused behind the keyboard binding to the
    // terminal.
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
    val pagerHeightTarget = extraKeysPageHeight(pagerState.settledPage, keysVisible, gridHeight)
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

    Column(
        modifier = modifier
            .fillMaxWidth()
            // Only the bar's own background fades (strip, key grid, quick actions and writing
            // field all live under it); the icon and key tints stay opaque for readability.
            .background(MaterialTheme.colorScheme.surface.copy(alpha = backgroundAlpha)),
    ) {
        BarStrip(
            keysVisible = keysVisible,
            currentPage = pagerState.currentPage,
            onSelectPage = { page ->
                // A dot tapped while the keys are collapsed expands the bar onto that page.
                keysVisible = true
                state.page = page
            },
            onToggleKeys = { keysVisible = !keysVisible },
        )

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
                ExtraKeysState.PAGE_KEYS -> KeysPage(gridHeight = gridHeight, callbacks = callbacks)
                ExtraKeysState.PAGE_ACTIONS -> QuickActionsPage(callbacks = callbacks)
                else -> TextInputPage(
                    pendingText = pendingText,
                    editTextRef = editTextRef,
                    callbacks = callbacks,
                )
            }
        }
    }
}

/**
 * Page 1 of the pager: the key grid, still the shared [ExtraKeysView] engine embedded through
 * [AndroidView] so a user's custom `extra-keys` layout, modifier buttons, swipe-up popups and
 * long-press repeat keep working unchanged. All pages stay composed (see [ExtraKeysBar]), so the
 * create/release contract with the activity fires only when the whole bar is hidden or the
 * activity is torn down, not on page changes or key collapse.
 */
@Composable
private fun KeysPage(gridHeight: Dp, callbacks: ShellHostCallbacks) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(gridHeight),
    ) {
        AndroidView(
            factory = { context ->
                val extraKeysView = LayoutInflater.from(context)
                    .inflate(R.layout.view_terminal_toolbar_extra_keys, null) as ExtraKeysView
                callbacks.onExtraKeysViewCreated(extraKeysView)
                extraKeysView
            },
            onRelease = { callbacks.onExtraKeysViewReleased() },
            modifier = Modifier
                .fillMaxWidth()
                // Fixed intrinsic height so the grid keeps re-flowing its keys only when the
                // preference changes, not while the pager's height animates above it.
                .height(gridHeight),
        )
    }
}

/**
 * Page 2 of the pager: the three quick actions as icon-plus-label buttons. [QuickAction.TEXT_INPUT]
 * navigates to the writing page through the activity's toggle, which now maps onto the pager.
 */
@Composable
private fun QuickActionsPage(callbacks: ShellHostCallbacks) {
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuickActionButton(
            label = stringResource(R.string.action_toggle_soft_keyboard),
            icon = QuickActionIcons.Keyboard,
            view = view,
        ) { callbacks.onExtraKeysAction(KEYBOARD) }
        QuickActionButton(
            label = stringResource(R.string.action_text_input),
            icon = QuickActionIcons.Text,
            view = view,
        ) { callbacks.onExtraKeysAction(TEXT_INPUT) }
        QuickActionButton(
            label = stringResource(R.string.action_open_settings),
            icon = QuickActionIcons.Settings,
            view = view,
        ) { callbacks.onExtraKeysAction(QuickAction.SETTINGS) }
    }
}

@Composable
private fun QuickActionButton(label: String, icon: ImageVector, view: View, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = label },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Page 3 of the pager (the old field above the keys): an [EditText] that writes a whole line to the
 * current session on send. Inflated from the same layout as before so
 * [com.termux.app.TermuxTerminalViewClient] can still find it by id. Focus and the IME are driven
 * by the bar when the pager settles here, not by the factory, which would grab focus at bar
 * creation while every page is composed.
 */
@Composable
private fun TextInputPage(
    pendingText: Ref<String?>,
    editTextRef: Ref<EditText?>,
    callbacks: ShellHostCallbacks,
) {
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
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * The slim strip above the pager: three tappable page dots on the left and the key-grid collapse
 * chevron on the right. It is the only part of the bar that stays visible when the keys are
 * collapsed — the dots then re-expand the bar onto the chosen page.
 */
@Composable
private fun BarStrip(
    keysVisible: Boolean,
    currentPage: Int,
    onSelectPage: (Int) -> Unit,
    onToggleKeys: () -> Unit,
) {
    val view = LocalView.current
    val pageLabels = listOf(
        stringResource(R.string.extra_keys_page_keys),
        stringResource(R.string.extra_keys_page_actions),
        stringResource(R.string.extra_keys_page_text_input),
    )
    val toggleLabel =
        stringResource(if (keysVisible) R.string.action_hide_keys else R.string.action_show_keys)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        pageLabels.forEachIndexed { index, label ->
            val isActive = index == currentPage
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(width = 40.dp, height = 48.dp)
                    // selectable, not clickable+semantics: it supplies the click action, the touch
                    // target and the ripple. Its semantics are then replaced wholesale, so the dot
                    // publishes exactly one node carrying label, tab role and selected state —
                    // stacking a semantics{} block next to it leaves a second, non-clickable node
                    // that a screen reader announces instead of the actionable one.
                    .selectable(
                        selected = isActive,
                        role = Role.Tab,
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onSelectPage(index)
                        },
                    )
                    .clearAndSetSemantics {
                        contentDescription = label
                        selected = isActive
                        role = Role.Tab
                    },
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (isActive) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            },
                        ),
                )
            }
        }

        Spacer(Modifier.weight(1f))

        IconButton(
            onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onToggleKeys()
            },
            modifier = Modifier.semantics { contentDescription = toggleLabel },
        ) {
            Icon(
                imageVector = if (keysVisible) {
                    QuickActionIcons.ChevronDown
                } else {
                    QuickActionIcons.ChevronUp
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Simple observable holder for a value crossing a composition boundary. */
private class Ref<T>(var value: T?)
