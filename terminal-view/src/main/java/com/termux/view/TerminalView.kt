package com.termux.view

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log
import android.view.ActionMode
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityManager
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Scroller
import androidx.annotation.RequiresApi
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalBuffer
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.view.textselection.TextSelectionCursorController

/** View displaying and interacting with a [TerminalSession]. */
class TerminalView(context: Context, attributes: AttributeSet?) : View(context, attributes) {

    /** The currently displayed terminal session, whose emulator is [mEmulator]. */
    @JvmField
    var mTermSession: TerminalSession? = null

    /** Our terminal emulator whose session is [mTermSession]. */
    @JvmField
    var mEmulator: TerminalEmulator? = null

    @JvmField
    var mRenderer: TerminalRenderer? = null

    /** Whether ligatures are requested; carried across the renderer rebuilds in setTextSize/setTypeface. */
    private var mLigaturesEnabled = true

    lateinit var mClient: TerminalViewClient

    private var mTextSelectionCursorController: TextSelectionCursorController? = null

    /** Colour the text-selection handles are tinted with; 0 keeps their built-in colour. */
    private var mSelectionHandleTint = 0

    private var mChoreographer: Choreographer? = null

    /** Observes the system animator scale so a change while attached stops or resumes the cursor. */
    private var mAnimatorScaleObserver: ContentObserver? = null

    @Volatile
    private var mTerminalCursorBlinkerRunnable: TerminalCursorBlinkerRunnable? = null
    private var mTerminalCursorBlinkerRate = 0

    /** Half-life of the eased cursor glide: the remaining distance halves every this many milliseconds. */
    private val mCursorEasingHalfLifeMs = 26f

    /**
     * The system animator duration scale, multiplied into the blink period and the glide half-life.
     * `0` means "remove animations" is on. Read here and refreshed when the setting changes, so the
     * cursor follows a 0.5x or 2x scale the way the Compose shell does, without a settings lookup on
     * every frame.
     */
    @Volatile
    private var mAnimatorDurationScale = readAnimatorDurationScale()

    /** Cursor opacity of the current frame, from the fade-blink envelope. */
    @Volatile
    private var mCursorAlpha = 1f

    /** The cursor position drawn so far, in cells, for the eased bar/underline cursor; negative when unset. */
    @Volatile
    private var mDisplayCursorRow = -1f

    @Volatile
    private var mDisplayCursorCol = -1f

    /** Whether the cursor is currently gliding towards its true cell. */
    @Volatile
    private var mCursorEasing = false

    /** Whether the blink envelope owns [mCursorAlpha]; false means the cursor stays opaque. */
    @Volatile
    private var mCursorBlinkRunning = false

    /** Set while a frame was requested only to repaint the cursor, so it must not consume the dirty state. */
    @Volatile
    private var mCursorOnlyFrame = false

    /** The top row of text to display. Ranges from -activeTranscriptRows to 0. */
    private var mTopRow = 0

    @JvmField
    var mDefaultSelectors = intArrayOf(-1, -1, -1, -1)

    @JvmField
    var mScaleFactor = 1f

    @JvmField
    val mGestureRecognizer: GestureAndScaleRecognizer

    /** Keep track of where mouse touch event started which we report as mouse scroll. */
    private var mMouseScrollStartX = -1
    private var mMouseScrollStartY = -1

    /** Keep track of the time when a touch event leading to sending mouse scroll events started. */
    private var mMouseStartDownTime = -1L

    @JvmField
    val mScroller: Scroller

    /** What was left in from scrolling movement. */
    @JvmField
    var mScrollRemainder = 0f

    /** If non-zero, this is the last unicode code point received if that was a combining character. */
    @JvmField
    var mCombiningAccent = 0

    /**
     * The buffer's change counter as of the last completed draw, or `-1` when nothing has been
     * drawn yet (session/emulator rebind). A mismatch with the current counter forces a full repaint.
     */
    private var mLastSeenChangeCounter = -1L

    /** The cursor cell painted by the last draw; the renderer paints the cursor into the row band. */
    private var mLastCursorRow = -1
    private var mLastCursorCol = -1

    /** Whether the last draw painted in reverse video; `CSI ? 5 h/l` changes no cell. */
    private var mLastReverseVideo = false

    /** Whether the last draw painted the cursor at all; `CSI ? 25 h/l` changes no cell. */
    private var mLastCursorVisible = false

    /** Cursor shape painted by the last draw; `DECSCUSR` changes no cell. */
    private var mLastCursorStyle = -1

    /** Palette change counter as of the last completed draw, or `-1` before the first draw. */
    private var mLastPaletteChangeCounter = -1

    /** Reused to avoid allocating a [Rect] on every [onDraw]. */
    private val mClipBounds = Rect()

    /**
     * The current AutoFill type returned for [View.getAutofillType] by [getAutofillType].
     *
     * The default is [View.AUTOFILL_TYPE_NONE] so that AutoFill UI, like toolbar above keyboard
     * is not shown automatically, like on Activity starts/View create. This value should be updated
     * to required value, like [View.AUTOFILL_TYPE_TEXT] before calling
     * [AutofillManager.requestAutofill] so that AutoFill UI shows. The updated value
     * set will automatically be restored to [View.AUTOFILL_TYPE_NONE] in
     * [autofill] so that AutoFill UI isn't shown anymore by calling [resetAutoFill].
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private var mAutoFillType = View.AUTOFILL_TYPE_NONE

    /**
     * The current AutoFill type returned for [View.getImportantForAutofill] by
     * [getImportantForAutofill].
     *
     * The default is [View.IMPORTANT_FOR_AUTOFILL_NO] so that view is not considered important
     * for AutoFill. This value should be updated to required value, like
     * [View.IMPORTANT_FOR_AUTOFILL_YES] before calling [AutofillManager.requestAutofill]
     * so that Android and apps consider the view as important for AutoFill to process the request.
     * The updated value set will automatically be restored to [View.IMPORTANT_FOR_AUTOFILL_NO] in
     * [autofill] by calling [resetAutoFill].
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private var mAutoFillImportance = View.IMPORTANT_FOR_AUTOFILL_NO

    /**
     * The current AutoFill hints returned for [View.getAutofillHints] by [getAutofillHints].
     *
     * The default is an empty `string[]`. This value should be updated to required value. The
     * updated value set will automatically be restored an empty `string[]` in
     * [autofill] by calling [resetAutoFill].
     */
    private var mAutoFillHints = emptyArray<String>()

    private val mAccessibilityEnabled: Boolean

    init {
        mScroller = Scroller(context)

        mGestureRecognizer = GestureAndScaleRecognizer(context, object : GestureAndScaleRecognizer.Listener {

            var scrolledWithFinger = false

            override fun onUp(e: MotionEvent): Boolean {
                mScrollRemainder = 0.0f
                if (mEmulator != null && mEmulator!!.isMouseTrackingActive && !e.isFromSource(InputDevice.SOURCE_MOUSE) && !isSelectingText() && !scrolledWithFinger) {
                    // Quick event processing when mouse tracking is active - do not wait for check of double tapping
                    // for zooming.
                    sendMouseEventCode(e, TerminalEmulator.MOUSE_LEFT_BUTTON, true)
                    sendMouseEventCode(e, TerminalEmulator.MOUSE_LEFT_BUTTON, false)
                    return true
                }
                scrolledWithFinger = false
                return false
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (mEmulator == null) return true

                if (isSelectingText()) {
                    stopTextSelectionMode()
                    return true
                }
                requestFocus()
                val columnAndRow = getColumnAndRow(e, true)
                val linkUri = getLinkUriAt(mEmulator!!, columnAndRow[1], columnAndRow[0])
                if (linkUri != null) {
                    // A hyperlink hit consumes the gesture: the tap opened the link, it is not also a
                    // client tap (which would toggle the soft keyboard, etc.).
                    mClient.onLinkClicked(linkUri)
                    return true
                }
                mClient.onSingleTapUp(e)
                return true
            }

            override fun onScroll(e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (mEmulator == null) return true
                if (mEmulator!!.isMouseTrackingActive && e2.isFromSource(InputDevice.SOURCE_MOUSE)) {
                    // If moving with mouse pointer while pressing button, report that instead of scroll.
                    // This means that we never report moving with button press-events for touch input,
                    // since we cannot just start sending these events without a starting press event,
                    // which we do not do for touch input, only mouse in onTouchEvent().
                    sendMouseEventCode(e2, TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, true)
                } else {
                    scrolledWithFinger = true
                    var distanceY = dy
                    distanceY += mScrollRemainder
                    val deltaRows = (distanceY / mRenderer!!.mFontLineSpacing).toInt()
                    mScrollRemainder = distanceY - deltaRows * mRenderer!!.mFontLineSpacing
                    doScroll(e2, deltaRows)
                }
                return true
            }

            override fun onScale(focusX: Float, focusY: Float, scale: Float): Boolean {
                if (mEmulator == null || isSelectingText()) return true
                mScaleFactor *= scale
                mScaleFactor = mClient.onScale(mScaleFactor)
                return true
            }

            override fun onFling(e: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (mEmulator == null) return true
                // Do not start scrolling until last fling has been taken care of:
                if (!mScroller.isFinished) return true

                val mouseTrackingAtStartOfFling = mEmulator!!.isMouseTrackingActive
                val scale = 0.25f
                if (mouseTrackingAtStartOfFling) {
                    mScroller.fling(0, 0, 0, -(velocityY * scale).toInt(), 0, 0, -mEmulator!!.mRows / 2, mEmulator!!.mRows / 2)
                } else {
                    mScroller.fling(0, mTopRow, 0, -(velocityY * scale).toInt(), 0, 0, -mEmulator!!.screen.activeTranscriptRows, 0)
                }

                // The frame loop outlives this dispatch, and the framework recycles the dispatched event
                // as soon as it returns, so scroll with our own copy and recycle it when the loop ends.
                val flingEvent = MotionEvent.obtain(e)
                post(object : Runnable {
                    private var mLastY = 0

                    override fun run() {
                        try {
                            val emulator = mEmulator ?: return
                            if (mouseTrackingAtStartOfFling != emulator.isMouseTrackingActive) {
                                mScroller.abortAnimation()
                                return
                            }
                            if (mScroller.isFinished) return
                            val more = mScroller.computeScrollOffset()
                            val newY = mScroller.currY
                            val diff = if (mouseTrackingAtStartOfFling) newY - mLastY else newY - mTopRow
                            doScroll(flingEvent, diff)
                            mLastY = newY
                            if (FlingLoop.continues(more, mScroller.isFinished)) post(this)
                        } finally {
                            // Both this return and the abort above finish the scroller, which is the
                            // single point where the copy is no longer needed. A gone emulator also
                            // ends the loop.
                            if (mScroller.isFinished || mEmulator == null) flingEvent.recycle()
                        }
                    }
                })

                return true
            }

            override fun onDown(x: Float, y: Float): Boolean {
                // Why is true not returned here?
                // https://developer.android.com/training/gestures/detector.html#detect-a-subset-of-supported-gestures
                // Although setting this to true still does not solve the following errors when long pressing in terminal view text area
                // ViewDragHelper: Ignoring pointerId=0 because ACTION_DOWN was not received for this pointer before ACTION_MOVE
                // Commenting out the call to mGestureDetector.onTouchEvent(event) in GestureAndScaleRecognizer#onTouchEvent() removes
                // the error logging, so issue is related to GestureDetector
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // Do not treat is as a single confirmed tap - it may be followed by zoom.
                return false
            }

            override fun onLongPress(e: MotionEvent) {
                if (mGestureRecognizer.isInProgress()) return
                if (mClient.onLongPress(e)) return
                if (!isSelectingText()) {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    startTextSelectionMode(e)
                }
            }
        })

        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        mAccessibilityEnabled = am.isEnabled
    }

