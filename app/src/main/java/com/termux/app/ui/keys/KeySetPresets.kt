package com.termux.app.ui.keys

import com.termux.shared.termux.settings.properties.TermuxPropertyConstants

/**
 * Preset extra-key layouts offered in Settings, each serialised into the same `extra-keys`
 * property syntax the properties file uses, so the shared `ExtraKeysView` engine parses them
 * unchanged.
 *
 * A preset only applies while the user has not set their own `extra-keys` property in
 * `~/.termux/termux.properties` (see `TermuxTerminalExtraKeys.setExtraKeys()`); the custom
 * property always wins.
 */
object KeySetPresets {

    const val PRESET_DEFAULT = "default"
    const val PRESET_COMPACT = "compact"
    const val PRESET_VIM = "vim"
    const val PRESET_EMACS = "emacs"

    data class Preset(val id: String, val rows: List<List<ExtraKey>>)

    /** All presets in settings order. [PRESET_DEFAULT] first. */
    val ALL: List<Preset> = listOf(
        Preset(PRESET_DEFAULT, KeySet.DEFAULT),
        Preset(
            PRESET_COMPACT,
            listOf(
                listOf(
                    ExtraKey("ESC"),
                    ExtraKey("/"),
                    ExtraKey("-"),
                    ExtraKey("TAB"),
                    ExtraKey("CTRL"),
                    ExtraKey("ALT"),
                    ExtraKey("UP"),
                    ExtraKey("DOWN"),
                ),
            ),
        ),
        Preset(
            PRESET_VIM,
            listOf(
                listOf(
                    ExtraKey("ESC"),
                    ExtraKey("/"),
                    ExtraKey(":", popup = "!"),
                    ExtraKey("PGUP"),
                    ExtraKey("UP"),
                    ExtraKey("PGDN"),
                    ExtraKey("END"),
                ),
                listOf(
                    ExtraKey("TAB"),
                    ExtraKey("CTRL"),
                    ExtraKey("ALT"),
                    ExtraKey("LEFT"),
                    ExtraKey("DOWN"),
                    ExtraKey("RIGHT"),
                    ExtraKey("$", popup = "^"),
                ),
            ),
        ),
        Preset(
            PRESET_EMACS,
            listOf(
                listOf(
                    ExtraKey("ESC"),
                    ExtraKey("CTRL"),
                    ExtraKey("ALT"),
                    ExtraKey("TAB"),
                    ExtraKey("UP"),
                    ExtraKey("PGUP"),
                    ExtraKey("HOME"),
                ),
                listOf(
                    ExtraKey("LEFT"),
                    ExtraKey("DOWN"),
                    ExtraKey("RIGHT"),
                    ExtraKey("END"),
                    ExtraKey("PGDN"),
                    ExtraKey("ENTER"),
                    ExtraKey("ESC", popup = "CTRL"),
                ),
            ),
        ),
    )

    /** The preset JSON for [id], or the default layout for unknown ids. */
    @JvmStatic
    fun jsonFor(id: String?): String {
        val preset = ALL.firstOrNull { it.id == id }
        if (preset == null || preset.id == PRESET_DEFAULT) return TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS
        return serializeExtraKeys(preset.rows)
    }

    /**
     * Serialises rows into the `extra-keys` property syntax, mirroring the shared default:
     * `[['ESC','/',{key: '-', popup: '|'},...], [...]]`.
     */
    fun serializeExtraKeys(rows: List<List<ExtraKey>>): String = buildString {
        append('[')
        rows.forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) append(", ")
            append('[')
            row.forEachIndexed { keyIndex, key ->
                if (keyIndex > 0) append(',')
                if (key.popup != null) {
                    append("{key: '").append(key.key).append("', popup: '").append(key.popup).append("'}")
                } else {
                    append('\'').append(key.key).append('\'')
                }
            }
            append(']')
        }
        append(']')
    }
}
