package com.termux.view.textselection

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import com.termux.terminal.GraphemeClusters
import com.termux.terminal.TerminalBuffer
import com.termux.view.R
import com.termux.view.TerminalView

class TextSelectionCursorController(private val terminalView: TerminalView) : CursorController {

    private val mStartHandle: TextSelectionHandleView
    private val mEndHandle: TextSelectionHandleView
    private var mStoredSelectedText: String? = null
    private var mIsSelectingText = false
    private var mShowStartTime = System.currentTimeMillis()

    private val mHandleHeight: Int
    private var mSelX1 = -1
    private var mSelX2 = -1
    private var mSelY1 = -1
    private var mSelY2 = -1

    private var mActionMode: ActionMode? = null

    @JvmField
    val ACTION_COPY = 1

    @JvmField
    val ACTION_PASTE = 2

    @JvmField
    val ACTION_MORE = 3

    init {
        mStartHandle = TextSelectionHandleView(terminalView, this, TextSelectionHandleView.LEFT)
        mEndHandle = TextSelectionHandleView(terminalView, this, TextSelectionHandleView.RIGHT)

        mHandleHeight = Math.max(mStartHandle.getHandleHeight(), mEndHandle.getHandleHeight())
    }

    /** Recolours both selection handles; 0 restores their built-in colour. */
    fun setHandleTint(tint: Int) {
        mStartHandle.setHandleTint(tint)
        mEndHandle.setHandleTint(tint)
    }

    override fun show(event: MotionEvent) {
        setInitialTextSelectionPosition(event)
        mStartHandle.positionAtCursor(mSelX1, mSelY1, true)
        mEndHandle.positionAtCursor(mSelX2 + 1, mSelY2, true)

        setActionModeCallBacks()
        mShowStartTime = System.currentTimeMillis()
        mIsSelectingText = true
    }

    override fun hide(): Boolean {
        if (!isActive()) return false

        // prevent hide calls right after a show call, like long pressing the down key
        // 300ms seems long enough that it wouldn't cause hide problems if action button
        // is quickly clicked after the show, otherwise decrease it
        if (System.currentTimeMillis() - mShowStartTime < 300) {
            return false
        }

        mStartHandle.hide()
        mEndHandle.hide()

        // This will hide the TextSelectionCursorController
        mActionMode?.finish()

        mSelX1 = -1
        mSelY1 = -1
        mSelX2 = -1
        mSelY2 = -1
        mIsSelectingText = false

        return true
    }

    override fun render() {
        if (!isActive()) return

        mStartHandle.positionAtCursor(mSelX1, mSelY1, false)
        mEndHandle.positionAtCursor(mSelX2 + 1, mSelY2, false)

        mActionMode?.invalidate()
    }

    fun setInitialTextSelectionPosition(event: MotionEvent) {
        val columnAndRow = terminalView.getColumnAndRow(event, true)
        mSelX1 = columnAndRow[0]
        mSelX2 = columnAndRow[0]
        mSelY1 = columnAndRow[1]
        mSelY2 = columnAndRow[1]

        val screen = terminalView.mEmulator!!.screen
        if (" " != screen.getSelectedText(mSelX1, mSelY1, mSelX1, mSelY1)) {
            // Selecting something other than whitespace. Expand to word.
            while (mSelX1 > 0 && "" != screen.getSelectedText(mSelX1 - 1, mSelY1, mSelX1 - 1, mSelY1)) {
                mSelX1--
            }
            while (mSelX2 < terminalView.mEmulator!!.mColumns - 1 && "" != screen.getSelectedText(mSelX2 + 1, mSelY1, mSelX2 + 1, mSelY1)) {
                mSelX2++
            }
        }
    }

