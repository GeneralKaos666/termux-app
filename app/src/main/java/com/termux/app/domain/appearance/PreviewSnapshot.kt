package com.termux.app.domain.appearance

/** CSI sequences: colour, cursor movement and the rest of the `ESC [ … final-byte` family. */
private val CSI_SEQUENCE = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

/** OSC sequences: window titles and similar `ESC ] … BEL` (or `ESC \`) strings. */
private val OSC_SEQUENCE = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")

/** Any remaining two-byte escape, so no control byte reaches the preview text. */
private val OTHER_ESCAPE = Regex("\u001B[@-_]")

/** The ellipsis appended to a line elided at [buildPreviewLines]'s `maxColumns`. */
private const val ELLIPSIS = "…"

/**
 * Turns a terminal transcript into the few lines the appearance preview shows.
 *
 * The transcript is the emulator's rendered text, so it still carries the escape sequences the
 * program emitted. They are stripped here rather than in the composable: the preview shows text,
 * not styling, and the staged colour scheme supplies the colours.
 *
 * Blank lines at the top and bottom are dropped (an empty terminal should preview as empty), but a
 * blank line between two non-blank ones is kept, because it is part of what the user sees.
 */
@JvmOverloads
fun buildPreviewLines(transcript: String?, maxLines: Int = 14, maxColumns: Int = 60): List<String> {
    if (transcript == null || maxLines <= 0) return emptyList()

    val lines = transcript
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lineSequence()
        .map { line -> stripSequences(line).trimEnd() }
        .toList()

    val first = lines.indexOfFirst { it.isNotBlank() }
    if (first < 0) return emptyList()
    val last = lines.indexOfLast { it.isNotBlank() }

    return lines.subList(first, last + 1)
        .takeLast(maxLines)
        .map { line -> elide(line, maxColumns) }
}

private fun stripSequences(line: String): String =
    line.replace(OSC_SEQUENCE, "")
        .replace(CSI_SEQUENCE, "")
        .replace(OTHER_ESCAPE, "")

private fun elide(line: String, maxColumns: Int): String {
    if (maxColumns <= 0) return ""
    if (line.length <= maxColumns) return line
    if (maxColumns == 1) return ELLIPSIS
    return line.take(maxColumns - 1) + ELLIPSIS
}
