package com.termux.app.ui.sessions

import androidx.compose.ui.graphics.vector.ImageVector
import com.termux.app.ui.materialIcon

/**
 * Vector icons for the sessions bar, drawn from 24x24 Material Design icon path data (Apache 2.0,
 * material-design-icons) like the extra-keys quick actions, so the new-session button is not a
 * text glyph.
 */
object SessionIcons {

    /** The Material "add" glyph used by the new-session button. */
    val Plus: ImageVector = materialIcon(
        "Plus",
        "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z",
    )
}