    /**
     * @param client The [TerminalViewClient] interface implementation to allow
     *                           for communication between [TerminalView] and its client.
     */
    fun setTerminalViewClient(client: TerminalViewClient) {
        this.mClient = client
    }

    /**
     * Sets whether terminal view key logging is enabled or not.
     *
     * @param value The boolean value that defines the state.
     */
    fun setIsTerminalViewKeyLoggingEnabled(value: Boolean) {
        TERMINAL_VIEW_KEY_LOGGING_ENABLED = value
    }

    /**
     * Attach a [TerminalSession] to this view.
     *
     * @param session The [TerminalSession] this view will be displaying.
     */
    fun attachSession(session: TerminalSession): Boolean {
        if (session === mTermSession) return false

        mTermSession = session
        mEmulator = null
        mCombiningAccent = 0
        resetDirtyRepaintState()

        // The emulator's cached value will be read in `updateSize()` when emulator is set.
        setTopRow(0, false)

        updateSize()

        // Wait with enabling the scrollbar until we have a terminal to get scroll position from.
        isVerticalScrollBarEnabled = true

        return true
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Ensure that inputType is only set if TerminalView is selected view with the keyboard and
        // an alternate view is not selected, like an EditText. This is necessary if an activity is
        // initially started with the alternate view or if activity is returned to from another app
        // and the alternate view was the one selected the last time.
        if (mClient.isTerminalViewSelected()) {
            if (mClient.shouldEnforceCharBasedInput()) {
                // Some keyboards seems do not reset the internal state on TYPE_NULL.
                // Affects mostly Samsung stock keyboards.
                // https://github.com/termux/termux-app/issues/686
                // However, this is not a valid value as per AOSP since `InputType.TYPE_CLASS_*` is
                // not set and it logs a warning:
                // W/InputAttributes: Unexpected input class: inputType=0x00080090 imeOptions=0x02000000
                // https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:packages/inputmethods/LatinIME/java/src/com/android/inputmethod/latin/InputAttributes.java;l=79
                outAttrs.inputType = InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            } else {
                // Using InputType.NULL is the most correct input type and avoids issues with other hacks.
                //
                // Previous keyboard issues:
                // https://github.com/termux/termux-packages/issues/25
                // https://github.com/termux/termux-app/issues/87.
                // https://github.com/termux/termux-app/issues/126.
                // https://github.com/termux/termux-app/issues/137 (japanese chars and TYPE_NULL).
                outAttrs.inputType = InputType.TYPE_NULL
            }
        } else {
            // Corresponds to android:inputType="text"
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        }

        // Note that IME_ACTION_NONE cannot be used as that makes it impossible to input newlines using the on-screen
        // keyboard on Android TV (see https://github.com/termux/termux-app/issues/221).
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN

        return object : BaseInputConnection(this@TerminalView, true) {

            override fun finishComposingText(): Boolean {
                if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) mClient.logInfo(LOG_TAG, "IME: finishComposingText()")
                super.finishComposingText()

                val content = editable ?: return true
                sendTextToTerminal(content)
                content.clear()
                return true
            }

            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
                    mClient.logInfo(LOG_TAG, "IME: commitText(\"" + text + "\", " + newCursorPosition + ")")
                }
                super.commitText(text, newCursorPosition)

                if (mEmulator == null) return true

