package com.termux.app.ui.keys

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Compose-observable state of the bottom extra-keys bar.
 *
 * Owned by the Java [com.termux.app.TermuxActivity], which mutates it as preferences and the
 * active key set change; Compose recomposes on assignment. The property setters are intentionally
 * public so Java can assign them directly.
 */
class ExtraKeysState {

    /** Whether the whole bar is shown. Mirrors the old "terminal toolbar" preference. */
    var visible: Boolean by mutableStateOf(false)

    /**
     * Which page of the bar's pager is showing: [PAGE_KEYS] (the key grid), [PAGE_ACTIONS] (the
     * quick actions) or [PAGE_WRITING] (the text-send field). Written by Compose when the user
     * swipes or taps a page dot, and by [textInputVisible] when Java toggles the writing page.
     * Out-of-range assignments are clamped.
     */
    var page: Int
        get() = pageState
        set(value) {
            pageState = value.coerceIn(PAGE_KEYS, PAGE_WRITING)
        }

    private var pageState: Int by mutableStateOf(PAGE_KEYS)

    /**
     * Whether the "type a line and send" field is showing, i.e. the pager is on [PAGE_WRITING].
     * Derived from [page] so the Java-facing contract in
     * [com.termux.app.TermuxActivity] (get/setTextInputVisible) keeps working while Compose drives
     * the pager directly on swipes.
     */
    var textInputVisible: Boolean
        get() = page == PAGE_WRITING
        set(value) {
            page = if (value) PAGE_WRITING else PAGE_KEYS
        }

    /** Total height of the key grid in pixels, or 0 when there are no keys to show. */
    var gridHeightPx: Int by mutableStateOf(0)

    companion object {
        /** Page index of the extra-keys grid. */
        const val PAGE_KEYS = 0

        /** Page index of the quick-actions page. */
        const val PAGE_ACTIONS = 1

        /** Page index of the text-send field. */
        const val PAGE_WRITING = 2
    }
}
