package com.termux.app.api.file

import java.io.File
import java.io.IOException

/**
 * Returns [raw] only when it names one file directly inside a directory. The name arrives from the
 * sharing app, so separators, `.`, `..` and control characters are refused rather than sanitised:
 * a silently rewritten name is worse than an error dialog.
 */
internal fun safeReceivedFileName(raw: String?): String? {
    val name = raw?.trim() ?: return null
    if (name.isEmpty() || name == "." || name == "..") return null
    if (name.any { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7f }) return null
    return name
}

/** True when [candidate] is a direct child of [directory] after canonicalisation. */
internal fun isDirectChildOf(directory: File, candidate: File): Boolean =
    try {
        candidate.canonicalFile.parentFile == directory.canonicalFile
    } catch (e: IOException) {
        false
    }