    fun setActionModeCallBacks() {
        val callback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                val show = MenuItem.SHOW_AS_ACTION_IF_ROOM or MenuItem.SHOW_AS_ACTION_WITH_TEXT

                val clipboard = terminalView.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
                menu.add(Menu.NONE, ACTION_COPY, Menu.NONE, R.string.copy_text).setShowAsAction(show)
                menu.add(Menu.NONE, ACTION_PASTE, Menu.NONE, R.string.paste_text)
                    .setEnabled(clipboard != null && clipboard.hasPrimaryClip()).setShowAsAction(show)
                menu.add(Menu.NONE, ACTION_MORE, Menu.NONE, R.string.text_selection_more)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                return false
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (!isActive()) {
                    // Fix issue where the dialog is pressed while being dismissed.
                    return true
                }

                when (item.itemId) {
                    ACTION_COPY -> {
                        val selectedText = selectedText
                        terminalView.mTermSession!!.onCopyTextToClipboard(selectedText)
                        terminalView.stopTextSelectionMode()
                    }

                    ACTION_PASTE -> {
                        terminalView.stopTextSelectionMode()
                        terminalView.mTermSession!!.onPasteTextFromClipboard()
                    }

                    ACTION_MORE -> {
                        // We first store the selected text in case TerminalViewClient needs the
                        // selected text before MORE button was pressed since we are going to
                        // stop selection mode
                        mStoredSelectedText = selectedText
                        // The text selection needs to be stopped before showing context menu,
                        // otherwise handles will show above popup
                        terminalView.stopTextSelectionMode()
                        terminalView.showContextMenu()
                    }
                }

                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) {
            }
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            mActionMode = terminalView.startActionMode(callback)
            return
        }

        //noinspection NewApi
        mActionMode = terminalView.startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                return callback.onCreateActionMode(mode, menu)
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                return false
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                return callback.onActionItemClicked(mode, item)
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                // Ignore.
            }

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                var x1 = Math.round(mSelX1 * terminalView.mRenderer!!.getFontWidth())
                val x2 = Math.round(mSelX2 * terminalView.mRenderer!!.getFontWidth())
                val y1 = (mSelY1 - 1 - terminalView.getTopRow()) * terminalView.mRenderer!!.getFontLineSpacing()
                val y2 = (mSelY2 + 1 - terminalView.getTopRow()) * terminalView.mRenderer!!.getFontLineSpacing()

                if (x1 > x2) {
                    x1 = x2
                }

                val terminalBottom = terminalView.bottom
                var top = y1 + mHandleHeight
                var bottom = y2 + mHandleHeight
                if (top > terminalBottom) top = terminalBottom
                if (bottom > terminalBottom) bottom = terminalBottom

                outRect.set(x1, top, x2, bottom)
            }
        }, ActionMode.TYPE_FLOATING)
    }

    override fun updatePosition(handle: TextSelectionHandleView, x: Int, y: Int) {
        val emulator = terminalView.mEmulator!!
        val screen = emulator.screen
        val scrollRows = screen.activeRows - emulator.mRows
        if (handle === mStartHandle) {
            mSelX1 = terminalView.getCursorX(x.toFloat())
            mSelY1 = terminalView.getCursorY(y.toFloat())
            if (mSelX1 < 0) {
                mSelX1 = 0
            }

            if (mSelY1 < -scrollRows) {
                mSelY1 = -scrollRows
            } else if (mSelY1 > emulator.mRows - 1) {
                mSelY1 = emulator.mRows - 1
            }

            if (mSelY1 > mSelY2) {
                mSelY1 = mSelY2
            }
            if (mSelY1 == mSelY2 && mSelX1 > mSelX2) {
                mSelX1 = mSelX2
            }

            if (!emulator.isAlternateBufferActive) {
                var topRow = terminalView.getTopRow()

                if (mSelY1 <= topRow) {
                    topRow--
                    if (topRow < -scrollRows) {
                        topRow = -scrollRows
                    }
                } else if (mSelY1 >= topRow + emulator.mRows) {
                    topRow++
                    if (topRow > 0) {
                        topRow = 0
                    }
                }

                terminalView.setTopRow(topRow)
            }

            mSelX1 = getValidCurX(screen, mSelY1, mSelX1)
        } else {
            mSelX2 = terminalView.getCursorX(x.toFloat())
            mSelY2 = terminalView.getCursorY(y.toFloat())
            if (mSelX2 < 0) {
                mSelX2 = 0
            }

            if (mSelY2 < -scrollRows) {
                mSelY2 = -scrollRows
            } else if (mSelY2 > emulator.mRows - 1) {
                mSelY2 = emulator.mRows - 1
            }

            if (mSelY1 > mSelY2) {
                mSelY2 = mSelY1
            }
            if (mSelY1 == mSelY2 && mSelX1 > mSelX2) {
                mSelX2 = mSelX1
            }

            if (!emulator.isAlternateBufferActive) {
                var topRow = terminalView.getTopRow()

                if (mSelY2 <= topRow) {
                    topRow--
                    if (topRow < -scrollRows) {
                        topRow = -scrollRows
                    }
                } else if (mSelY2 >= topRow + emulator.mRows) {
                    topRow++
                    if (topRow > 0) {
                        topRow = 0
                    }
                }

                terminalView.setTopRow(topRow)
            }

            mSelX2 = getValidCurX(screen, mSelY2, mSelX2)
        }

        terminalView.invalidate()
    }

    private fun getValidCurX(screen: TerminalBuffer, cy: Int, cx: Int): Int {
        return validCursorColumn(screen.getSelectedText(0, cy, cx, cy), cx)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return false
    }

    override fun onTouchModeChanged(isInTouchMode: Boolean) {
        if (!isInTouchMode) {
            terminalView.stopTextSelectionMode()
        }
    }

    override fun onDetached() {
    }

    override fun isActive(): Boolean {
        return mIsSelectingText
    }

    fun decrementYTextSelectionCursors(decrement: Int) {
        mSelY1 -= decrement
        mSelY2 -= decrement
    }

    fun getSelectors(sel: IntArray?) {
        if (sel == null || sel.size != 4) {
            return
        }

        sel[0] = mSelY1
        sel[1] = mSelY2
        sel[2] = mSelX1
        sel[3] = mSelX2
    }

    /** Get the currently selected text. */
    val selectedText: String
        get() = terminalView.mEmulator!!.getSelectedText(mSelX1, mSelY1, mSelX2, mSelY2)

    /** Get the selected text stored before "MORE" button was pressed on the context menu. */
    val storedSelectedText: String?
        get() = mStoredSelectedText

    /** Unset the selected text stored before "MORE" button was pressed on the context menu. */
    fun unsetStoredSelectedText() {
        mStoredSelectedText = null
    }

    val actionMode: ActionMode?
        get() = mActionMode

    /**
     * @return true if this controller is currently used to move the start selection.
     */
    val isSelectionStartDragged: Boolean
        get() = mStartHandle.isDragging()

    /**
     * @return true if this controller is currently used to move the end selection.
     */
    val isSelectionEndDragged: Boolean
        get() = mEndHandle.isDragging()

    companion object {
        /**
         * Maps a requested selection column to a cell boundary: a column inside a cell snaps to the end
         * of that cell, so a selection handle never sits in the middle of a wide glyph.
         *
         * The walk is over grapheme clusters, not code points, because a row stores a whole cluster in
         * the cell of its base code point. A ZWJ family, a flag and a skin toned emoji are one cell each,
         * so their continuation code points must not stop the walk: only the base code point's width
         * advances the column.
         *
         * @param line the row's text from column 0 to the requested column
         * @param cx the requested column
         * @return the column the selection handle should use
         */
        @JvmStatic
        fun validCursorColumn(line: String?, cx: Int): Int {
            if (line == null || line.isEmpty()) return cx

            val text = line.toCharArray()
            var spaceUsed = text.size
            for (i in text.indices) {
                if (text[i] == '\u0000') {
                    spaceUsed = i
                    break
                }
            }

            var col = 0
            var i = 0
            while (i < spaceUsed) {
                val cend = col + GraphemeClusters.clusterWidth(text, i, spaceUsed)
                if (cx > col && cx < cend) {
                    return cend
                }
                if (cend > col) {
                    col = cend
                }
                val next = GraphemeClusters.clusterEnd(text, i, spaceUsed)
                if (next <= i) break
                i = next
            }
            return cx
        }
    }
}
