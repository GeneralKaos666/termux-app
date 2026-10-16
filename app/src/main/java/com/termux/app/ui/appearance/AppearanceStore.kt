package com.termux.app.ui.appearance

import java.io.File
import java.io.IOException

/**
 * App-local settings the appearance screen owns.
 *
 * Font size already lives in the app's shared preferences. Cursor style and blink rate stay
 * app-local, and `termux.properties` is only the fallback: the override getters return the
 * app-local value (or `null` when unset), so the screen can represent and clear an override, while
 * [AppearancePreferences.getDefaultCursorStyle] exposes the `termux.properties` value the terminal
 * would use instead.
 */
interface AppearancePreferences {

    fun getFontSize(): Int

    fun setFontSize(size: Int)

    /** The app-local cursor-style override, or `null` when `termux.properties` governs. */
    fun getCursorStyleOverride(): Int?

    /** The `termux.properties` cursor style, or `null` when it sets none. */
    fun getDefaultCursorStyle(): Int?

    fun setCursorStyle(style: Int?)

    /** The app-local blink-rate override, or `null` when `termux.properties` governs. */
    fun getCursorBlinkRateOverride(): Int?

    fun setCursorBlinkRate(rate: Int?)
}

/** Why a candidate font was not installed. */
enum class FontError { NOT_FOUND, INVALID, TOO_LARGE, COPY_FAILED }

/**
 * Outcome of [AppearanceStore.write]. [fontInstalled] says whether any custom font is in place when
 * the write returns; [fontError] says why a candidate was rejected, if it was.
 */
data class AppearanceWriteResult(val fontInstalled: Boolean, val fontError: FontError?)

/**
 * Shared-preferences keys the appearance screen owns, kept app-local so `:termux-shared` needs no
 * change. The read sites on the terminal view consume these constants rather than literals.
 */
object AppearancePreferenceKeys {
    /** The app-local preferences file that holds the appearance keys. */
    const val PREFERENCES_FILE = "termux_appearance"

    const val CURSOR_STYLE = "appearance_cursor_style"
    const val CURSOR_BLINK_RATE = "appearance_cursor_blink_rate"

    /** Bumped on every successful apply so the terminal can detect a change it has not shown. */
    const val STYLING_REVISION = "appearance_styling_revision"
}

/** Validates a candidate font before it replaces the installed one. */
object FontValidator {

    /** Fonts larger than this are rejected rather than copied. */
    const val MAX_FONT_BYTES = 8L * 1024 * 1024

    private val SIGNATURES = listOf(
        byteArrayOf(0x00, 0x01, 0x00, 0x00), // TrueType
        "true".toByteArray(), // TrueType (Apple)
        "OTTO".toByteArray(), // CFF/OpenType
        "ttcf".toByteArray(), // TrueType collection
    )

    /** @return `null` when [file] can be installed, otherwise the reason it cannot. */
    fun validate(file: File): FontError? {
        if (!file.isFile || !file.canRead()) return FontError.NOT_FOUND
        if (file.length() == 0L) return FontError.INVALID
        if (file.length() > MAX_FONT_BYTES) return FontError.TOO_LARGE
        return if (hasFontSignature(file)) null else FontError.INVALID
    }

    private fun hasFontSignature(file: File): Boolean {
        val head = ByteArray(4)
        val read = try {
            file.inputStream().use { it.read(head) }
        } catch (e: IOException) {
            return false
        }
        return read == head.size && SIGNATURES.any { it.contentEquals(head) }
    }
}

/**
 * Reads and writes the appearance the engine consumes: `colors.properties` for colors, `font.ttf`
 * for the font family, plus the font-size and cursor preferences.
 *
 * The colors file is edited line by line, so keys this screen does not manage (`color16`..`color255`
 * and anything hand-added) survive a write untouched.
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
            ansi = List(ANSI_COLOR_COUNT) { index -> document.color("color$index") ?: defaults.ansi[index] },
        )

        return TerminalAppearance(
            colors = colors,
            fontPath = installedFontPath(),
            fontSize = preferences.getFontSize(),
            cursorStyle = preferences.getCursorStyleOverride()?.let(CursorStyle::fromValue),
            cursorBlinkRate = preferences.getCursorBlinkRateOverride()?.let(CursorBlink::sanitize),
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
        for (index in 0 until ANSI_COLOR_COUNT) {
            document.set("color$index", colorToHex(colors.ansi[index]))
        }

        colorsFile.parentFile?.mkdirs()
        colorsFile.writeText(document.render())

        preferences.setFontSize(appearance.fontSize)
        // A null cursor value clears the override, handing the cursor back to termux.properties.
        preferences.setCursorStyle(appearance.cursorStyle?.value)
        preferences.setCursorBlinkRate(appearance.cursorBlinkRate?.let(CursorBlink::sanitize))

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

/**
 * The line structure of `colors.properties`. Keys are looked up like `Properties` does (`key=value`
 * or `key:value`); [set] rewrites the existing line or appends a new one, and [render] keeps every
 * other line - comments, blank lines and keys this screen does not manage - exactly as it was.
 */
internal class ColorPropertiesDocument(text: String) {

    private val lines: MutableList<String> =
        if (text.isEmpty()) mutableListOf() else text.trimEnd('\n').split('\n').toMutableList()

    fun get(key: String): String? =
        lines.firstNotNullOfOrNull { line -> parseLine(line)?.takeIf { it.first == key }?.second }

    fun set(key: String, value: String) {
        val entry = "$key=$value"
        for (index in lines.indices) {
            if (parseLine(lines[index])?.first == key) {
                lines[index] = entry
                return
            }
        }
        lines.add(entry)
    }

    fun render(): String = if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"

    private fun parseLine(line: String): Pair<String, String>? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) return null
        val separator = trimmed.indexOfFirst { it == '=' || it == ':' || it.isWhitespace() }
        if (separator <= 0) return null
        return trimmed.substring(0, separator) to trimmed.substring(separator + 1).trim()
    }
}

/** Reads a managed color from the document, falling back to `null` when it is absent or unparseable. */
private fun ColorPropertiesDocument.color(key: String): Int? = get(key)?.let(::parseColor)
