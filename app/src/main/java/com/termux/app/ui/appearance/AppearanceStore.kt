package com.termux.app.ui.appearance

import java.io.File
import java.io.IOException

/**
 * App-local settings the appearance screen owns.
 *
 * Font size already lives in the app's shared preferences. Cursor style and blink rate stay
 * app-local (a `termux.properties` value is only the fallback) so `:termux-shared` needs no change;
 * an implementation therefore returns the *effective* value: the app preference when one is set,
 * the property value otherwise, or `null` when neither exists.
 */
interface AppearancePreferences {

    fun getFontSize(): Int

    fun setFontSize(size: Int)

    fun getCursorStyle(): Int?

    fun setCursorStyle(style: Int?)

    fun getCursorBlinkRate(): Int?

    fun setCursorBlinkRate(rate: Int?)
}

/** Why a candidate font was not installed. */
enum class FontError { NOT_FOUND, INVALID, TOO_LARGE, COPY_FAILED }

/**
 * Outcome of [AppearanceStore.write]. [fontInstalled] says whether any custom font is in place when
 * the write returns; [fontError] says why a candidate was rejected, if it was.
 */
data class AppearanceWriteResult(val fontInstalled: Boolean, val fontError: FontError?)

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
) {

    /** Reads the effective appearance, falling back to the Termux default scheme. */
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
            cursorStyle = CursorStyle.fromValue(preferences.getCursorStyle()),
            cursorBlinkRate = CursorBlink.sanitize(preferences.getCursorBlinkRate() ?: 0),
        )
    }

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
        preferences.setCursorStyle(appearance.cursorStyle.value)
        preferences.setCursorBlinkRate(CursorBlink.sanitize(appearance.cursorBlinkRate))

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

    private fun installFont(source: File): Boolean = try {
        source.copyTo(fontFile, overwrite = true)
        true
    } catch (e: IOException) {
        false
    }

    private companion object {
        const val BACKGROUND_BRIGHTNESS_THRESHOLD = 130
        const val CURSOR_ON_DARK = 0xffffffff.toInt()
        const val CURSOR_ON_LIGHT = 0xff000000.toInt()
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
