package com.termux.app.ui.appearance

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.app.domain.appearance.ColorSchemes
import com.termux.app.domain.appearance.CursorBlink
import com.termux.app.domain.appearance.CursorStyle
import com.termux.app.domain.appearance.INDEXED_COLOR_COUNT
import com.termux.app.domain.appearance.TerminalAppearance
import com.termux.app.domain.appearance.TerminalColorSet

/**
 * Compose-observable staged appearance edits. [loaded] is what was last read from (or written to)
 * the store, [draft] is what the screen shows; [isDirty] is the difference the user must apply or
 * discard.
 */
class AppearanceState(private val defaultAppearance: TerminalAppearance) {

    var loaded: TerminalAppearance by mutableStateOf(defaultAppearance)
        private set

    var draft: TerminalAppearance by mutableStateOf(defaultAppearance)
        private set

    val isDirty: Boolean
        get() = draft != loaded

    /** Takes the appearance just read from the store as both the draft and the applied value. */
    fun load(appearance: TerminalAppearance) {
        loaded = appearance
        draft = appearance
    }

    /** Commits the draft, making it the applied appearance. */
    fun apply() {
        loaded = draft
    }

    /** Throws the draft away, restoring the applied appearance. */
    fun discard() {
        draft = loaded
    }

    /**
     * Stages the Termux default scheme: colors, cursor shape and blink. The chosen font family and
     * size are separate settings and are left alone. Cursor shape and blink are staged as "follow
     * `termux.properties`", so applying Reset hands control of the cursor back to the properties
     * file instead of pinning it to a value the user never chose.
     */
    fun resetToDefault() {
        draft = draft.copy(
            colors = ColorSchemes.TERMUX_DEFAULT.colors,
            cursorStyle = null,
            cursorBlinkRate = null,
        )
    }

    fun setForeground(color: Int) {
        draft = draft.copy(colors = draft.colors.copy(foreground = color))
    }

    fun setBackground(color: Int) {
        draft = draft.copy(colors = draft.colors.copy(background = color))
    }

    fun setCursorColor(color: Int) {
        draft = draft.copy(colors = draft.colors.copy(cursor = color))
    }

    fun setAnsiColor(index: Int, color: Int) {
        require(index in 0 until INDEXED_COLOR_COUNT) { "indexed color index out of range: $index" }
        val ansi = draft.colors.ansi.toMutableList()
        ansi[index] = color
        draft = draft.copy(colors = draft.colors.copy(ansi = ansi))
    }

    fun setColors(colors: TerminalColorSet) {
        draft = draft.copy(colors = colors)
    }

    fun setFontPath(path: String?) {
        draft = draft.copy(fontPath = path)
    }

    fun setFontSize(size: Int) {
        draft = draft.copy(fontSize = size)
    }

    /** Stages a cursor shape, or `null` to clear the override and follow `termux.properties`. */
    fun setCursorStyle(style: CursorStyle?) {
        draft = draft.copy(cursorStyle = style)
    }

    /** Stages a blink rate, or `null` to clear the override and follow `termux.properties`. */
    fun setCursorBlinkRate(rate: Int?) {
        draft = draft.copy(cursorBlinkRate = rate?.let(CursorBlink::sanitize))
    }

    fun setLigatures(enabled: Boolean) {
        draft = draft.copy(ligatures = enabled)
    }
}
