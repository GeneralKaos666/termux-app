package com.termux.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Builds a 24x24 Material icon vector from its path data (Apache 2.0, material-design-icons).
 *
 * Shared by the per-screen icon sets ([com.termux.app.ui.keys.QuickActionIcons] and
 * [com.termux.app.ui.sessions.SessionIcons]) so the vector assembly lives in one place; callers
 * tint the resulting icon from the theme like the rest of the shell chrome.
 */
internal fun materialIcon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = SolidColor(Color.Black),
        )
    }.build()