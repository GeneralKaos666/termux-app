package com.termux.view;

import java.util.Locale;

/**
 * Scheme allow-list sanitization for URIs arriving from untrusted terminal output (OSC 8).
 *
 * <p>The URI a remote shell, a script or a multiplexer passes to {@code OSC 8} is attacker-controlled
 * input. The contract handed to consumers (and to the {@code ACTION_VIEW} intents they build) is
 * "a URI this app may open", so the allow-list lives here, before any callback — every implementor
 * gets the safe set by construction.
 */
public final class LinkUris {

    /** The schemes a hyperlink may use, lowercase. Everything else is dropped. */
    private static final String[] ALLOWED_PREFIXES = {"http://", "https://", "ftp://", "mailto:"};

    private LinkUris() {
    }

    /**
     * @param uri the raw URI from a hyperlink cell, may be {@code null}.
     * @return the trimmed URI when its scheme is in the allow-list (case-insensitive prefix match,
     * no embedded control characters), otherwise {@code null}. Never throws.
     */
    public static String sanitize(String uri) {
        if (uri == null) return null;
        String trimmed = uri.trim();
        if (trimmed.isEmpty()) return null;
        for (int i = 0, len = trimmed.length(); i < len; i++) {
            char c = trimmed.charAt(i);
            if (c < 0x20 || c == 0x7f) return null;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (String prefix : ALLOWED_PREFIXES) {
            if (lower.startsWith(prefix)) return trimmed;
        }
        return null;
    }
}
