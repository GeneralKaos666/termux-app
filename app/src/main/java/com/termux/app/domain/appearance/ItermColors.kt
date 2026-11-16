package com.termux.app.domain.appearance

import java.io.StringReader
import java.util.Locale
import kotlin.math.roundToInt
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/** The iTerm2 key for ANSI colour [index]. */
private fun ansiKey(index: Int): String = "Ansi $index Color"

private const val KEY_FOREGROUND = "Foreground Color"
private const val KEY_BACKGROUND = "Background Color"
private const val KEY_CURSOR = "Cursor Color"

private const val COMPONENT_RED = "Red Component"
private const val COMPONENT_GREEN = "Green Component"
private const val COMPONENT_BLUE = "Blue Component"

/**
 * Reads an iTerm2 `.itermcolors` plist.
 *
 * The file carries the 16 ANSI colours plus foreground, background and cursor; the remaining 240
 * indexed colours are not part of the format, so they take the engine's default extended palette.
 * A file missing any of the 16 ANSI colours or the foreground/background is rejected whole rather
 * than half-applied, and a component outside 0..1 makes the file invalid: a preset that is partly
 * wrong would silently repaint the terminal with the wrong colours.
 *
 * @return the parsed set, or `null` when the input is not a complete, in-range iTerm colour file.
 */
fun parseItermColors(xml: String): TerminalColorSet? = try {
    val parser = XmlPullParserFactory.newInstance().newPullParser()
    parser.setInput(StringReader(xml))
    val entries = readColorEntries(parser)

    val ansi = (0 until ANSI_COLOR_COUNT).map { index ->
        entries[ansiKey(index)] ?: return null
    }
    val foreground = entries[KEY_FOREGROUND] ?: return null
    val background = entries[KEY_BACKGROUND] ?: return null
    val cursor = entries[KEY_CURSOR] ?: foreground

    TerminalColorSet(
        foreground = foreground,
        background = background,
        cursor = cursor,
        ansi = ansi + ColorSchemes.defaultExtendedPalette(),
    )
} catch (t: Throwable) {
    // XmlPullParser throws several exception types for bad input; all of them mean "not a file we
    // can import", and the caller shows the same message.
    null
}

/** The 16 ANSI colours plus foreground, background and cursor, in iTerm's plist shape. */
fun exportItermColors(colors: TerminalColorSet): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    append("<plist version=\"1.0\">\n<dict>\n")
    for (index in 0 until ANSI_COLOR_COUNT) {
        appendColorEntry(ansiKey(index), colors.ansi[index])
    }
    appendColorEntry(KEY_BACKGROUND, colors.background)
    appendColorEntry(KEY_CURSOR, colors.cursor)
    appendColorEntry(KEY_FOREGROUND, colors.foreground)
    append("</dict>\n</plist>\n")
}

private fun StringBuilder.appendColorEntry(key: String, color: Int) {
    append("\t<key>").append(key).append("</key>\n")
    append("\t<dict>\n")
    appendComponent(COMPONENT_RED, color ushr 16 and 0xff)
    appendComponent(COMPONENT_GREEN, color ushr 8 and 0xff)
    appendComponent(COMPONENT_BLUE, color and 0xff)
    append("\t</dict>\n")
}

private fun StringBuilder.appendComponent(key: String, channel: Int) {
    append("\t\t<key>").append(key).append("</key>\n")
    append("\t\t<real>")
    append(String.format(Locale.ROOT, "%.6f", channel / 255.0))
    append("</real>\n")
}

/** Reads every `key -> colour dict` pair of the top-level dict; non-colour entries are skipped. */
private fun readColorEntries(parser: XmlPullParser): Map<String, Int> {
    val entries = HashMap<String, Int>()
    parser.nextTag() // <plist>
    parser.nextTag() // the top-level <dict>
    while (parser.nextTag() == XmlPullParser.START_TAG) {
        if (parser.name != "key") {
            skipElement(parser)
            continue
        }
        val key = parser.nextText()
        if (parser.nextTag() == XmlPullParser.START_TAG && parser.name == "dict") {
            val color = readColorDict(parser)
            if (color != null) entries[key] = color
        } else {
            skipElement(parser)
        }
    }
    return entries
}

/** Reads one colour dict's three components; `null` when one is missing or outside 0..1. */
private fun readColorDict(parser: XmlPullParser): Int? {
    val components = HashMap<String, Int>()
    while (parser.nextTag() == XmlPullParser.START_TAG) {
        if (parser.name != "key") {
            skipElement(parser)
            continue
        }
        val key = parser.nextText()
        if (parser.nextTag() == XmlPullParser.START_TAG && parser.name == "real") {
            val value = parser.nextText().trim().toDoubleOrNull()
            if (value == null || value < 0.0 || value > 1.0) return null
            components[key] = (value * 255.0).roundToInt()
        } else {
            skipElement(parser)
        }
    }
    val red = components[COMPONENT_RED] ?: return null
    val green = components[COMPONENT_GREEN] ?: return null
    val blue = components[COMPONENT_BLUE] ?: return null
    return 0xff000000.toInt() or (red shl 16) or (green shl 8) or blue
}

/** Skips the element the parser is on, including any children. */
private fun skipElement(parser: XmlPullParser) {
    if (parser.eventType != XmlPullParser.START_TAG) return
    var depth = 1
    while (depth > 0) {
        when (parser.next()) {
            XmlPullParser.START_TAG -> depth++
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.END_DOCUMENT -> return
        }
    }
}
