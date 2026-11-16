package com.termux.app.data.appearance

import com.termux.app.domain.appearance.ColorPropertiesDocument
import com.termux.app.domain.appearance.ColorSchemes
import com.termux.app.domain.appearance.CursorBlink
import com.termux.app.domain.appearance.CursorStyle
import com.termux.app.domain.appearance.FontError
import com.termux.app.domain.appearance.FontValidator
import com.termux.app.domain.appearance.INDEXED_COLOR_COUNT
import com.termux.app.domain.appearance.TerminalAppearance
import com.termux.app.domain.appearance.TerminalColorSet
import com.termux.app.domain.appearance.color
import com.termux.app.domain.appearance.colorToHex
import java.io.File

/**
 * Outcome of [AppearanceStore.write]. [fontInstalled] says whether any custom font is in place when
 * the write returns; [fontError] says why a candidate was rejected, if it was.
 */
data class AppearanceWriteResult(val fontInstalled: Boolean, val fontError: FontError?)

/**
 * Reads and writes the appearance the engine consumes: `colors.properties` for colors, `font.ttf`
 * for the font family, plus the font-size and cursor preferences.
 *
 * The colors file is edited line by line, so keys this screen does not manage (anything other than
 * `foreground`/`background`/`cursor` and `color0`..`color255`) survive a write untouched.
 */
class AppearanceStore(
    private val colorsFile: File,
    private val fontFile: File,
    private val preferences: AppearancePreferences,
    /**
     * How a validated candidate font is copied onto its install target. Injectable so a test can
     * force the copy to fail and check that the previously installed font survives.
     */
    private val copyForInstall: (source: File, target: File) -> Unit = { source, target ->
        source.copyTo(target, overwrite = true)
    },
) {

    /**
     * Reads the staged appearance: the managed colours from `colors.properties` (falling back to
     * the Termux default scheme per key), the installed font, the font size, and any app-local
     * cursor override (`null` when `termux.properties` governs the cursor instead).
     */
    fun read(): TerminalAppearance {
        val document = ColorPropertiesDocument(existingColorsText())
        val defaults = ColorSchemes.TERMUX_DEFAULT.colors

        val background = document.color("background") ?: defaults.background
        val colors = TerminalColorSet(
            foreground = document.color("foreground") ?: defaults.foreground,
            background = background,
            cursor = document.color("cursor") ?: cursorColorFor(background),
            ansi = List(INDEXED_COLOR_COUNT) { index -> document.color("color$index") ?: defaults.ansi[index] },
        )

        return TerminalAppearance(
            colors = colors,
            fontPath = installedFontPath(),
            fontSize = preferences.getFontSize(),
            cursorStyle = preferences.getCursorStyleOverride()?.let(CursorStyle::fromValue),
            cursorBlinkRate = preferences.getCursorBlinkRateOverride()?.let(CursorBlink::sanitize),
            ligatures = preferences.getLigatures(),
        )
    }

    /**
     * The cursor shape the terminal falls back to when the appearance sets no override: the
     * `termux.properties` value, or the engine default when it sets none either. Used to resolve
     * the screen's "Default" state for the live preview.
     */
    fun defaultCursorStyle(): CursorStyle = CursorStyle.fromValue(preferences.getDefaultCursorStyle())

    /**
     * Writes [appearance]. When [fontSource] is given it replaces the installed font if it is a
     * valid font file; otherwise the installed font is left alone, so a rejected candidate never
     * removes the font already in use.
     */
    fun write(appearance: TerminalAppearance, fontSource: File? = null): AppearanceWriteResult {
        val document = ColorPropertiesDocument(existingColorsText())
        val colors = appearance.colors
        document.set("foreground", colorToHex(colors.foreground))
        document.set("background", colorToHex(colors.background))
        document.set("cursor", colorToHex(colors.cursor))
        for (index in 0 until INDEXED_COLOR_COUNT) {
            document.set("color$index", colorToHex(colors.ansi[index]))
        }

        colorsFile.parentFile?.mkdirs()
        colorsFile.writeText(document.render())

        preferences.setFontSize(appearance.fontSize)
        // A null cursor value clears the override, handing the cursor back to termux.properties.
        preferences.setCursorStyle(appearance.cursorStyle?.value)
        preferences.setCursorBlinkRate(appearance.cursorBlinkRate?.let(CursorBlink::sanitize))
        preferences.setLigatures(appearance.ligatures)

        var fontError: FontError? = null
        if (fontSource != null) {
            fontError = FontValidator.validate(fontSource)
            if (fontError == null && !installFont(fontSource)) fontError = FontError.COPY_FAILED
        }

        return AppearanceWriteResult(fontInstalled = installedFontPath() != null, fontError = fontError)
    }

    /** Removes the installed custom font, returning to the monospace fallback. */
    fun removeFont(): Boolean = !fontFile.exists() || fontFile.delete()

    /**
     * The cursor color to use when `colors.properties` does not set one. Mirrors the engine's
     * contrast rule, reimplemented here because the engine helper needs `android.graphics.Color`,
     * which plain JVM tests cannot execute.
     */
    internal fun cursorColorFor(background: Int): Int {
        val red = background ushr 16 and 0xff
        val green = background ushr 8 and 0xff
        val blue = background and 0xff
        val brightness = Math.floor(
            Math.sqrt(red * red * 0.241 + green * green * 0.691 + blue * blue * 0.068),
        ).toInt()
        return if (brightness < BACKGROUND_BRIGHTNESS_THRESHOLD) CURSOR_ON_DARK else CURSOR_ON_LIGHT
    }

    private fun existingColorsText(): String = if (colorsFile.isFile) colorsFile.readText() else ""

    private fun installedFontPath(): String? =
        if (fontFile.isFile && fontFile.length() > 0) fontFile.path else null

    /**
     * Installs [source] by copying it to a sibling temp file and renaming it into place, so a copy
     * that fails partway leaves the previously installed font untouched instead of truncating it.
     */
    private fun installFont(source: File): Boolean {
        val directory = fontFile.parentFile ?: return false
        val temp = File(directory, fontFile.name + INSTALL_TEMP_SUFFIX)
        return try {
            copyForInstall(source, temp)
            if (temp.renameTo(fontFile)) {
                true
            } else {
                temp.delete()
                false
            }
        } catch (e: Exception) {
            temp.delete()
            false
        }
    }

    private companion object {
        const val BACKGROUND_BRIGHTNESS_THRESHOLD = 130
        const val CURSOR_ON_DARK = 0xffffffff.toInt()
        const val CURSOR_ON_LIGHT = 0xff000000.toInt()
        const val INSTALL_TEMP_SUFFIX = ".tmp"
    }
}
