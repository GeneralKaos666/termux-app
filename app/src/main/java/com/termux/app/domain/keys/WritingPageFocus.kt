package com.termux.app.domain.keys

/**
 * True when the writing page stopped being visible. The pager can settle on the writing page while
 * the bar collapses (`keysVisible` false), so the page index alone cannot decide this.
 */
internal fun shouldHandBackFocus(previousWriting: Boolean, writing: Boolean): Boolean =
    previousWriting && !writing
