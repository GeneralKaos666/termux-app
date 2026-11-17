package com.termux.view

import java.util.Locale

/**
 * Scheme allow-list sanitization for URIs arriving from untrusted terminal output (OSC 8).
 *
 * The URI a remote shell, a script or a multiplexer passes to `OSC 8` is attacker-controlled
 * input. The contract handed to consumers (and to the `ACTION_VIEW` intents they build) is
 * "a URI this app may open", so the allow-list lives here, before any callback — every implementor
 * gets the safe set by construction.
 */
object LinkUris {

    /** The schemes a hyperlink may use, lowercase. Everything else is dropped. */
    private val ALLOWED_PREFIXES = arrayOf("http://", "https://", "ftp://", "mailto:")

    /**
     * @param uri the raw URI from a hyperlink cell, may be `null`.
     * @return the trimmed URI when its scheme is in the allow-list (case-insensitive prefix match,
     * no embedded control characters), otherwise `null`. Never throws.
     */
    @JvmStatic
    fun sanitize(uri: String?): String? {
        if (uri == null) return null
        val trimmed = uri.trim()
        if (trimmed.isEmpty()) return null
        for (c in trimmed) {
            if (c < ' ' || c == '\u007f') return null
        }
        val lower = trimmed.lowercase(Locale.ROOT)
        for (prefix in ALLOWED_PREFIXES) {
            if (lower.startsWith(prefix)) return trimmed
        }
        return null
    }
}
