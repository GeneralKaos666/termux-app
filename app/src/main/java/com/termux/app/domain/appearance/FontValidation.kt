package com.termux.app.domain.appearance

import java.io.File
import java.io.IOException

/** Why a candidate font was not installed. */
enum class FontError { NOT_FOUND, INVALID, TOO_LARGE, COPY_FAILED }

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
internal fun ColorPropertiesDocument.color(key: String): Int? = get(key)?.let(::parseColor)
