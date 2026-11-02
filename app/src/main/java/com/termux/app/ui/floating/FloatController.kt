package com.termux.app.ui.floating

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession
import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase
import com.termux.shared.view.KeyboardUtils
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import kotlin.math.roundToInt

/**
 * Narrow seam through which [FloatController] creates its dedicated floating session, so the
 * controller can be unit tested without a concrete [com.termux.app.TermuxService]. Implemented by
 * `TermuxService` (whose existing `createFloatSession` already matches this shape).
 */
interface FloatSessionSource {
    fun createFloatSession(client: TerminalSessionClient): TermuxSession?
}

/**
 * Minimal no-op [com.termux.view.TerminalView] client for the floating terminal's embedded view.
 * Selection, link handling and key remapping are out of first-pass scope, so the base defaults
 * (`TermuxTerminalViewClientBase`) are used unchanged.
 */
class FloatViewClient : TermuxTerminalViewClientBase()

/**
 * Owns the in-app floating terminal: the collapsed bubble and expanded window overlays, and the
 * dedicated session shown in them.
 *
 * - [showNewSession] is the entry point: it checks the overlay permission, creates a session via
 *   [sessionSource], attaches it to a controller-owned [TerminalView], and expands the window.
 * - [show]/[hide] add/remove the expanded window; [stop] removes everything and finishes the
 *   session; [isShown] reports whether the window is up.
 * - The controller is its own [FloatSessionClient.Listener]; [onSessionFinished] collapses the
 *   window and clears the session.
 *
 * The [canDrawOverlays] lambda is the only Android permission decision and is injectable so the
 * gate can be driven from a JVM test.
 */
class FloatController @JvmOverloads constructor(
    private val context: Context,
    private val sessionSource: FloatSessionSource,
    private val canDrawOverlays: () -> Boolean = { Settings.canDrawOverlays(context) },
) : FloatSessionClient.Listener {

    /** The dedicated float session, exposed for the test to observe the listener clearing it. */
    internal var floatSession: TermuxSession? = null
        private set

    private val windowManager: WindowManager? =
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    private val terminalView = TerminalView(context, null).apply {
        setTerminalViewClient(FloatViewClient())
    }

    private val prefs = FloatWindowPrefs(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    private val sessionClient = FloatSessionClient(this)

    private var windowView: ComposeView? = null
    private var bubbleView: ComposeView? = null

    private var windowParams: WindowManager.LayoutParams? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    /** Whether the expanded window overlay is currently added. */
    private var shown = false

    /**
     * Shows the expanded floating terminal. If the overlay permission is missing, launches the
     * system manage-overlay screen and does nothing else.
     */
    fun showNewSession() {
        if (!canDrawOverlays()) {
            launchOverlayPermission()
            return
        }
        val session = sessionSource.createFloatSession(sessionClient) ?: return
        floatSession = session
        terminalView.attachSession(session.terminalSession)
        show()
    }

    /** Expands to the window, hiding the bubble. */
    fun show() {
        removeBubble()
        showWindow()
    }

    /** Removes the expanded window (collapse). */
    fun hide() {
        removeWindow()
    }

    /** Removes both overlays and finishes the session. */
    fun stop() {
        removeWindow()
        removeBubble()
        floatSession?.finish()
        floatSession = null
    }

    /** Whether the expanded window overlay is currently up. */
    fun isShown(): Boolean = shown

    // --- FloatSessionClient.Listener ---------------------------------------------------------

    override fun onTextChanged() = Unit

    override fun onTitleChanged() = Unit

    override fun onColorsChanged() = Unit

    override fun onSessionFinished() {
        hide()
        floatSession = null
    }

    // --- expanded window ---------------------------------------------------------------------

    private fun showWindow() {
        if (shown) return

        val state = prefs.read()
        val params = WindowManager.LayoutParams(
            state.width,
            state.height,
            FloatWindowGeometry.windowType(Build.VERSION.SDK_INT),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = state.x
            y = state.y
        }
        windowParams = params

        val composeView = ComposeView(context).apply {
            setContent {
                FloatingWindow(
                    terminalView = terminalView,
                    onDrag = { onWindowDrag(it) },
                    onResize = { onWindowResize(it) },
                    onCollapse = { collapseToBubble() },
                    onClose = { stop() },
                    onToggleKeyboard = { toggleKeyboard() },
                )
            }
        }
        windowView = composeView
        windowManager?.addView(composeView, params)
        shown = true
    }

    private fun removeWindow() {
        windowView?.let { windowManager?.removeView(it) }
        windowView = null
        windowParams = null
        shown = false
    }

    private fun onWindowDrag(delta: IntOffset) {
        val params = windowParams ?: return
        params.x += delta.x
        params.y += delta.y
        windowView?.let { windowManager?.updateViewLayout(it, params) }
        persistGeometry(params)
    }

    private fun onWindowResize(delta: IntSize) {
        val params = windowParams ?: return
        val metrics = context.resources.displayMetrics
        params.width = FloatWindowGeometry.clampWidth(params.width + delta.width, MIN_WIDTH, metrics.widthPixels)
        params.height = FloatWindowGeometry.clampHeight(params.height + delta.height, MIN_HEIGHT, metrics.heightPixels)
        windowView?.let { windowManager?.updateViewLayout(it, params) }
        persistGeometry(params)
    }

    private fun persistGeometry(params: WindowManager.LayoutParams) {
        prefs.write(FloatWindowState(params.x, params.y, params.width, params.height))
    }

    private fun toggleKeyboard() {
        terminalView.requestFocus()
        KeyboardUtils.toggleSoftKeyboard(context)
    }

    // --- collapsed bubble --------------------------------------------------------------------

    private fun collapseToBubble() {
        hide()
        showBubble()
    }

    private fun showBubble() {
        if (bubbleView != null) return

        val size = dp(BUBBLE_SIZE_DP)
        val params = WindowManager.LayoutParams(
            size,
            size,
            FloatWindowGeometry.windowType(Build.VERSION.SDK_INT),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = context.resources.displayMetrics.heightPixels / 2
        }
        bubbleParams = params

        val composeView = ComposeView(context).apply {
            setContent {
                FloatingBubble(
                    onTap = { show() },
                    onDrag = { onBubbleDrag(it) },
                )
            }
        }
        bubbleView = composeView
        windowManager?.addView(composeView, params)
    }

    private fun removeBubble() {
        bubbleView?.let { windowManager?.removeView(it) }
        bubbleView = null
        bubbleParams = null
    }

    private fun onBubbleDrag(delta: IntOffset) {
        val params = bubbleParams ?: return
        params.x += delta.x
        params.y += delta.y
        bubbleView?.let { windowManager?.updateViewLayout(it, params) }
    }

    // --- permission --------------------------------------------------------------------------

    private fun launchOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    private companion object {
        const val PREFS_NAME = "termux_float_window"
        const val BUBBLE_SIZE_DP = 56
        const val MIN_WIDTH = 200
        const val MIN_HEIGHT = 200
    }
}
