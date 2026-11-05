package com.termux.app.ui.floating

import android.content.Context
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * A Compose overlay with the lifecycle and saved-state owners that Compose requires.
 *
 * The floating terminal is added straight to the [android.view.WindowManager] by `TermuxService`,
 * so there is no Activity above it to install the owners Compose looks for when a view attaches.
 * Without them `AbstractComposeView.onAttachedToWindow` throws
 * `IllegalStateException: ViewTreeLifecycleOwner not found` and the app dies (D-12).
 *
 * Both owners are installed on [view] before it can attach. [destroy] clears them and destroys the
 * lifecycle, which releases the window recomposer; call it after the window manager has removed
 * the view.
 */
internal class FloatOverlayView(
    context: Context,
    content: @Composable () -> Unit,
) : LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    /** The view to hand to the window manager. */
    val view: ComposeView = ComposeView(context)

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    init {
        // The registry throws on first use until restored; there is no saved bundle to hand it,
        // because the overlay outlives the activity that happens to be showing.
        savedStateController.performRestore(null)
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        view.setContent(content)
    }

    /** Detaches the ViewTree owners and destroys the lifecycle. Idempotent. */
    fun destroy() {
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) return
        view.setViewTreeLifecycleOwner(null)
        view.setViewTreeSavedStateRegistryOwner(null)
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}
