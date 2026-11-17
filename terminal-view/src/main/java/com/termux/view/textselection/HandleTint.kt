package com.termux.view.textselection

/**
 * Picks the colour the text-selection handles are drawn in.
 *
 * The handles used to be a fixed light blue that vanished on light terminal backgrounds and clashed
 * with most themes. They now follow the app accent, but only while the accent is still visible on
 * the terminal background: WCAG's 3:1 non-text contrast is the threshold, and below it the handles
 * fall back to plain white or black, whichever contrasts more with the background.
 */
object HandleTint {

    /** WCAG 1.4.11: non-text UI components need at least 3:1 against their background. */
    private const val MIN_CONTRAST = 3.0

    /**
     * @param terminalBackground the colour painted behind the terminal text.
     * @param shellAccent        the app accent colour the handles should use when they can.
     * @return `shellAccent` when it reaches [MIN_CONTRAST] against the background,
     *         otherwise white on a dark background and black on a light one. The returned colour is
     *         always opaque.
     */
    @JvmStatic
    fun handleTint(terminalBackground: Int, shellAccent: Int): Int {
        val accent = opaque(shellAccent)
        if (contrastRatio(terminalBackground, accent) >= MIN_CONTRAST) return accent
        return if (contrastRatio(terminalBackground, 0xFFFFFFFF.toInt()) >= contrastRatio(terminalBackground, 0xFF000000.toInt())) {
            0xFFFFFFFF.toInt()
        } else {
            0xFF000000.toInt()
        }
    }

    /** Drops any alpha, so a translucent accent is judged by the colour it actually shows. */
    private fun opaque(color: Int): Int {
        return 0xFF000000.toInt() or (color and 0x00FFFFFF)
    }

    private fun contrastRatio(first: Int, second: Int): Double {
        val lighter = Math.max(luminance(first), luminance(second))
        val darker = Math.min(luminance(first), luminance(second))
        return (lighter + 0.05) / (darker + 0.05)
    }

    /** WCAG relative luminance of an opaque colour. */
    private fun luminance(color: Int): Double {
        val red = channel(color shr 16 and 0xFF)
        val green = channel(color shr 8 and 0xFF)
        val blue = channel(color and 0xFF)
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue
    }

    private fun channel(value: Int): Double {
        val srgb = value / 255.0
        return if (srgb <= 0.03928) srgb / 12.92 else Math.pow((srgb + 0.055) / 1.055, 2.4)
    }
}
