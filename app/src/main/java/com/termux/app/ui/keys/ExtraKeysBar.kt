package com.termux.app.ui.keys

import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.R
import com.termux.app.ui.ShellHostCallbacks
import com.termux.app.ui.keys.QuickAction.KEYBOARD
import com.termux.app.ui.keys.QuickAction.TEXT_INPUT
import com.termux.shared.termux.extrakeys.ExtraKeysView
import com.termux.shared.view.KeyboardUtils

/**
 * Quick actions surfaced in the extra-keys bar, above the key grid. Handled by
 * [com.termux.app.TermuxActivity], which owns the session and terminal clients.
 */
enum class QuickAction { KEYBOARD, TEXT_INPUT, SETTINGS }

/**
 * The bottom extra-keys bar.
 *
 * The key grid is still the shared `ExtraKeysView` engine, embedded through [AndroidView] so that a
 * user's custom `extra-keys` layout, modifier buttons, swipe-up popups and long-press repeat keep
 * working unchanged. This composable provides only the container: a quick-actions row, a
 * collapsible key grid and the optional inline text-send field.
 *
 * @param state Shell-owned bar state (visibility, grid height, text-field visibility).
 * @param initialText Text to restore into the send field on first show (e.g. after rotation).
 * @param callbacks Bridge back into the activity.
 */
@Composable
fun ExtraKeysBar(
    state: ExtraKeysState,
    initialText: String?,
    animationsEnabled: Boolean,
    callbacks: ShellHostCallbacks,
    modifier: Modifier = Modifier,
) {
    // Hoisted above the animated container so restored text is applied once, and not again every
    // time the field is hidden and re-shown.
    val pendingText = remember { Ref<String?>(initialText) }
    var keysVisible by rememberSaveable { mutableStateOf(true) }

    val density = LocalDensity.current
    val gridHeight = with(density) { state.gridHeightPx.toDp() }

    // The grid's AndroidView stays composed and is only hidden by animating its container height
    // and alpha: re-creating it on every collapse would re-inflate the view, re-wire the client and
    // re-parse the custom extra-keys layout each time the user toggles the keys. The create/release
    // contract with the activity therefore fires only when the whole bar is hidden or the activity
    // is torn down, not on grid collapse.
    val gridShown = keysVisible && state.gridHeightPx > 0
    val gridHeightAnim by animateDpAsState(
        targetValue = if (gridShown) gridHeight else 0.dp,
        animationSpec = if (animationsEnabled) tween(180) else snap(),
        label = "extraKeysGridHeight",
    )
    val gridAlpha by animateFloatAsState(
        targetValue = if (gridShown) 1f else 0f,
        animationSpec = if (animationsEnabled) tween(180) else snap(),
        label = "extraKeysGridAlpha",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        AnimatedVisibility(
            visible = state.textInputVisible,
            enter = if (animationsEnabled) expandVertically(animationSpec = tween(180)) + fadeIn() else EnterTransition.None,
            exit = if (animationsEnabled) shrinkVertically(animationSpec = tween(180)) + fadeOut() else ExitTransition.None,
        ) {
            TextInputRow(pendingText = pendingText, callbacks = callbacks)
        }

        QuickActionsRow(
            keysVisible = keysVisible,
            onToggleKeys = { keysVisible = !keysVisible },
            callbacks = callbacks,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(gridHeightAnim)
                .clipToBounds()
                .graphicsLayer { alpha = gridAlpha }
                .then(
                    // A zero-height clipped node would still expose its key buttons to screen
                    // readers; hide it from them while collapsed.
                    if (gridShown) Modifier else Modifier.semantics { invisibleToUser() }
                ),
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
                    .height(gridHeight),
            )
        }
    }
}

@Composable
private fun QuickActionsRow(
    keysVisible: Boolean,
    onToggleKeys: () -> Unit,
    callbacks: ShellHostCallbacks,
) {
    val view = LocalView.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuickActionIconButton(
                label = stringResource(R.string.action_toggle_soft_keyboard),
                icon = QuickActionIcons.Keyboard,
                view = view,
            ) {
                callbacks.onExtraKeysAction(KEYBOARD)
            }
            QuickActionIconButton(
                label = stringResource(R.string.action_text_input),
                icon = QuickActionIcons.Text,
                view = view,
            ) {
                callbacks.onExtraKeysAction(TEXT_INPUT)
            }
            QuickActionIconButton(
                label = stringResource(R.string.action_open_settings),
                icon = QuickActionIcons.Settings,
                view = view,
            ) {
                callbacks.onExtraKeysAction(QuickAction.SETTINGS)
            }

        }

        val toggleLabel = stringResource(if (keysVisible) R.string.action_hide_keys else R.string.action_show_keys)
        IconButton(
            onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onToggleKeys()
            },
            modifier = Modifier.semantics { contentDescription = toggleLabel },
        ) {
            Icon(
                imageVector = if (keysVisible) QuickActionIcons.ChevronDown else QuickActionIcons.ChevronUp,
                contentDescription = null,
                tint = LocalContentColor.current,
            )
        }
    }
}

@Composable
private fun QuickActionIconButton(label: String, icon: ImageVector, view: View, onClick: () -> Unit) {
    IconButton(
        onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        },
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = LocalContentColor.current,
        )
    }
}

/**
 * The old ViewPager's second page, kept behind a quick action: an [EditText] that writes a whole
 * line to the current session on send. Inflated from the same layout as before so
 * [com.termux.app.TermuxTerminalViewClient] can still find it by id and keep the IME shown while it
 * has focus.
 */
@Composable
private fun TextInputRow(pendingText: Ref<String?>, callbacks: ShellHostCallbacks) {
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
            // Focus once the view is attached, then ask the IME to show for it.
            editText.post {
                editText.requestFocus()
                KeyboardUtils.showSoftKeyboard(context, editText)
            }
            editText
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Minimal non-observable holder so a value can be consumed once from an [AndroidView] factory. */
private class Ref<T>(var value: T?)
