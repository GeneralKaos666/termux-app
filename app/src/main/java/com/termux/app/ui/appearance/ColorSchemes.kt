package com.termux.app.ui.appearance

/** A named bundle of the 18 colors the appearance screen manages. */
data class ColorSchemePreset(val name: String, val colors: TerminalColorSet)

/**
 * Color schemes offered by the appearance screen. The values are the palettes each project
 * publishes for terminal emulators, so a chosen preset looks like its upstream theme.
 */
object ColorSchemes {

    /** The terminal engine's own default scheme; "Reset to default" stages exactly this. */
    val TERMUX_DEFAULT = preset(
        name = "Termux Default",
        foreground = "#ffffff",
        background = "#000000",
        cursor = "#ffffff",
        ansi = listOf(
            "#000000", "#cd0000", "#00cd00", "#cdcd00", "#6495ed", "#cd00cd", "#00cdcd", "#e5e5e5",
            "#7f7f7f", "#ff0000", "#00ff00", "#ffff00", "#5c5cff", "#ff00ff", "#00ffff", "#ffffff",
        ),
    )

    val NORD = preset(
        name = "Nord",
        foreground = "#d8dee9",
        background = "#2e3440",
        cursor = "#d8dee9",
        ansi = listOf(
            "#3b4252", "#bf616a", "#a3be8c", "#ebcb8b", "#81a1c1", "#b48ead", "#88c0d0", "#e5e9f0",
            "#4c566a", "#bf616a", "#a3be8c", "#ebcb8b", "#81a1c1", "#b48ead", "#8fbcbb", "#eceff4",
        ),
    )

    val DRACULA = preset(
        name = "Dracula",
        foreground = "#f8f8f2",
        background = "#282a36",
        cursor = "#f8f8f2",
        ansi = listOf(
            "#21222c", "#ff5555", "#50fa7b", "#f1fa8c", "#bd93f9", "#ff79c6", "#8be9fd", "#f8f8f2",
            "#6272a4", "#ff6e6e", "#69ff94", "#ffffa5", "#d6acff", "#ff92df", "#a4ffff", "#ffffff",
        ),
    )

    val SOLARIZED_DARK = preset(
        name = "Solarized Dark",
        foreground = "#839496",
        background = "#002b36",
        cursor = "#839496",
        ansi = listOf(
            "#073642", "#dc322f", "#859900", "#b58900", "#268bd2", "#d33682", "#2aa198", "#eee8d5",
            "#002b36", "#cb4b16", "#586e75", "#657b83", "#839496", "#6c71c4", "#93a1a1", "#fdf6e3",
        ),
    )

    val SOLARIZED_LIGHT = preset(
        name = "Solarized Light",
        foreground = "#657b83",
        background = "#fdf6e3",
        cursor = "#657b83",
        ansi = listOf(
            "#073642", "#dc322f", "#859900", "#b58900", "#268bd2", "#d33682", "#2aa198", "#eee8d5",
            "#002b36", "#cb4b16", "#586e75", "#657b83", "#839496", "#6c71c4", "#93a1a1", "#fdf6e3",
        ),
    )

    val GRUVBOX_DARK = preset(
        name = "Gruvbox Dark",
        foreground = "#ebdbb2",
        background = "#282828",
        cursor = "#ebdbb2",
        ansi = listOf(
            "#282828", "#cc241d", "#98971a", "#d79921", "#458588", "#b16286", "#689d6a", "#a89984",
            "#928374", "#fb4934", "#b8bb26", "#fabd2f", "#83a598", "#d3869b", "#8ec07c", "#ebdbb2",
        ),
    )

    val ONE_DARK = preset(
        name = "One Dark",
        foreground = "#abb2bf",
        background = "#282c34",
        cursor = "#528bff",
        ansi = listOf(
            "#282c34", "#e06c75", "#98c379", "#e5c07b", "#61afef", "#c678dd", "#56b6c2", "#abb2bf",
            "#5c6370", "#e06c75", "#98c379", "#e5c07b", "#61afef", "#c678dd", "#56b6c2", "#ffffff",
        ),
    )

    /** Every preset, in the order the screen shows them. */
    val presets: List<ColorSchemePreset> =
        listOf(TERMUX_DEFAULT, NORD, DRACULA, SOLARIZED_DARK, SOLARIZED_LIGHT, GRUVBOX_DARK, ONE_DARK)

    /** Looks a preset up by its display name, or `null` when it is not a bundled scheme. */
    fun byName(name: String): ColorSchemePreset? = presets.firstOrNull { it.name == name }

    private fun preset(
        name: String,
        foreground: String,
        background: String,
        cursor: String,
        ansi: List<String>,
    ) = ColorSchemePreset(
        name = name,
        colors = TerminalColorSet(
            foreground = hex(name, foreground),
            background = hex(name, background),
            cursor = hex(name, cursor),
            ansi = ansi.map { hex(name, it) },
        ),
    )

    private fun hex(scheme: String, value: String): Int =
        requireNotNull(parseColor(value)) { "$scheme has an invalid color literal: $value" }
}
