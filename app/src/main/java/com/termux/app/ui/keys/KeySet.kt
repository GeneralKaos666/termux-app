package com.termux.app.ui.keys

/**
 * Pure, Android-free description of a single extra key.
 *
 * The key grid itself is still rendered and handled by the shared
 * `com.termux.shared.termux.extrakeys.ExtraKeysView` engine (see [ExtraKeysBar]): that view owns
 * modifier buttons, swipe-up popups, long-press repeat, theme colors and parsing of a user's
 * custom `extra-keys` property.
 *
 * @property key The key name understood by `ExtraKeysView` (e.g. `ESC`, `UP`, `CTRL`) or the literal
 *   text to write when it is not a known key.
 * @property popup Optional popup key triggered when the button is swiped up.
 */
data class ExtraKey(val key: String, val popup: String? = null)

/**
 * The layout data the shell reasons about.
 *
 * [DEFAULT] mirrors `TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS` as plain Kotlin so the
 * defaults have a canonical, dependency-free reference and a unit test can prove they have not
 * drifted from the shared constant.
 */
object KeySet {

    /**
     * Termux's default extra-keys layout, the double row:
     * `[['ESC','/',{key:'-',popup:'|'},'HOME','UP','END','PGUP'],
     *   ['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN']]`
     */
    val DEFAULT: List<List<ExtraKey>> = listOf(
        listOf(
            ExtraKey("ESC"),
            ExtraKey("/"),
            ExtraKey("-", popup = "|"),
            ExtraKey("HOME"),
            ExtraKey("UP"),
            ExtraKey("END"),
            ExtraKey("PGUP"),
        ),
        listOf(
            ExtraKey("TAB"),
            ExtraKey("CTRL"),
            ExtraKey("ALT"),
            ExtraKey("LEFT"),
            ExtraKey("DOWN"),
            ExtraKey("RIGHT"),
            ExtraKey("PGDN"),
        ),
    )
}
