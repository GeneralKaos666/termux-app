package com.termux.app.ui.floating

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.termux.R
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences
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

    /**
     * Terminates the shell of a session from [createFloatSession]. The float session is not in the
     * service's sessions list, so this is the only owner that can kill its process.
     */
    fun finishFloatSession(session: TermuxSession)
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
    private val canDrawOverlays: () -> Boolean = {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) true else Settings.canDrawOverlays(context)
    },
) : FloatSessionClient.Listener {

    /** The dedicated float session, exposed for the test to observe the listener clearing it. */
    internal var floatSession: TermuxSession? = null
        private set

    private val windowManager: WindowManager? =
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    internal val terminalView = TerminalView(context, null).apply {
        setTerminalViewClient(FloatViewClient())
        // The activity path applies the user's font size before the view is attached; without a
        // renderer the first layout throws a NullPointerException out of updateSize().
        setTextSize(TermuxAppSharedPreferences.build(context)?.fontSize ?: DEFAULT_FONT_SIZE)
    }

    private val prefs = FloatWindowPrefs(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    private val sessionClient = FloatSessionClient(this)

    private var windowOverlay: FloatOverlayView? = null
    private var bubbleOverlay: FloatOverlayView? = null

    private var windowParams: WindowManager.LayoutParams? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    /** Whether the expanded window overlay is currently added. */
    private var shown = false

    /**
     * Shows the expanded floating terminal. If the overlay permission is missing, launches the
     * system manage-overlay screen and does nothing else.
     */
    fun showNewSession() {
        if (floatSession != null) {
            show()
            return
        }
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
        floatSession?.let(sessionSource::finishFloatSession)
        floatSession = null
    }

    /** Whether the expanded window overlay is currently up. */
    fun isShown(): Boolean = shown

    /** Whether a dedicated float session is currently running. */
    fun isSessionRunning(): Boolean = floatSession != null

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
        val metrics = context.resources.displayMetrics
        // Clamp the stored (or default) geometry so the window always lands fully on screen: the
        // default 300x420dp at x=200dp would otherwise open with its right edge off a phone screen.
        val width = FloatWindowGeometry.clampWidth(dp(state.width), dp(MIN_WIDTH_DP), metrics.widthPixels)
        val height = FloatWindowGeometry.clampHeight(dp(state.height), dp(MIN_HEIGHT_DP), metrics.heightPixels)
        val x = FloatWindowGeometry.clampX(dp(state.x), width, metrics.widthPixels)
        val y = FloatWindowGeometry.clampY(dp(state.y), height, metrics.heightPixels)
        val params = WindowManager.LayoutParams(
            width,
            height,
            FloatWindowGeometry.windowType(Build.VERSION.SDK_INT),
            0,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        windowParams = params

        val overlay = FloatOverlayView(context) {
            FloatingWindow(
                terminalView = terminalView,
                onDrag = { onWindowDrag(it) },
                onDragEnd = { onWindowDragEnd() },
                onResize = { onWindowResize(it) },
                onResizeEnd = { onWindowResizeEnd() },
                onCollapse = { collapseToBubble() },
                onClose = { stop() },
                onToggleKeyboard = { toggleKeyboard() },
            )
        }
        windowOverlay = overlay
        windowManager?.addView(overlay.view, params)
        shown = true
    }

    private fun removeWindow() {
        windowOverlay?.let { overlay ->
            windowManager?.removeView(overlay.view)
            overlay.destroy()
        }
        windowOverlay = null
        windowParams = null
        shown = false
    }

    private fun onWindowDrag(delta: IntOffset) {
        val params = windowParams ?: return
        val metrics = context.resources.displayMetrics
        val clamped = clampDragPosition(
            params.x, params.y, params.width, params.height,
            delta, metrics.widthPixels, metrics.heightPixels,
        )
        params.x = clamped.x
        params.y = clamped.y
        windowOverlay?.view?.let { windowManager?.updateViewLayout(it, params) }
    }

    private fun onWindowDragEnd() {
        windowParams?.let(::persistGeometry)
    }

    private fun onWindowResize(delta: IntSize) {
        val params = windowParams ?: return
        val metrics = context.resources.displayMetrics
        params.width = FloatWindowGeometry.clampWidth(params.width + delta.width, dp(MIN_WIDTH_DP), metrics.widthPixels)
        params.height = FloatWindowGeometry.clampHeight(params.height + delta.height, dp(MIN_HEIGHT_DP), metrics.heightPixels)
        params.x = FloatWindowGeometry.clampX(params.x, params.width, metrics.widthPixels)
        params.y = FloatWindowGeometry.clampY(params.y, params.height, metrics.heightPixels)
        windowOverlay?.view?.let { windowManager?.updateViewLayout(it, params) }
    }

    private fun onWindowResizeEnd() {
        windowParams?.let(::persistGeometry)
    }

    private fun persistGeometry(params: WindowManager.LayoutParams) {
        prefs.write(
            FloatWindowState(
                x = pxToDp(params.x),
                y = pxToDp(params.y),
                width = pxToDp(params.width),
                height = pxToDp(params.height),
            ),
        )
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
        if (bubbleOverlay != null) return

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

        val overlay = FloatOverlayView(context) {
            FloatingBubble(
                onTap = { show() },
                onDrag = { onBubbleDrag(it) },
            )
        }
        bubbleOverlay = overlay
        windowManager?.addView(overlay.view, params)
    }

    private fun removeBubble() {
        bubbleOverlay?.let { overlay ->
            windowManager?.removeView(overlay.view)
            overlay.destroy()
        }
        bubbleOverlay = null
        bubbleParams = null
    }

    private fun onBubbleDrag(delta: IntOffset) {
        val params = bubbleParams ?: return
        val metrics = context.resources.displayMetrics
        val clamped = clampDragPosition(
            params.x, params.y, params.width, params.height,
            delta, metrics.widthPixels, metrics.heightPixels,
        )
        params.x = clamped.x
        params.y = clamped.y
        bubbleOverlay?.view?.let { windowManager?.updateViewLayout(it, params) }
    }

    // --- permission --------------------------------------------------------------------------

    private fun launchOverlayPermission() {
        Toast.makeText(
            context,
            context.getString(R.string.float_overlay_permission_explainer),
            Toast.LENGTH_LONG,
        ).show()
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    private fun pxToDp(value: Int): Int = (value / context.resources.displayMetrics.density).roundToInt()

    private companion object {
        const val PREFS_NAME = "termux_float_window"
        const val BUBBLE_SIZE_DP = 56
        const val MIN_WIDTH_DP = 200
        const val MIN_HEIGHT_DP = 200
    }
}

/**
 * Applies a drag [delta] to a position and clamps the result so the view stays fully on screen.
 * Pure (no window manager) so the drag clamp path can be unit tested directly.
 */
internal fun clampDragPosition(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    delta: IntOffset,
    maxX: Int,
    maxY: Int,
): IntOffset = IntOffset(
    FloatWindowGeometry.clampX(x + delta.x, width, maxX),
    FloatWindowGeometry.clampY(y + delta.y, height, maxY),
)

private const val DEFAULT_FONT_SIZE = 14
