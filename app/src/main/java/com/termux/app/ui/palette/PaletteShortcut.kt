package com.termux.app.ui.palette

import android.view.KeyEvent

/**
 * Whether [keyCode] with the given modifier state is the command palette shortcut.
 *
 * The shortcut is Ctrl+Shift+K. Plain Ctrl+K and Ctrl+Alt+K stay with the shell: Ctrl+K is
 * readline's kill-line and Ctrl+Alt+K reaches readline as `ESC Ctrl-K`, also kill-line. Ctrl+Shift+K
 * is not encodable as terminal input, so nothing the shell can use is taken.
 */
fun isPaletteShortcut(keyCode: Int, ctrl: Boolean, shift: Boolean, alt: Boolean): Boolean =
    keyCode == KeyEvent.KEYCODE_K && ctrl && shift && !alt