                val content = editable ?: return true
                sendTextToTerminal(content)
                content.clear()
                return true
            }

            override fun deleteSurroundingText(leftLength: Int, rightLength: Int): Boolean {
                if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
                    mClient.logInfo(LOG_TAG, "IME: deleteSurroundingText(" + leftLength + ", " + rightLength + ")")
                }
                // The stock Samsung keyboard with 'Auto check spelling' enabled sends leftLength > 1.
                val deleteKey = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
                for (i in 0 until leftLength) sendKeyEvent(deleteKey)
                return super.deleteSurroundingText(leftLength, rightLength)
            }

            fun sendTextToTerminal(text: CharSequence) {
                stopTextSelectionMode()
                for (stroke in ImeTextEncoder.encode(text, mClient.readShiftKey())) {
                    inputCodePoint(KEY_EVENT_SOURCE_SOFT_KEYBOARD, stroke.codePoint, stroke.controlHeld, false)
                }
            }
        }
    }

    override fun computeVerticalScrollRange(): Int {
        return if (mEmulator == null) 1 else mEmulator!!.screen.activeRows
    }

    override fun computeVerticalScrollExtent(): Int {
        return if (mEmulator == null) 1 else mEmulator!!.mRows
    }

    override fun computeVerticalScrollOffset(): Int {
        return if (mEmulator == null) 1 else mEmulator!!.screen.activeRows + mTopRow - mEmulator!!.mRows
    }

    fun onScreenUpdated() {
        onScreenUpdated(false)
    }

    fun onScreenUpdated(skipScrollingIn: Boolean) {
        val emulator = mEmulator ?: return
        var skipScrolling = skipScrollingIn

        // Captured before the clamp below, so a clamp-induced viewport move counts as a scroll.
        val topRowBefore = mTopRow

        val rowsInHistory = emulator.screen.activeTranscriptRows
        if (mTopRow < -rowsInHistory) setTopRow(-rowsInHistory)

        if (isSelectingText() || emulator.isAutoScrollDisabled) {
            // Do not scroll when selecting text.
            val rowShift = emulator.scrollCounter
            if (-mTopRow + rowShift > rowsInHistory) {
                // .. unless we're hitting the end of history transcript, in which
                // case we abort text selection and scroll to end.
                if (isSelectingText())
                    stopTextSelectionMode()

                if (emulator.isAutoScrollDisabled) {
                    setTopRow(-rowsInHistory)
                    skipScrolling = true
                }
            } else {
                skipScrolling = true
                setTopRow(mTopRow - rowShift)
                decrementYTextSelectionCursors(rowShift)
            }
        }

        if (!skipScrolling && mTopRow != 0) {
            // Scroll down if not already there.
            if (mTopRow < -3) {
                // Awaken scroll bars only if scrolling a noticeable amount
                // - we do not want visible scroll bars during normal typing
                // of one row at a time.
                awakenScrollBars()
            }
            setTopRow(0)
        }

        emulator.clearScrollCounter()

        val screen: TerminalBuffer = emulator.screen
        val topRowChanged = (mTopRow != topRowBefore)
        // The renderer paints the cursor into the row band, and reverse video (CSI ? 5 h/l) changes no
        // cell, so neither would dirty a row or tick the change counter: compare against the last draw.
        val overlayChanged = emulator.cursorRow != mLastCursorRow ||
            emulator.cursorCol != mLastCursorCol ||
            emulator.isReverseVideo != mLastReverseVideo ||
            emulator.shouldCursorBeVisible() != mLastCursorVisible ||
            emulator.cursorStyle != mLastCursorStyle ||
            emulator.mColors.changeCounter != mLastPaletteChangeCounter

        val decision = RowInvalidation.decide(
            mLastSeenChangeCounter,
            screen.changeCounter, topRowChanged || overlayChanged,
            RowInvalidation.dirtyByIndex(screen, mTopRow, emulator.mRows),
            emulator.mRows, height,
            mRenderer!!.mFontLineSpacing, mRenderer!!.mFontLineSpacingAndAscent,
        )

        if (decision.kind == RowInvalidation.Result.FULL) {
            invalidate()
        } else if (decision.kind == RowInvalidation.Result.RANGE) {
            invalidate(0, decision.topPx, width, decision.bottomPx)
        }
        // Result.NONE: nothing visible changed, so deliberately do not invalidate.

        if (mAccessibilityEnabled) contentDescription = getText()
    }

    /**
     * Repaints when the palette changed since the last draw, and is a no-op otherwise. A palette-only
     * change (OSC 4/10/11/12/104) dirties no row and ticks no change counter, so it never reaches
     * [onScreenUpdated]; the hosting activity calls this from its
     * `onColorsChanged` path instead of a blanket [invalidate].
     */
    fun onPaletteChanged() {
        val emulator = mEmulator ?: return
        if (emulator.mColors.changeCounter != mLastPaletteChangeCounter) {
            invalidate()
        }
    }

    /** This must be called by the hosting activity in [Activity.onContextMenuClosed]
     * when context menu for the [TerminalView] is started by
     * TextSelectionCursorController.ACTION_MORE is closed. */
    fun onContextMenuClosed(menu: Menu) {
        // Unset the stored text since it shouldn't be used anymore and should be cleared from memory
        unsetStoredSelectedText()
    }

    /**
     * Sets the colour the text-selection handles are drawn in, and applies it to handles that
     * already exist.
     *
     * @param color an opaque ARGB colour, or 0 to keep the drawables' built-in colour.
     */
    fun setSelectionHandleTint(color: Int) {
        mSelectionHandleTint = color
        mTextSelectionCursorController?.setHandleTint(color)
    }

    fun getSelectionHandleTint(): Int {
        return mSelectionHandleTint
    }

    /**
     * Sets the text size, which in turn sets the number of rows and columns.
     *
     * @param textSize the new font size, in density-independent pixels.
     */
    fun setTextSize(textSize: Int) {
        mRenderer = TerminalRenderer(
            textSize,
            if (mRenderer == null) Typeface.MONOSPACE else mRenderer!!.mTypeface, mLigaturesEnabled,
        )
        updateSize()
        invalidate()
    }

    fun setTypeface(newTypeface: Typeface?) {
        mRenderer = TerminalRenderer(mRenderer!!.mTextSize, newTypeface, mLigaturesEnabled)
        updateSize()
        invalidate()
    }

    /**
     * Sets whether the font's ligatures are used. The feature string cannot change the cell metrics, so a change
     * only needs the renderer rebuilt and the view repainted; no re-layout is requested.
     */
    fun setLigaturesEnabled(ligaturesEnabled: Boolean) {
        if (mLigaturesEnabled == ligaturesEnabled) return
        mLigaturesEnabled = ligaturesEnabled
        if (mRenderer != null) {
            mRenderer = TerminalRenderer(mRenderer!!.mTextSize, mRenderer!!.mTypeface, mLigaturesEnabled)
        }
        invalidate()
    }

    override fun onCheckIsTextEditor(): Boolean {
        return true
    }

    override fun isOpaque(): Boolean {
        return true
    }

    /**
     * Get the zero indexed column and row of the terminal view for the
     * position of the event.
     *
     * @param event The event with the position to get the column and row for.
     * @param relativeToScroll If true the column number will take the scroll
     * position into account. E.g. if scrolled 3 lines up and the event
     * position is in the top left, column will be -3 if relativeToScroll is
     * true and 0 if relativeToScroll is false.
     * @return Array with the column and row.
     */
    fun getColumnAndRow(event: MotionEvent, relativeToScroll: Boolean): IntArray {
        val renderer = mRenderer!!
        val column = (event.x / renderer.mFontWidth).toInt()
        var row = ((event.y - renderer.mFontLineSpacingAndAscent) / renderer.mFontLineSpacing).toInt()
        if (relativeToScroll) {
            row += mTopRow
        }
        return intArrayOf(column, row)
    }

    /** Send a single mouse event code to the terminal. */
    fun sendMouseEventCode(e: MotionEvent, button: Int, pressed: Boolean) {
        val columnAndRow = getColumnAndRow(e, false)
        var x = columnAndRow[0] + 1
        var y = columnAndRow[1] + 1
        if (pressed && (button == TerminalEmulator.MOUSE_WHEELDOWN_BUTTON || button == TerminalEmulator.MOUSE_WHEELUP_BUTTON)) {
            if (mMouseStartDownTime == e.downTime) {
                x = mMouseScrollStartX
                y = mMouseScrollStartY
            } else {
                mMouseStartDownTime = e.downTime
                mMouseScrollStartX = x
                mMouseScrollStartY = y
            }
        }
        mEmulator!!.sendMouseEvent(button, x, y, pressed)
    }

    /** Perform a scroll, either from dragging the screen or by scrolling a mouse wheel. */
    fun doScroll(event: MotionEvent, rowsDown: Int) {
        val emulator = mEmulator ?: return
        val up = rowsDown < 0
        val amount = Math.abs(rowsDown)
        for (i in 0 until amount) {
            if (emulator.isMouseTrackingActive) {
                sendMouseEventCode(event, if (up) TerminalEmulator.MOUSE_WHEELUP_BUTTON else TerminalEmulator.MOUSE_WHEELDOWN_BUTTON, true)
            } else if (emulator.isAlternateBufferActive) {
                // Send up and down key events for scrolling, which is what some terminals do to make scroll work in
                // e.g. less, which shifts to the alt screen without mouse handling.
                handleKeyCode(if (up) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN, 0)
            } else {
                setTopRow(Math.min(0, Math.max(-emulator.screen.activeTranscriptRows, mTopRow + (if (up) -1 else 1))))
                if (!awakenScrollBars()) invalidate()
            }
        }
    }

    /** Overriding [View.onGenericMotionEvent]. */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (mEmulator != null && event.isFromSource(InputDevice.SOURCE_MOUSE) && event.action == MotionEvent.ACTION_SCROLL) {
            // Handle mouse wheel scrolling.
            val up = event.getAxisValue(MotionEvent.AXIS_VSCROLL) > 0.0f
            doScroll(event, if (up) -3 else 3)
            return true
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    @TargetApi(23)
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (mEmulator == null) return true
        val action = event.action

        if (isSelectingText()) {
            updateFloatingToolbarVisibility(event)
            mGestureRecognizer.onTouchEvent(event)
            return true
        } else if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            if (event.isButtonPressed(MotionEvent.BUTTON_SECONDARY)) {
                if (action == MotionEvent.ACTION_DOWN) showContextMenu()
                return true
            } else if (event.isButtonPressed(MotionEvent.BUTTON_TERTIARY)) {
                val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipData: ClipData? = clipboardManager.primaryClip
                if (clipData != null) {
                    val clipItem: ClipData.Item? = clipData.getItemAt(0)
                    if (clipItem != null) {
                        val text = clipItem.coerceToText(context)
                        if (!TextUtils.isEmpty(text)) mEmulator!!.paste(text.toString())
                    }
                }
            } else if (mEmulator!!.isMouseTrackingActive) { // BUTTON_PRIMARY.
                when (event.action) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_UP,
                    -> sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON, event.action == MotionEvent.ACTION_DOWN)

                    MotionEvent.ACTION_MOVE -> sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, true)
                }
            }
        }

        mGestureRecognizer.onTouchEvent(event)
        return true
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
            mClient.logInfo(LOG_TAG, "onKeyPreIme(keyCode=" + keyCode + ", event=" + event + ")")
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            cancelRequestAutoFill()
            if (isSelectingText()) {
                stopTextSelectionMode()
                return true
            } else if (mClient.shouldBackButtonBeMappedToEscape()) {
                // Intercept back button to treat it as escape:
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> return onKeyDown(keyCode, event)
                    KeyEvent.ACTION_UP -> return onKeyUp(keyCode, event)
                }
            }
        } else if (mClient.shouldUseCtrlSpaceWorkaround() &&
            keyCode == KeyEvent.KEYCODE_SPACE && event.isCtrlPressed
        ) {
            /* ctrl+space does not work on some ROMs without this workaround.
               However, this breaks it on devices where it works out of the box. */
            return onKeyDown(keyCode, event)
        }
        return super.onKeyPreIme(keyCode, event)
    }

    /**
     * Key presses in software keyboards will generally NOT trigger this listener, although some
     * may elect to do so in some situations. Do not rely on this to catch software key presses.
     * Gboard calls this when shouldEnforceCharBasedInput() is disabled (InputType.TYPE_NULL) instead
     * of calling commitText(), with deviceId=-1. However, Hacker's Keyboard, OpenBoard, LG Keyboard
     * call commitText().
     *
     * This function may also be called directly without android calling it, like by
     * `TerminalExtraKeys` which generates a KeyEvent manually which uses [KeyCharacterMap.VIRTUAL_KEYBOARD]
     * as the device (deviceId=-1), as does Gboard. That would normally use mappings defined in
     * `/system/usr/keychars/Virtual.kcm`. You can run `dumpsys input` to find the `KeyCharacterMapFile`
     * used by virtual keyboard or hardware keyboard. Note that virtual keyboard device is not the
     * same as software keyboard, like Gboard, etc. Its a fake device used for generating events and
     * for testing.
     *
     * We handle shift key in `commitText()` to convert codepoint to uppercase case there with a
     * call to [Character.toUpperCase], but here we instead rely on getUnicodeChar() for
     * conversion of keyCode, for both hardware keyboard shift key (via effectiveMetaState) and
     * `mClient.readShiftKey()`, based on value in kcm files.
     * This may result in different behaviour depending on keyboard and android kcm files set for the
     * InputDevice for the event passed to this function. This will likely be an issue for non-english
     * languages since `Virtual.kcm` in english only by default or at least in AOSP. For both hardware
     * shift key (via effectiveMetaState) and `mClient.readShiftKey()`, `getUnicodeChar()` is used
     * for shift specific behaviour which usually is to uppercase.
     *
     * For fn key on hardware keyboard, android checks kcm files for hardware keyboards, which is
     * `Generic.kcm` by default, unless a vendor specific one is defined. The event passed will have
     * [KeyEvent.META_FUNCTION_ON] set. If the kcm file only defines a single character or unicode
     * code point `\\uxxxx`, then only one event is passed with that value. However, if kcm defines
     * a `fallback` key for fn or others, like `key DPAD_UP { ... fn: fallback PAGE_UP }`, then
     * android will first pass an event with original key `DPAD_UP` and [KeyEvent.META_FUNCTION_ON]
     * set. But this function will not consume it and android will pass another event with `PAGE_UP`
     * and [KeyEvent.META_FUNCTION_ON] not set, which will be consumed.
     *
     * Now there are some other issues as well, firstly ctrl and alt flags are not passed to
     * `getUnicodeChar()`, so modified key values in kcm are not used. Secondly, if the kcm file
     * for other modifiers like shift or fn define a non-alphabet, like { fn: '\u0015' } to act as
     * DPAD_LEFT, the `getUnicodeChar()` will correctly return `21` as the code point but action will
     * not happen because the `handleKeyCode()` function that transforms DPAD_LEFT to `\033[D`
     * escape sequence for the terminal to perform the left action would not be called since its
     * called before `getUnicodeChar()` and terminal will instead get `21 0x15 Negative Acknowledgement`.
     * The solution to such issues is calling `getUnicodeChar()` before the call to `handleKeyCode()`
     * if user has defined a custom kcm file, like done in POC mentioned in #2237. Note that
     * Hacker's Keyboard calls `commitText()` so don't test fn/shift with it for this function.
     * https://github.com/termux/termux-app/pull/2237
     * https://github.com/agnostic-apollo/termux-app/blob/terminal-code-point-custom-mapping/terminal-view/src/main/java/com/termux/view/TerminalView.java
     *
     * Key Character Map (kcm) and Key Layout (kl) files info:
     * https://source.android.com/devices/input/key-character-map-files
     * https://source.android.com/devices/input/key-layout-files
     * https://source.android.com/devices/input/keyboard-devices
     * AOSP kcm and kl files:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/data/keyboards
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/packages/InputDevices/res/raw
     *
     * KeyCodes:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/core/java/android/view/KeyEvent.java
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/native/include/android/keycodes.h
     *
     * `dumpsys input`:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/services/inputflinger/reader/EventHub.cpp;l=1917
     *
     * Loading of keymap:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/services/inputflinger/reader/EventHub.cpp;l=1644
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/libs/input/Keyboard.cpp;l=41
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/libs/input/InputDevice.cpp
     * OVERLAY keymaps for hardware keyboards may be combined as well:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/libs/input/KeyCharacterMap.cpp;l=165
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/libs/input/KeyCharacterMap.cpp;l=831
     *
     * Parse kcm file:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/libs/input/KeyCharacterMap.cpp;l=727
     * Parse key value:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/libs/input/KeyCharacterMap.cpp;l=981
     *
     * `KeyEvent.getUnicodeChar()`
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/core/java/android/view/KeyEvent.java;l=2716
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/view/KeyCharacterMap.java;l=368
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/core/jni/android_view_KeyCharacterMap.cpp;l=117
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/libs/input/KeyCharacterMap.cpp;l=231
     *
     * Keyboard layouts advertised by applications, like for hardware keyboards via #ACTION_QUERY_KEYBOARD_LAYOUTS
     * Config is stored in `/data/system/input-manager-state.xml`
     * https://github.com/ris58h/custom-keyboard-layout
     * Loading from apps:
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/input/InputManagerService.java;l=1221
     * Set:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/core/java/android/hardware/input/InputManager.java;l=89
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/core/java/android/hardware/input/InputManager.java;l=543
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:packages/apps/Settings/src/com/android/settings/inputmethod/KeyboardLayoutDialogFragment.java;l=167
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/input/InputManagerService.java;l=1385
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/input/PersistentDataStore.java
     * Get overlay keyboard layout
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/input/InputManagerService.java;l=2158
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/services/core/jni/com_android_server_input_InputManagerService.cpp;l=616
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
            mClient.logInfo(LOG_TAG, "onKeyDown(keyCode=" + keyCode + ", isSystem()=" + event.isSystem + ", event=" + event + ")")
        if (mEmulator == null) return true
        if (isSelectingText()) {
            stopTextSelectionMode()
        }

        if (mClient.onKeyDown(keyCode, event, mTermSession!!)) {
            invalidate()
            return true
        } else if (event.isSystem && (!mClient.shouldBackButtonBeMappedToEscape() || keyCode != KeyEvent.KEYCODE_BACK)) {
            return super.onKeyDown(keyCode, event)
        } else if (event.action == KeyEvent.ACTION_MULTIPLE && keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            mTermSession!!.write(event.characters)
            return true
        } else if (keyCode == KeyEvent.KEYCODE_LANGUAGE_SWITCH) {
            return super.onKeyDown(keyCode, event)
        }

        val metaState = event.metaState
        val controlDown = event.isCtrlPressed || mClient.readControlKey()
        val leftAltDown = (metaState and KeyEvent.META_ALT_LEFT_ON) != 0 || mClient.readAltKey()
        val shiftDown = event.isShiftPressed || mClient.readShiftKey()
        val rightAltDownFromEvent = (metaState and KeyEvent.META_ALT_RIGHT_ON) != 0

        var keyMod = 0
        if (controlDown) keyMod = keyMod or KeyHandler.KEYMOD_CTRL
        if (event.isAltPressed || leftAltDown) keyMod = keyMod or KeyHandler.KEYMOD_ALT
        if (shiftDown) keyMod = keyMod or KeyHandler.KEYMOD_SHIFT
        if (event.isNumLockOn) keyMod = keyMod or KeyHandler.KEYMOD_NUM_LOCK
        // https://github.com/termux/termux-app/issues/731
        if (!event.isFunctionPressed && handleKeyCode(keyCode, keyMod)) {
            if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) mClient.logInfo(LOG_TAG, "handleKeyCode() took key event")
            return true
        }

        // Clear Ctrl since we handle that ourselves:
        var bitsToClear = KeyEvent.META_CTRL_MASK
        if (rightAltDownFromEvent) {
            // Let right Alt/Alt Gr be used to compose characters.
        } else {
            // Use left alt to send to terminal (e.g. Left Alt+B to jump back a word), so remove:
            bitsToClear = bitsToClear or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        }
        var effectiveMetaState = event.metaState and bitsToClear.inv()

        if (shiftDown) effectiveMetaState = effectiveMetaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (mClient.readFnKey()) effectiveMetaState = effectiveMetaState or KeyEvent.META_FUNCTION_ON

        var result = event.getUnicodeChar(effectiveMetaState)
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
            mClient.logInfo(LOG_TAG, "KeyEvent#getUnicodeChar(" + effectiveMetaState + ") returned: " + result)
        if (result == 0) {
            return false
        }

        val oldCombiningAccent = mCombiningAccent
        if ((result and KeyCharacterMap.COMBINING_ACCENT) != 0) {
            // If entered combining accent previously, write it out:
            if (mCombiningAccent != 0)
                inputCodePoint(event.deviceId, mCombiningAccent, controlDown, leftAltDown)
            mCombiningAccent = result and KeyCharacterMap.COMBINING_ACCENT_MASK
        } else {
            if (mCombiningAccent != 0) {
                val combinedChar = KeyCharacterMap.getDeadChar(mCombiningAccent, result)
                if (combinedChar > 0) result = combinedChar
                mCombiningAccent = 0
            }
            inputCodePoint(event.deviceId, result, controlDown, leftAltDown)
        }

        if (mCombiningAccent != oldCombiningAccent) invalidate()

        return true
    }

    fun inputCodePoint(eventSource: Int, codePointIn: Int, controlDownFromEvent: Boolean, leftAltDownFromEvent: Boolean) {
        var codePoint = codePointIn
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
            mClient.logInfo(
                LOG_TAG, "inputCodePoint(eventSource=" + eventSource + ", codePoint=" + codePoint + ", controlDownFromEvent=" + controlDownFromEvent + ", leftAltDownFromEvent=" +
                    leftAltDownFromEvent + ")",
            )
        }

        if (mTermSession == null) return

        // Ensure cursor is shown when a key is pressed down like long hold on (arrow) keys
        if (mEmulator != null)
            mEmulator!!.setCursorBlinkState(true)

        val controlDown = controlDownFromEvent || mClient.readControlKey()
        val altDown = leftAltDownFromEvent || mClient.readAltKey()

        if (mClient.onCodePoint(codePoint, controlDown, mTermSession!!)) return

        if (controlDown) {
            codePoint = KeyCodePoints.applyControl(codePoint)
        }

        if (codePoint > -1) {
            // If not virtual or soft keyboard.
            if (eventSource > KEY_EVENT_SOURCE_SOFT_KEYBOARD) {
                codePoint = KeyCodePoints.normalizeExternalKeyboard(codePoint)
            }

            // If left alt, send escape before the code point to make e.g. Alt+B and Alt+F work in readline:
            mTermSession!!.writeCodePoint(altDown, codePoint)
        }
    }

    /** Input the specified keyCode if applicable and return if the input was consumed. */
    fun handleKeyCode(keyCode: Int, keyMod: Int): Boolean {
        // Ensure cursor is shown when a key is pressed down like long hold on (arrow) keys
        if (mEmulator != null)
            mEmulator!!.setCursorBlinkState(true)

        if (handleKeyCodeAction(keyCode, keyMod))
            return true

        val term = mTermSession!!.emulator
        val code = KeyHandler.getCode(
            keyCode, keyMod, term.isCursorKeysApplicationMode, term.isKeypadApplicationMode,
        )
        if (code == null) return false
        mTermSession!!.write(code)
        return true
    }

    fun handleKeyCodeAction(keyCode: Int, keyMod: Int): Boolean {
        val shiftDown = (keyMod and KeyHandler.KEYMOD_SHIFT) != 0

        when (keyCode) {
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN,
            ->
                // shift+page_up and shift+page_down should scroll scrollback history instead of
                // scrolling command history or changing pages
                if (shiftDown) {
                    val time = SystemClock.uptimeMillis()
                    val motionEvent = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
                    doScroll(motionEvent, if (keyCode == KeyEvent.KEYCODE_PAGE_UP) -mEmulator!!.mRows else mEmulator!!.mRows)
                    motionEvent.recycle()
                    return true
                }
        }

        return false
    }

    /**
     * Called when a key is released in the view.
     *
     * @param keyCode The keycode of the key which was released.
     * @param event   A [KeyEvent] describing the event.
     * @return Whether the event was handled.
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
            mClient.logInfo(LOG_TAG, "onKeyUp(keyCode=" + keyCode + ", event=" + event + ")")

        // Do not return for KEYCODE_BACK and send it to the client since user may be trying
        // to exit the activity.
        if (mEmulator == null && keyCode != KeyEvent.KEYCODE_BACK) return true

        if (mClient.onKeyUp(keyCode, event)) {
            invalidate()
            return true
        } else if (event.isSystem) {
            // Let system key events through.
            return super.onKeyUp(keyCode, event)
        }

        return true
    }

    /**
     * This is called during layout when the size of this view has changed. If you were just added to the view
     * hierarchy, you're called with the old values of 0.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        updateSize()
    }

    /** Check if the terminal size in rows and columns should be updated. */
    fun updateSize() {
        val viewWidth = width
        val viewHeight = height
        if (viewWidth == 0 || viewHeight == 0 || mTermSession == null) return

        val renderer = mRenderer!!
        // Set to 80 and 24 if you want to enable vttest.
        val newColumns = Math.max(4, (viewWidth / renderer.mFontWidth).toInt())
        val newRows = Math.max(4, (viewHeight - renderer.mFontLineSpacingAndAscent) / renderer.mFontLineSpacing)

        if (mEmulator == null || (newColumns != mEmulator!!.mColumns || newRows != mEmulator!!.mRows)) {
            mTermSession!!.updateSize(newColumns, newRows, renderer.getFontWidth().toInt(), renderer.getFontLineSpacing())
            mEmulator = mTermSession!!.emulator
            resetDirtyRepaintState()
            mClient.onEmulatorSet()

            // Update mTerminalCursorBlinkerRunnable inner class mEmulator on session change
            mTerminalCursorBlinkerRunnable?.setEmulator(mEmulator)

            // Restore cached top row value if session/emulator was switched back from a
            // different session or after activity restart. The top row value also needs to be
            // maintained after opening/closing soft keyboard.
            var topRow = 0
            val emulator = mEmulator
            if (emulator != null) {
                val rowsInHistory = emulator.screen.activeTranscriptRows
                val cachedTopRow = emulator.topRow
                if (cachedTopRow >= -rowsInHistory) {
                    topRow = cachedTopRow
                }
            }
            setTopRow(topRow)

            scrollTo(0, 0)
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val emulator = mEmulator
        if (emulator == null) {
            // `.toInt()` matters: Kotlin makes an 8-digit hex literal a Long, which would select the
            // API 29 `drawColor(long)` overload and crash below it (min is 23).
            canvas.drawColor(0xFF000000.toInt())
            // The frame that was going to repaint the cursor cell never happened: do not let the flag leak
            // into the first frame after a rebind, which would skip that frame's dirty-state consumption.
            mCursorOnlyFrame = false
        } else {
            // render the terminal view and highlight any selected text
            val sel = mDefaultSelectors
            mTextSelectionCursorController?.getSelectors(sel)

            // Restrict the render to the rows the canvas clip actually covers (the dirty band), with one
            // row of slack on either side. An empty/unbounded clip falls back to the whole visible band;
            // the return value matters, because on a false return the Rect must not be trusted as current.
            val clipKnown = canvas.getClipBounds(mClipBounds) && !mClipBounds.isEmpty
            val band = RenderBand.forClip(
                mTopRow, emulator.mRows, mRenderer!!.mFontLineSpacing,
                clipKnown, mClipBounds.top, mClipBounds.bottom,
            )

            updateCursorAnimationBeforeRender()

            // The eased position is only supplied while the cursor is actually gliding: at rest the inline path
            // draws the bar/underline behind the glyphs and across the whole cluster's cells.
            val cursorEases = mCursorEasing && isCursorStyleEaseable(emulator.cursorStyle)
            mRenderer!!.render(
                emulator, canvas, mTopRow, sel[0], sel[1], sel[2], sel[3],
                band.firstRow, band.lastRowExclusive, mCursorAlpha, cursorGlowRadius(),
                if (cursorEases) mDisplayCursorRow else -1f,
                if (cursorEases) mDisplayCursorCol else -1f,
            )

            // Consume the dirty state only once the frame has been drawn: invalidates coalesce, so this
            // cannot be done when the update arrives. A cursor-only frame repaints just the cursor cell, so it
            // must leave the dirty flags for the next screen update; the change counter and the overlay
            // bookkeeping are advanced either way, or every later update would look like a full repaint.
            val screen = emulator.screen
            if (!mCursorOnlyFrame) screen.clearDirtyState()
            mLastSeenChangeCounter = screen.changeCounter
            mLastCursorRow = emulator.cursorRow
            mLastCursorCol = emulator.cursorCol
            mLastReverseVideo = emulator.isReverseVideo
            mLastCursorVisible = emulator.shouldCursorBeVisible()
            mLastCursorStyle = emulator.cursorStyle
            mLastPaletteChangeCounter = emulator.mColors.changeCounter
            mCursorOnlyFrame = false

            // render the text selection handles
            renderTextSelection()
        }
    }

    /**
     * Advances the cursor's visual state before a frame is rendered. A bar or underline cursor keeps the
     * position it was last drawn at after the cursor moves, so the animation loop can glide it to the new
     * cell; every other style (and a hidden cursor) snaps, since a block cursor's cell inversion must stay
     * on the true cell. The emulator's cursor is never moved: selection and IME keep using the true cell.
     */
    private fun updateCursorAnimationBeforeRender() {
        val emulator = mEmulator ?: return
        val cursorRow = emulator.cursorRow
        val cursorCol = emulator.cursorCol
        if (!emulator.shouldCursorBeVisible() || !isCursorStyleEaseable(emulator.cursorStyle)) {
            mCursorEasing = false
            mDisplayCursorRow = cursorRow.toFloat()
            mDisplayCursorCol = cursorCol.toFloat()
            return
        }
        if (mDisplayCursorRow < 0f || mDisplayCursorCol < 0f) {
            mDisplayCursorRow = cursorRow.toFloat()
            mDisplayCursorCol = cursorCol.toFloat()
            return
        }
        if (animationsEnabled() && (mDisplayCursorRow != cursorRow.toFloat() || mDisplayCursorCol != cursorCol.toFloat()) && !mCursorEasing) {
            mCursorEasing = true
            if (!mCursorBlinkRunning) startCursorAnimationFrames()
        }
    }

    /**
     * Whether system animations are enabled. When they are not (the "remove animations" setting is on),
     * the cursor stays steady and fully opaque and a bar or underline cursor snaps to its cell instead of
     * gliding, so no frame loop is started at all.
     */
    private fun animationsEnabled(): Boolean {
        return mAnimatorDurationScale > 0f
    }

    /** Reads the system animator duration scale; `0` means "remove animations" is on. */
    private fun readAnimatorDurationScale(): Float {
        return Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
    }

    /**
     * Reacts to the system animator scale changing while the view is attached. With motion allowed the
     * blink resumes; with it removed the cursor is stopped and left steady and fully opaque at its true
     * cell. Mirrors the Compose shell's observer so both follow the setting live, with no restart.
     */
    private fun onAnimatorScaleChanged() {
        // Refresh the scale first: a change from one non-zero scale to another (0.5x to 2x) does not
        // restart the cursor, it only changes the speed the running frame loop uses.
        mAnimatorDurationScale = readAnimatorDurationScale()
        // Re-base the running envelope before it reads the new scale, so the fade does not jump.
        mTerminalCursorBlinkerRunnable?.setBlinkScale(mAnimatorDurationScale)
        val emulator = mEmulator ?: return
        if (animationsEnabled()) {
            if (!mCursorBlinkRunning) setTerminalCursorBlinkerState(true, true)
        } else {
            stopTerminalCursorBlinker()
            emulator.setCursorBlinkingEnabled(false)
        }
    }

    /** The cursor halo radius: one eighth of the line spacing, but always at least one pixel. */
    private fun cursorGlowRadius(): Float {
        return Math.max(1f, mRenderer!!.mFontLineSpacing / 8f)
    }

    /** Starts the frame loop that drives the cursor's easing, when blink is disabled and nothing else keeps it up. */
    private fun startCursorAnimationFrames() {
        if (mChoreographer == null)
            mChoreographer = Choreographer.getInstance()
        var current = mTerminalCursorBlinkerRunnable
        if (current == null || current.isCancelled() || current.mEmulator !== mEmulator) {
            // Cancel the old runnable so a frame already executing cannot re-post itself alongside the new one.
            if (current != null) {
                current.cancel()
                mChoreographer!!.removeFrameCallback(current)
            }
            current = TerminalCursorBlinkerRunnable(mEmulator, mTerminalCursorBlinkerRate)
            mTerminalCursorBlinkerRunnable = current
        }
        mChoreographer!!.removeFrameCallback(current)
        mChoreographer!!.postFrameCallback(current)
    }

    /**
     * Forces the next draw to repaint in full. Called when the session/emulator is (re)bound, where the
     * previous change counter and the previously painted cursor belong to a different buffer.
     */
    private fun resetDirtyRepaintState() {
        mLastSeenChangeCounter = -1L
        mLastCursorRow = -1
        mLastCursorCol = -1
        mLastReverseVideo = false
        mLastCursorVisible = true
        mLastCursorStyle = -1
        mLastPaletteChangeCounter = -1
        mCursorEasing = false
        mDisplayCursorRow = -1f
        mDisplayCursorCol = -1f
        mCursorOnlyFrame = false
    }

    fun getCurrentSession(): TerminalSession? {
        return mTermSession
    }

    private fun getText(): CharSequence? {
        val emulator = mEmulator ?: return null
        return emulator.screen.getSelectedText(0, mTopRow, emulator.mColumns, mTopRow + emulator.mRows)
    }

    fun getCursorX(x: Float): Int {
        return TerminalGeometry.cursorColumn(x, mRenderer!!.mFontWidth)
    }

    fun getCursorY(y: Float): Int {
        return TerminalGeometry.cursorRow(y, mRenderer!!.mFontLineSpacing.toFloat(), mTopRow)
    }

    fun getPointX(cx: Int): Int {
        return TerminalGeometry.pointX(cx, mEmulator!!.mColumns, mRenderer!!.mFontWidth)
    }

    fun getPointY(cy: Int): Int {
        return TerminalGeometry.pointY(cy, mTopRow, mRenderer!!.mFontLineSpacing.toFloat())
    }

    fun getTopRow(): Int {
        return mTopRow
    }

    fun setTopRow(topRow: Int) {
        setTopRow(topRow, true)
    }

    fun setTopRow(topRow: Int, updateEmulator: Boolean) {
        mTopRow = topRow
        if (updateEmulator && mEmulator != null) {
            mEmulator!!.setTopRow(mTopRow)
        }
    }

    /**
     * Define functions required for AutoFill API
     */
    @RequiresApi(Build.VERSION_CODES.O)
    override fun autofill(value: AutofillValue) {
        if (value.isText) {
            mTermSession!!.write(value.textValue.toString())
        }

        resetAutoFill()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun getAutofillType(): Int {
        return mAutoFillType
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun getAutofillHints(): Array<String> {
        return mAutoFillHints
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun getAutofillValue(): AutofillValue {
        return AutofillValue.forText("")
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun getImportantForAutofill(): Int {
        return mAutoFillImportance
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @Synchronized
    private fun resetAutoFill() {
        // Restore none type so that AutoFill UI isn't shown anymore.
        mAutoFillType = View.AUTOFILL_TYPE_NONE
        mAutoFillImportance = View.IMPORTANT_FOR_AUTOFILL_NO
        mAutoFillHints = emptyArray()
    }

    fun getAutoFillManagerService(): AutofillManager? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null

        return try {
            val context = context ?: return null
            context.getSystemService(AutofillManager::class.java)
        } catch (e: Exception) {
            mClient.logStackTraceWithMessage(LOG_TAG, "Failed to get AutofillManager service", e)
            null
        }
    }

    fun isAutoFillEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false

        return try {
            val autofillManager = getAutoFillManagerService()
            autofillManager != null && autofillManager.isEnabled
        } catch (e: Exception) {
            mClient.logStackTraceWithMessage(LOG_TAG, "Failed to check if Autofill is enabled", e)
            false
        }
    }

    @Synchronized
    fun requestAutoFillUsername() {
        requestAutoFill(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) arrayOf(View.AUTOFILL_HINT_USERNAME) else null,
        )
    }

    @Synchronized
    fun requestAutoFillPassword() {
        requestAutoFill(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) arrayOf(View.AUTOFILL_HINT_PASSWORD) else null,
        )
    }

    @Synchronized
    fun requestAutoFill(autoFillHints: Array<String>?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (autoFillHints == null || autoFillHints.size < 1) return

        try {
            val autofillManager = getAutoFillManagerService()
            if (autofillManager != null && autofillManager.isEnabled) {
                // Update type that will be returned by `getAutofillType()` so that AutoFill UI is shown.
                mAutoFillType = View.AUTOFILL_TYPE_TEXT
                // Update importance that will be returned by `getImportantForAutofill()` so that
                // AutoFill considers the view as important.
                mAutoFillImportance = View.IMPORTANT_FOR_AUTOFILL_YES
                // Update hints that will be returned by `getAutofillHints()` for which to show AutoFill UI.
                mAutoFillHints = autoFillHints
                autofillManager.requestAutofill(this)
            }
        } catch (e: Exception) {
            mClient.logStackTraceWithMessage(LOG_TAG, "Failed to request Autofill", e)
        }
    }

    @Synchronized
    fun cancelRequestAutoFill() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (mAutoFillType == View.AUTOFILL_TYPE_NONE) return

        try {
            val autofillManager = getAutoFillManagerService()
            if (autofillManager != null && autofillManager.isEnabled) {
                resetAutoFill()
                autofillManager.cancel()
            }
        } catch (e: Exception) {
            mClient.logStackTraceWithMessage(LOG_TAG, "Failed to cancel Autofill request", e)
        }
    }

    /**
     * Set terminal cursor blinker rate. It must be between [TERMINAL_CURSOR_BLINK_RATE_MIN]
     * and [TERMINAL_CURSOR_BLINK_RATE_MAX], otherwise it will be disabled.
     *
     * The [setTerminalCursorBlinkerState] must be called after this
     * for changes to take effect if not disabling.
     *
     * @param blinkRate The value to set.
     * @return Returns `true` if setting blinker rate was successfully set, otherwise [@code false}.
     */
    @Synchronized
    fun setTerminalCursorBlinkerRate(blinkRate: Int): Boolean {
        val result: Boolean

        // If cursor blinking rate is not valid
        if (blinkRate != 0 && (blinkRate < TERMINAL_CURSOR_BLINK_RATE_MIN || blinkRate > TERMINAL_CURSOR_BLINK_RATE_MAX)) {
            mClient.logError(LOG_TAG, "The cursor blink rate must be in between " + TERMINAL_CURSOR_BLINK_RATE_MIN + "-" + TERMINAL_CURSOR_BLINK_RATE_MAX + ": " + blinkRate)
            mTerminalCursorBlinkerRate = 0
            result = false
        } else {
            mClient.logVerbose(LOG_TAG, "Setting cursor blinker rate to " + blinkRate)
            mTerminalCursorBlinkerRate = blinkRate
            result = true
        }

        if (mTerminalCursorBlinkerRate == 0) {
            mClient.logVerbose(LOG_TAG, "Cursor blinker disabled")
            stopTerminalCursorBlinker()
        }

        return result
    }

    /**
     * Sets whether cursor blinker should be started or stopped. Cursor blinker will only be
     * started if [mTerminalCursorBlinkerRate] does not equal 0 and is between
     * [TERMINAL_CURSOR_BLINK_RATE_MIN] and [TERMINAL_CURSOR_BLINK_RATE_MAX].
     *
     * This should be called when the view holding this activity is resumed or stopped so that
     * cursor blinker does not run when activity is not visible. If you call this on onResume()
     * to start cursor blinking, then ensure that [mEmulator] is set, otherwise wait for the
     * [TerminalViewClient.onEmulatorSet] event after calling [attachSession]
     * for the first session added in the activity since blinking will not start if [mEmulator]
     * is not set, like if activity is started again after exiting it with double back press. Do not
     * call this directly after [attachSession] since [updateSize]
     * may return without setting [mEmulator] since width/height may be 0. Its called again in
     * [onSizeChanged]. Calling on onResume() if emulator is already set
     * is necessary, since onEmulatorSet() may not be called after activity is started after device
     * display timeout with double tap and not power button.
     *
     * It should also be called on the
     * [com.termux.terminal.TerminalSessionClient.onTerminalCursorStateChange]
     * callback when cursor is enabled or disabled so that blinker is disabled if cursor is not
     * to be shown. It should also be checked if activity is visible if blinker is to be started
     * before calling this.
     *
     * It should also be called after terminal is reset with [TerminalSession.reset] in case
     * cursor blinker was disabled before reset due to call to
     * [com.termux.terminal.TerminalSessionClient.onTerminalCursorStateChange].
     *
     * How cursor blinker starting works is by registering a [Runnable] with the looper of
     * the main thread of the app which when run, toggles the cursor blinking state and re-registers
     * itself to be called with the delay set by [mTerminalCursorBlinkerRate]. When cursor
     * blinking needs to be disabled, we just cancel any callbacks registered. We don't run our own
     * "thread" and let the thread for the main looper do the work for us, whose usage is also
     * required to update the UI, since it also handles other calls to update the UI as well based
     * on a queue.
     *
     * Note that when moving cursor in text editors like nano, the cursor state is quickly
     * toggled `-> off -> on`, which would call this very quickly sequentially. So that if cursor
     * is moved 2 or more times quickly, like long hold on arrow keys, it would trigger
     * `-> off -> on -> off -> on -> ...`, and the "on" callback at index 2 is automatically
     * cancelled by next "off" callback at index 3 before getting a chance to be run. For this case
     * we log only if [TERMINAL_VIEW_KEY_LOGGING_ENABLED] is enabled, otherwise would clutter
     * the log. We don't start the blinking with a delay to immediately show cursor in case it was
     * previously not visible.
     *
     * @param start If cursor blinker should be started or stopped.
     * @param startOnlyIfCursorEnabled If set to `true`, then it will also be checked if the
     *                                 cursor is even enabled by [TerminalEmulator] before
     *                                 starting the cursor blinker.
     */
    @Synchronized
    fun setTerminalCursorBlinkerState(start: Boolean, startOnlyIfCursorEnabled: Boolean) {
        // Stop any existing cursor blinker callbacks
        stopTerminalCursorBlinker()

        val emulator = mEmulator ?: return

        emulator.setCursorBlinkingEnabled(false)
        mCursorBlinkRunning = false

        if (start) {
            // "Remove animations" is on: keep the cursor steady and fully opaque instead of fading it,
            // and never start the frame loop.
            if (!animationsEnabled()) return
            // If cursor blinker is not enabled or is not valid
            if (mTerminalCursorBlinkerRate < TERMINAL_CURSOR_BLINK_RATE_MIN || mTerminalCursorBlinkerRate > TERMINAL_CURSOR_BLINK_RATE_MAX)
                return
            // If cursor blinder is to be started only if cursor is enabled
            else if (startOnlyIfCursorEnabled && !emulator.isCursorEnabled) {
                if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
                    mClient.logVerbose(LOG_TAG, "Ignoring call to start cursor blinker since cursor is not enabled")
                return
            }

            // Start cursor blinker runnable
            if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
                mClient.logVerbose(LOG_TAG, "Starting cursor blinker with the blink rate " + mTerminalCursorBlinkerRate)
            if (mChoreographer == null)
                mChoreographer = Choreographer.getInstance()
            mTerminalCursorBlinkerRunnable = TerminalCursorBlinkerRunnable(mEmulator, mTerminalCursorBlinkerRate)
            emulator.setCursorBlinkingEnabled(true)
            // The fade replaces the hard on/off toggle: the blink state stays visible and the envelope owns the alpha.
            emulator.setCursorBlinkState(true)
            mCursorAlpha = 1f
            mCursorBlinkRunning = true
            // Run on the main thread like every later frame, so the animation never touches the view off it.
            mChoreographer!!.postFrameCallback(mTerminalCursorBlinkerRunnable)
        }
    }

    /**
     * Cancel the terminal cursor blinker callbacks
     */
    private fun stopTerminalCursorBlinker() {
        if (mChoreographer != null && mTerminalCursorBlinkerRunnable != null) {
            if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
                mClient.logVerbose(LOG_TAG, "Stopping cursor blinker")
            // Everything here runs on the UI thread (the emulator is fed by TerminalSession's main-thread
            // handler), so removeFrameCallback alone would suffice today. The flag is kept as defence for a
            // future off-thread caller: a frame already executing then declines to re-post itself.
            mTerminalCursorBlinkerRunnable!!.cancel()
            mChoreographer!!.removeFrameCallback(mTerminalCursorBlinkerRunnable)
        }
        // A faded or gliding cursor must be redrawn opaque at its true cell when the animation stops.
        val wasAnimating = mCursorAlpha != 1f || mCursorEasing
        mCursorBlinkRunning = false
        mCursorAlpha = 1f
        // Drop any glide in progress so the next draw snaps to the true cell instead of resuming mid-way.
        mCursorEasing = false
        mDisplayCursorRow = -1f
        mDisplayCursorCol = -1f
        if (wasAnimating) postInvalidate()
    }

    /**
     * Invalidates only the pixels the cursor occupies — its previous and its current cell, plus the halo — so a
     * fade or a glide does not repaint the whole view on every frame. The requested frame is flagged as
     * cursor-only, so [onDraw] leaves the dirty state for the next screen update.
     */
    private fun invalidateCursor(oldRow: Float, oldCol: Float, newRow: Float, newCol: Float) {
        if (mRenderer == null) {
            postInvalidate()
            return
        }
        val glow = cursorGlowRadius()
        val left = Math.min(oldCol, newCol) * mRenderer!!.mFontWidth - glow
        val right = (Math.max(oldCol, newCol) + 1f) * mRenderer!!.mFontWidth + glow
        val top = mRenderer!!.mFontLineSpacingAndAscent +
            (Math.min(oldRow, newRow) - mTopRow) * mRenderer!!.mFontLineSpacing - glow
        val bottom = mRenderer!!.mFontLineSpacingAndAscent +
            (Math.max(oldRow, newRow) - mTopRow + 1f) * mRenderer!!.mFontLineSpacing + glow
        mCursorOnlyFrame = true
        // Only reached from the animation runnable, which always runs on the main thread.
        invalidate(
            Math.floor(left.toDouble()).toInt(), Math.floor(top.toDouble()).toInt(),
            Math.ceil(right.toDouble()).toInt(), Math.ceil(bottom.toDouble()).toInt(),
        )
    }

    private inner class TerminalCursorBlinkerRunnable(emulator: TerminalEmulator?, private val mBlinkRate: Int) :
        Choreographer.FrameCallback {

        @Volatile
        var mEmulator: TerminalEmulator? = emulator

        /** Start of the blink envelope, so the cursor is fully visible on the first frame after starting. */
        private var mBlinkStartUptimeMs = SystemClock.uptimeMillis()

        /** The animator duration scale this runnable times its blink and glide with. */
        private var mBlinkScale = mAnimatorDurationScale

        /** Frame time of the previous callback, in nanos; negative until the first frame so its delta is 0. */
        private var mLastFrameNanos = -1L

        /**
         * Set by [stopTerminalCursorBlinker]. The view and this runnable both run on the UI thread
         * today, so this is defence for a future off-thread caller: a frame that is already executing then
         * declines to draw again or re-post itself.
         */
        @Volatile
        private var mCancelled = false

        fun setEmulator(emulator: TerminalEmulator?) {
            this.mEmulator = emulator
        }

        /** Stops this runnable from drawing again or re-posting itself. */
        fun cancel() {
            mCancelled = true
        }

        /** Whether [cancel] was called; a cancelled runnable is never posted again. */
        fun isCancelled(): Boolean {
            return mCancelled
        }

        /**
         * Takes a new animator duration scale, keeping the cursor at the same point of the fade: the
         * envelope start moves so the elapsed phase keeps its ratio to the new period. Without this a
         * scale change mid-blink jumps the alpha to whatever the old start time says in the new period.
         */
        fun setBlinkScale(scale: Float) {
            val now = SystemClock.uptimeMillis()
            val oldPeriod = (2.0 * mBlinkRate * mBlinkScale).toLong().coerceAtLeast(1L)
            val newPeriod = (2.0 * mBlinkRate * scale).toLong().coerceAtLeast(1L)
            val phase = ((now - mBlinkStartUptimeMs) % oldPeriod + oldPeriod) % oldPeriod
            mBlinkStartUptimeMs = now - (phase.toFloat() / oldPeriod * newPeriod).toLong()
            mBlinkScale = scale
        }

        override fun doFrame(frameTimeNanos: Long) {
            try {
                if (mCancelled) return
                // Elapsed time since the previous frame, so the glide speed does not depend on the frame
                // cadence. The first frame has no previous time and holds still.
                var dtMs = 0f
                if (mLastFrameNanos >= 0L) dtMs = (frameTimeNanos - mLastFrameNanos) / 1_000_000f
                mLastFrameNanos = frameTimeNanos
                val emulator = mEmulator
                if (emulator != null) {
                    // Fade the cursor instead of toggling it on and off, and glide a bar or underline cursor to
                    // the cell it just moved to. Either effect invalidates the cursor's own pixels so this
                    // callback paints the next frame, which is what keeps the animation running.
                    val oldRow = if (mDisplayCursorRow >= 0f) mDisplayCursorRow else emulator.cursorRow.toFloat()
                    val oldCol = if (mDisplayCursorCol >= 0f) mDisplayCursorCol else emulator.cursorCol.toFloat()
                    // Both effects follow the system animator duration scale: 0.5x halves their
                    // duration, 2x doubles it, matching the Compose chrome.
                    val durationScale = mBlinkScale
                    val blinkPeriodMs = (2.0 * mBlinkRate * durationScale).toLong().coerceAtLeast(1L)
                    mCursorAlpha =
                        if (mCursorBlinkRunning) {
                            CursorFx.blinkAlpha(SystemClock.uptimeMillis() - mBlinkStartUptimeMs, blinkPeriodMs)
                        } else {
                            1f
                        }
                    if (mCursorEasing) {
                        val targetRow = emulator.cursorRow.toFloat()
                        val targetCol = emulator.cursorCol.toFloat()
                        val halfLifeMs = mCursorEasingHalfLifeMs * durationScale
                        mDisplayCursorRow = CursorFx.easeTowards(mDisplayCursorRow, targetRow, dtMs, halfLifeMs)
                        mDisplayCursorCol = CursorFx.easeTowards(mDisplayCursorCol, targetCol, dtMs, halfLifeMs)
                        if (Math.abs(mDisplayCursorRow - targetRow) < 0.05f) mDisplayCursorRow = targetRow
                        if (Math.abs(mDisplayCursorCol - targetCol) < 0.05f) mDisplayCursorCol = targetCol
                        if (mDisplayCursorRow == targetRow && mDisplayCursorCol == targetCol) mCursorEasing = false
                    }
                    invalidateCursor(
                        oldRow, oldCol,
                        if (mDisplayCursorRow >= 0f) mDisplayCursorRow else emulator.cursorRow.toFloat(),
                        if (mDisplayCursorCol >= 0f) mDisplayCursorCol else emulator.cursorCol.toFloat(),
                    )
                }
            } finally {
                // Keep the loop alive only while an effect is actually running and this callback was not cancelled.
                if (!mCancelled && (mCursorBlinkRunning || mCursorEasing) && mChoreographer != null)
                    mChoreographer!!.postFrameCallback(this)
            }
        }
    }

    /**
     * Define functions required for text selection and its handles.
     */
    fun getTextSelectionCursorController(): TextSelectionCursorController {
        var controller = mTextSelectionCursorController
        if (controller == null) {
            controller = TextSelectionCursorController(this)
            mTextSelectionCursorController = controller

            val observer: ViewTreeObserver? = viewTreeObserver
            if (observer != null) {
                observer.addOnTouchModeChangeListener(controller)
            }
        }

        return controller
    }

    private fun showTextSelectionCursors(event: MotionEvent) {
        getTextSelectionCursorController().show(event)
    }

    private fun hideTextSelectionCursors(): Boolean {
        return getTextSelectionCursorController().hide()
    }

    private fun renderTextSelection() {
        mTextSelectionCursorController?.render()
    }

    fun isSelectingText(): Boolean {
        return mTextSelectionCursorController?.isActive() ?: false
    }

    /** Get the currently selected text if selecting. */
    fun getSelectedText(): String? {
        return if (isSelectingText() && mTextSelectionCursorController != null) {
            mTextSelectionCursorController!!.selectedText
        } else {
            null
        }
    }

    /** Get the selected text stored before "MORE" button was pressed on the context menu. */
    fun getStoredSelectedText(): String? {
        return mTextSelectionCursorController?.storedSelectedText
    }

    /** Unset the selected text stored before "MORE" button was pressed on the context menu. */
    fun unsetStoredSelectedText() {
        mTextSelectionCursorController?.unsetStoredSelectedText()
    }

    private fun getTextSelectionActionMode(): ActionMode? {
        return mTextSelectionCursorController?.actionMode
    }

    fun startTextSelectionMode(event: MotionEvent) {
        if (!requestFocus()) {
            return
        }

        showTextSelectionCursors(event)
        mClient.copyModeChanged(isSelectingText())

        invalidate()
    }

    fun stopTextSelectionMode() {
        if (hideTextSelectionCursors()) {
            mClient.copyModeChanged(isSelectingText())
            invalidate()
        }
    }

    private fun decrementYTextSelectionCursors(decrement: Int) {
        mTextSelectionCursorController?.decrementYTextSelectionCursors(decrement)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        if (mTextSelectionCursorController != null) {
            viewTreeObserver.addOnTouchModeChangeListener(mTextSelectionCursorController)
        }

        // Follow the "remove animations" setting while the view is on screen: toggling it stops or
        // resumes the cursor without the activity being recreated. Re-read the current scale now,
        // since the observer only reports later changes.
        mAnimatorDurationScale = readAnimatorDurationScale()
        if (mAnimatorScaleObserver == null) {
            mAnimatorScaleObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    onAnimatorScaleChanged()
                }
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            mAnimatorScaleObserver!!,
        )
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()

        // A detached view still gets its frame callbacks, so stop the loop rather than fade a cursor
        // that nobody draws.
        stopTerminalCursorBlinker()

        if (mAnimatorScaleObserver != null) {
            context.contentResolver.unregisterContentObserver(mAnimatorScaleObserver!!)
        }

        if (mTextSelectionCursorController != null) {
            // Might solve the following exception
            // android.view.WindowLeaked: Activity com.termux.app.TermuxActivity has leaked window android.widget.PopupWindow
            stopTextSelectionMode()

            viewTreeObserver.removeOnTouchModeChangeListener(mTextSelectionCursorController)
            mTextSelectionCursorController!!.onDetached()
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        mEmulator?.sendFocusEvent(hasWindowFocus)
    }

    /**
     * Define functions required for long hold toolbar.
     */
    private val mShowFloatingToolbar: Runnable = Runnable {
        getTextSelectionActionMode()?.hide(0) // hide off.
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun showFloatingToolbar() {
        if (getTextSelectionActionMode() != null) {
            val delay = ViewConfiguration.getDoubleTapTimeout()
            postDelayed(mShowFloatingToolbar, delay.toLong())
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    fun hideFloatingToolbar() {
        if (getTextSelectionActionMode() != null) {
            removeCallbacks(mShowFloatingToolbar)
            getTextSelectionActionMode()!!.hide(-1)
        }
    }

    fun updateFloatingToolbarVisibility(event: MotionEvent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && getTextSelectionActionMode() != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> hideFloatingToolbar()

                MotionEvent.ACTION_UP, // fall through
                MotionEvent.ACTION_CANCEL,
                -> showFloatingToolbar()
            }
        }
    }

    companion object {

        /** Log terminal view key and IME events. */
        private var TERMINAL_VIEW_KEY_LOGGING_ENABLED = false

        const val TERMINAL_CURSOR_BLINK_RATE_MIN = 100
        const val TERMINAL_CURSOR_BLINK_RATE_MAX = 2000

        /**
         * The [KeyEvent] is generated from a virtual keyboard, like manually with the [KeyEvent.KeyEvent] constructor.
         */
        @JvmField
        val KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD = KeyCharacterMap.VIRTUAL_KEYBOARD // -1

        /**
         * The [KeyEvent] is generated from a non-physical device, like if 0 value is returned by [KeyEvent.getDeviceId].
         */
        const val KEY_EVENT_SOURCE_SOFT_KEYBOARD = 0

        private const val LOG_TAG = "TerminalView"

        /**
         * @return the sanitized link URI at the given screen cell, or `null` when there is no
         * dispatchable link there (no link, out of bounds, or disallowed scheme). The row is relative to
         * the scroll position, as [getColumnAndRow] produces it, so rows above
         * the visible screen resolve against scrollback. Pure, so the hit-test can be unit-tested without a
         * [MotionEvent].
         */
        @JvmStatic
        fun getLinkUriAt(emulator: TerminalEmulator, row: Int, column: Int): String? {
            val screen: TerminalBuffer = emulator.screen
            val oldestRow = -screen.activeTranscriptRows
            if (row < oldestRow || row >= emulator.mRows || column < 0 || column >= emulator.mColumns) return null
            val linkId = screen.getLinkIdAt(row, column)
            if (linkId == 0) return null
            val uri = screen.getLink(linkId)
            val sanitized = LinkUris.sanitize(uri)
            if (sanitized == null) {
                // Only a URI the registry actually holds can be rejected by the allow-list; an unknown id
                // (already garbage collected) is simply not a link any more.
                if (uri != null) Log.w(LOG_TAG, "Dropped OSC 8 hyperlink with disallowed scheme: $uri")
                return null
            }
            return sanitized
        }

        /** Whether the cursor style is drawn at an eased position; a block cursor must stay on its cell. */
        private fun isCursorStyleEaseable(cursorStyle: Int): Boolean {
            return cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR ||
                cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE
        }
    }
}
