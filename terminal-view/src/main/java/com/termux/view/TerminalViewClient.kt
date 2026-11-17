package com.termux.view

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.termux.terminal.TerminalSession

/**
 * The interface for communication between [TerminalView] and its client. It allows for getting
 * various  configuration options from the client and for sending back data to the client like logs,
 * key events, both hardware and IME (which makes it different from that available with
 * [View.setOnKeyListener], etc. It must be set for the
 * [TerminalView] through [TerminalView.setTerminalViewClient].
 */
interface TerminalViewClient {

    /**
     * Callback function on scale events according to [ScaleGestureDetector.getScaleFactor].
     */
    fun onScale(scale: Float): Float

    /**
     * On a single tap on the terminal if terminal mouse reporting not enabled.
     */
    fun onSingleTapUp(e: MotionEvent)

    /**
     * Called when the user taps an OSC 8 hyperlink whose scheme is in the allowed set. The URI has
     * already been sanitized ([LinkUris]) before it reaches this method, so implementors may
     * dispatch it directly. The default implementation does nothing; an implementor that opens links
     * overrides it.
     */
    fun onLinkClicked(uri: String) {
    }

    fun shouldBackButtonBeMappedToEscape(): Boolean

    fun shouldEnforceCharBasedInput(): Boolean

    fun shouldUseCtrlSpaceWorkaround(): Boolean

    fun isTerminalViewSelected(): Boolean

    fun copyModeChanged(copyMode: Boolean)

    fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean

    fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean

    fun onLongPress(event: MotionEvent): Boolean

    fun readControlKey(): Boolean

    fun readAltKey(): Boolean

    fun readShiftKey(): Boolean

    fun readFnKey(): Boolean

    fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean

    fun onEmulatorSet()

    fun logError(tag: String, message: String)

    fun logWarn(tag: String, message: String)

    fun logInfo(tag: String, message: String)

    fun logDebug(tag: String, message: String)

    fun logVerbose(tag: String, message: String)

    fun logStackTraceWithMessage(tag: String, message: String, e: Exception)

    fun logStackTrace(tag: String, e: Exception)
}
