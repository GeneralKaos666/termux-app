package com.termux.app.domain.palette

/** What the palette does when a row is chosen. */
enum class PaletteAction {
    NEW_SESSION,
    NEW_FLOAT_SESSION,
    JUMP_SESSION,
    OPEN_APPEARANCE,
    OPEN_SETTINGS,
    TOGGLE_KEYS,
    PASTE,
    RUN_SNIPPET,
}

/**
 * One row of the command palette. [label] and [hint] are already localized by the caller; the
 * domain owns matching and ordering, not the words.
 */
data class PaletteEntry(
    val id: String,
    val label: String,
    val hint: String?,
    val action: PaletteAction,
    val payload: String? = null,
)

/** Localized labels for the palette's fixed app actions. */
data class PaletteLabels(
    val newSession: String,
    val newFloatSession: String,
    val openAppearance: String,
    val openSettings: String,
    val toggleKeys: String,
    val paste: String,
)
