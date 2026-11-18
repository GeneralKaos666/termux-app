package com.termux.app.domain.backup

import org.json.JSONException
import org.json.JSONObject

/** One line of `SHA256SUMS`: a lowercase hex digest and a plain file name. */
data class SumEntry(val name: String, val sha256: String)

/** An imported directory that is not a usable backup. */
class InvalidBackupException(message: String) : Exception(message)

/**
 * Parses the files an exported backup directory carries: `SHA256SUMS` and `manifest.json`.
 * Validation is strict because the directory comes from a foreign provider.
 */
object SafBackupManifest {

    private val SUM_LINE = Regex("^([0-9a-f]{64}) {2}([^/\\\\]+)$")

    /** Parses `SHA256SUMS`, rejecting traversal, nested paths, duplicates and empty input. */
    fun parseSums(text: String): List<SumEntry> {
        val entries = mutableListOf<SumEntry>()
        val seen = mutableSetOf<String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val match = SUM_LINE.matchEntire(line)
                ?: throw InvalidBackupException("malformed checksum line: $line")
            val name = match.groupValues[2]
            if (name == "." || name == ".." || name.startsWith("../")) {
                throw InvalidBackupException("illegal file name: $name")
            }
            if (!seen.add(name)) throw InvalidBackupException("duplicate file name: $name")
            entries += SumEntry(name, match.groupValues[1])
        }
        if (entries.isEmpty()) throw InvalidBackupException("no checksums found")
        return entries
    }

    /** `format_version` of the manifest, or null when unreadable. */
    fun formatVersion(json: String?): Int? = opt(json)?.takeIf { it.has("format_version") }?.optInt("format_version")

    /** `created_at` of the manifest, or null when unreadable. */
    fun createdAt(json: String?): String? = opt(json)
        ?.takeIf { it.has("created_at") }
        ?.optString("created_at")
        ?.takeIf { it.isNotEmpty() }

    private fun opt(json: String?): JSONObject? {
        if (json == null) return null
        return try {
            JSONObject(json)
        } catch (_: JSONException) {
            null
        }
    }
}
