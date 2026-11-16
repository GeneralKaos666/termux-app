package com.termux.view.textselection;

/**
 * Picks the colour the text-selection handles are drawn in.
 *
 * The handles used to be a fixed light blue that vanished on light terminal backgrounds and clashed
 * with most themes. They now follow the app accent, but only while the accent is still visible on
 * the terminal background: WCAG's 3:1 non-text contrast is the threshold, and below it the handles
 * fall back to plain white or black, whichever contrasts more with the background.
 */
public final class HandleTint {

    /** WCAG 1.4.11: non-text UI components need at least 3:1 against their background. */
    private static final double MIN_CONTRAST = 3.0;

    private HandleTint() {
    }

    /**
     * @param terminalBackground the colour painted behind the terminal text.
     * @param shellAccent        the app accent colour the handles should use when they can.
     * @return {@code shellAccent} when it reaches {@link #MIN_CONTRAST} against the background,
     *         otherwise white on a dark background and black on a light one. The returned colour is
     *         always opaque.
     */
    public static int handleTint(int terminalBackground, int shellAccent) {
        int accent = opaque(shellAccent);
        if (contrastRatio(terminalBackground, accent) >= MIN_CONTRAST) return accent;
        return contrastRatio(terminalBackground, 0xFFFFFFFF) >= contrastRatio(terminalBackground, 0xFF000000)
            ? 0xFFFFFFFF
            : 0xFF000000;
    }

    /** Drops any alpha, so a translucent accent is judged by the colour it actually shows. */
    private static int opaque(int color) {
        return 0xFF000000 | (color & 0x00FFFFFF);
    }

    private static double contrastRatio(int first, int second) {
        double lighter = Math.max(luminance(first), luminance(second));
        double darker = Math.min(luminance(first), luminance(second));
        return (lighter + 0.05) / (darker + 0.05);
    }

    /** WCAG relative luminance of an opaque colour. */
    private static double luminance(int color) {
        double red = channel(color >> 16 & 0xFF);
        double green = channel(color >> 8 & 0xFF);
        double blue = channel(color & 0xFF);
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
    }

    private static double channel(int value) {
        double srgb = value / 255.0;
        return srgb <= 0.03928 ? srgb / 12.92 : Math.pow((srgb + 0.055) / 1.055, 2.4);
    }
}
