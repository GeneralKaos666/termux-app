package com.termux.app.ui.sessions

import androidx.compose.ui.graphics.vector.ImageVector
import com.termux.app.ui.materialIcon

/**
 * Vector icons for the sessions bar, drawn from 24x24 Material Design icon path data (Apache 2.0,
 * material-design-icons) like the extra-keys quick actions.
 */
object SessionIcons {

    /** The Material "add" glyph used by the new-session button. */
    val Plus: ImageVector = materialIcon(
        "Plus",
        "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z",
    )

    /** The Material "more_vert" glyph used for the active tab's overflow menu. */
    val MoreVert: ImageVector = materialIcon(
        "MoreVert",
        "M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z",
    )
}
