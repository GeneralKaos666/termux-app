package com.termux.app.domain.appearance

import com.termux.terminal.TerminalEmulator
import java.util.Locale

/** The 16 named ANSI colours (indices 0..15), shown as the quick swatches. */
const val ANSI_COLOR_COUNT = 16

/** Every indexed colour the terminal supports (0..255): the 16 ANSI colours plus the 240-colour extended palette. */
const val INDEXED_COLOR_COUNT = 256

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

/** The 259 colors the appearance screen manages: all 256 indexed colours plus foreground, background, cursor. */
data class TerminalColorSet(
    val foreground: Int,
    val background: Int,
    val cursor: Int,
    /** The indexed colours `color0`..`color255` in the order the engine indexes them. */
    val ansi: List<Int>,
) {
    init {
        require(ansi.size == INDEXED_COLOR_COUNT) {
            "expected $INDEXED_COLOR_COUNT indexed colors but got ${ansi.size}"
        }
    }
}

/** A complete terminal appearance, as staged by the screen and persisted by [AppearanceStore]. */
data class TerminalAppearance(
    val colors: TerminalColorSet,
    val fontPath: String?,
    val fontSize: Int,
    /** The cursor shape override, or `null` to follow `termux.properties`. */
    val cursorStyle: CursorStyle?,
    /** The blink-rate override in ms, or `null` to follow `termux.properties`. */
    val cursorBlinkRate: Int?,
    /** Whether the terminal asks the font for ligatures. */
    val ligatures: Boolean = true,
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

/** The standard names of the 16 ANSI colours; entries 8..15 are the bright variants of 0..7. */
private val ANSI_COLOR_NAMES = listOf("Black", "Red", "Green", "Yellow", "Blue", "Magenta", "Cyan", "White")

/**
 * The human description of indexed colour [index] (0..255): the ANSI name for 0..15, the cube
 * coordinate for 16..231, or the grayscale step for 232..255. Purely a function of the index, so the
 * screen and its tests share one source of truth.
 */
fun colorLabel(index: Int): String = when {
    index in 0..7 -> "ANSI $index · ${ANSI_COLOR_NAMES[index]}"
    index in 8..15 -> "ANSI $index · Bright ${ANSI_COLOR_NAMES[index - 8]}"
    index in 16..231 -> {
        val cube = index - 16
        val red = cube / 36
        val green = cube / 6 % 6
        val blue = cube % 6
        "RGB cube · red=$red green=$green blue=$blue"
    }
    index in 232..255 -> "Grayscale · step ${index - 231} of 24"
    else -> throw IllegalArgumentException("indexed color index out of range: $index")
}
