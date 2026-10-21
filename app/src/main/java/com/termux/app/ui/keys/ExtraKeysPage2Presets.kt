package com.termux.app.ui.keys

import com.termux.shared.termux.settings.properties.TermuxPropertyConstants

/**
 * Preset layouts for the *second* page of the extra-keys bar, serialised into the same
 * `extra-keys-page2` property syntax the properties file uses, so the shared `ExtraKeysView`
 * engine parses them unchanged (see [KeySetPresets] for the first page).
 *
 * A preset only applies while the user has not set their own `extra-keys-page2` property in
 * `~/.termux/termux.properties` (see `TermuxTerminalExtraKeys.setExtraKeys()`); the custom
 * property always wins.
 */
object ExtraKeysPage2Presets {

    const val PRESET_DEFAULT = "default"
    const val PRESET_FUNCTION = "function"

    data class Preset(val id: String, val rows: List<List<ExtraKey>>)

    /** All presets in settings order. [PRESET_DEFAULT] first. */
    val ALL: List<Preset> = listOf(
        Preset(PRESET_DEFAULT, KeySet.DEFAULT_PAGE2),
        Preset(
            PRESET_FUNCTION,
            listOf(
                listOf(
                    ExtraKey("F1"),
                    ExtraKey("F2"),
                    ExtraKey("F3"),
                    ExtraKey("F4"),
                    ExtraKey("F5"),
                    ExtraKey("F6"),
                    ExtraKey("F7"),
                    ExtraKey("F8"),
                ),
                listOf(
                    ExtraKey("F9"),
                    ExtraKey("F10"),
                    ExtraKey("F11"),
                    ExtraKey("F12"),
                    ExtraKey("PGUP"),
                    ExtraKey("PGDN"),
                    ExtraKey("HOME"),
                    ExtraKey("END"),
                ),
                listOf(
                    ExtraKey("ESC"),
                    ExtraKey("TAB"),
                    ExtraKey("CTRL"),
                    ExtraKey("ALT"),
                    ExtraKey("LEFT"),
                    ExtraKey("DOWN"),
                    ExtraKey("UP"),
                    ExtraKey("RIGHT"),
                ),
            ),
        ),
    )

    /** The preset JSON for [id], or the default layout for unknown ids. */
    @JvmStatic
    fun jsonFor(id: String?): String {
        val preset = ALL.firstOrNull { it.id == id }
        if (preset == null || preset.id == PRESET_DEFAULT) {
            return TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_PAGE2
        }
        return KeySetPresets.serializeExtraKeys(preset.rows)
    }
}
