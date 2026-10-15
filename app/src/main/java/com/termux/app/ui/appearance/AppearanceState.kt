package com.termux.app.ui.appearance

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.TerminalEmulator
import java.util.Locale

/** Number of ANSI palette entries the appearance screen manages (indices `color0`..`color15`). */
const val ANSI_COLOR_COUNT = 16

/** Cursor shapes the terminal engine understands. */
enum class CursorStyle(val value: Int) {
    BLOCK(TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK),
    UNDERLINE(TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE),
    BAR(TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR);

    companion object {
        /** Maps an engine or preference value to a style; anything unknown falls back to [BLOCK]. */
        fun fromValue(value: Int?): CursorStyle = CursorStyle.entries.firstOrNull { it.value == value } ?: BLOCK
    }
}

/** Cursor blink rates the terminal view accepts: `0` (steady) or 100..2000 ms. */
object CursorBlink {

    const val MIN_RATE = 100
    const val MAX_RATE = 2000

    /**
     * Returns [rate] when the terminal view accepts it and `0` otherwise. The view disables blinking
     * for anything outside the range, so the screen never stages a rate that cannot take effect.
     */
    fun sanitize(rate: Int): Int = if (rate == 0 || rate in MIN_RATE..MAX_RATE) rate else 0
}

/** The 18 colors the appearance screen manages: 16 ANSI entries plus foreground, background, cursor. */
data class TerminalColorSet(
    val foreground: Int,
    val background: Int,
    val cursor: Int,
    val ansi: List<Int>,
) {
    init {
        require(ansi.size == ANSI_COLOR_COUNT) {
            "expected $ANSI_COLOR_COUNT ANSI colors but got ${ansi.size}"
        }
    }
}

/** A complete terminal appearance, as staged by the screen and persisted by [AppearanceStore]. */
data class TerminalAppearance(
    val colors: TerminalColorSet,
    val fontPath: String?,
    val fontSize: Int,
    val cursorStyle: CursorStyle,
    val cursorBlinkRate: Int,
)

/**
 * Parses a color the way the terminal engine does: `#RGB`, `#RRGGBB`, `#RRRGGGBBB`,
 * `#RRRRGGGGBBBB` or `rgb:<r>/<g>/<b>` with equally long components.
 *
 * @return the color as `0xFFrrggbb`, or `null` when [text] is not a color.
 */
fun parseColor(text: String): Int? {
    val skipInitial: Int
    val skipBetween: Int
    when {
        text.startsWith("#") -> {
            skipInitial = 1
            skipBetween = 0
        }
        text.startsWith("rgb:") -> {
            skipInitial = 4
            skipBetween = 1
        }
        else -> return null
    }

    val charsForColors = text.length - skipInitial - 2 * skipBetween
    if (charsForColors <= 0 || charsForColors % 3 != 0) return null
    val componentLength = charsForColors / 3
    val scale = 255.0 / (Math.pow(16.0, componentLength.toDouble()) - 1)

    var position = skipInitial
    fun component(): Int {
        val value = text.substring(position, position + componentLength)
        position += componentLength + skipBetween
        return (value.toInt(16) * scale).toInt()
    }

    return try {
        val red = component()
        val green = component()
        val blue = component()
        0xFF shl 24 or (red shl 16) or (green shl 8) or blue
    } catch (e: NumberFormatException) {
        null
    } catch (e: IndexOutOfBoundsException) {
        null
    }
}

/** Renders a color as the `#rrggbb` form written into `colors.properties`. */
fun colorToHex(color: Int): String = String.format(Locale.ROOT, "#%06x", color and 0x00ffffff)

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
     * size are separate settings and are left alone.
     */
    fun resetToDefault() {
        draft = draft.copy(
            colors = ColorSchemes.TERMUX_DEFAULT.colors,
            cursorStyle = CursorStyle.BLOCK,
            cursorBlinkRate = 0,
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
        require(index in 0 until ANSI_COLOR_COUNT) { "ANSI color index out of range: $index" }
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

    fun setCursorStyle(style: CursorStyle) {
        draft = draft.copy(cursorStyle = style)
    }

    fun setCursorBlinkRate(rate: Int) {
        draft = draft.copy(cursorBlinkRate = CursorBlink.sanitize(rate))
    }
}
