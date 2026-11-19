package com.termux.app.ui.sessions

/**
 * Strength of the scroll-edge fade (0 clear, 1 opaque).
 *
 * The fade exists only where content is hidden: an edge sitting at its scroll boundary draws
 * nothing, and a scroll of a pixel or two is below the ramp threshold, so the bar does not blink a
 * gradient while the user is just tapping tabs.
 */
internal fun edgeFadeAlpha(
    scrollFraction: Float,
    atStart: Boolean,
    atEnd: Boolean,
    leading: Boolean,
): Float {
    if (if (leading) atStart else atEnd) return 0f
    val fraction = scrollFraction.coerceIn(0f, 1f)
    if (fraction < 0.02f) return 0f
    return ((fraction - 0.02f) / 0.1f).coerceIn(0f, 1f)
}

/**
 * How far the row must scroll to bring a tab into view: `0` when it is fully visible, the negative
 * leading overlap when it starts before the viewport, and the positive trailing overlap when it ends
 * past it. A tab wider than the viewport therefore scrolls to its leading edge.
 */
internal fun minimalScrollDelta(tabOffset: Int, tabSize: Int, viewportStart: Int, viewportEnd: Int): Int {
    val leadingOverlap = tabOffset - viewportStart
    val trailingOverlap = tabOffset + tabSize - viewportEnd
    return when {
        leadingOverlap < 0 -> leadingOverlap
        trailingOverlap > 0 -> trailingOverlap
        else -> 0
    }
}
