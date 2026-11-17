package com.termux.view.textselection

/** The tint to apply, or `null` to clear; 0 is the "no tint" value the callers pass. */
internal object HandleTintList {
    fun tintOrNull(tint: Int): Int? = if (tint == 0) null else tint
}
