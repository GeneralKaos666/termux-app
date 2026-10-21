package com.termux.app.ui.keys

import androidx.compose.ui.graphics.vector.ImageVector
import com.termux.app.ui.materialIcon

/**
 * Vector icons for the extra-keys bar chrome (the collapse chevron and the back arrow of the
 * writing page).
 *
 * Drawn from 24x24 Material Design icon path data (Apache 2.0, material-design-icons) instead of
 * text glyphs: text glyphs depend on the device font and can render as black/coloured emoji
 * fallbacks. Vectors are tinted from the theme like the rest of the shell chrome.
 */
object QuickActionIcons {

    val ChevronDown: ImageVector = materialIcon(
        "ChevronDown",
        "M7.41 8.59L12 13.17l4.59-4.58L18 10l-6 6-6-6 1.41-1.41z",
    )

    val ChevronUp: ImageVector = materialIcon(
        "ChevronUp",
        "M7.41 15.41L12 10.83l4.59 4.58L18 14l-6-6-6 6 1.41 1.41z",
    )

    /** The Material "arrow_back" glyph used to leave the writing page for the first key grid. */
    val ArrowBack: ImageVector = materialIcon(
        "ArrowBack",
        "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z",
    )
}
