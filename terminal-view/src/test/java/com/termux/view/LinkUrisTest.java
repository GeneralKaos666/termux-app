package com.termux.view;

import junit.framework.TestCase;

/**
 * Unit tests for {@link LinkUris}, the scheme allow-list applied to URIs arriving from untrusted
 * terminal output (OSC 8) before they reach {@code TerminalViewClient.onLinkClicked}.
 */
public class LinkUrisTest extends TestCase {

    public void testAcceptsAllowedSchemes() {
        assertEquals("http://example.com", LinkUris.sanitize("http://example.com"));
        assertEquals("https://example.com", LinkUris.sanitize("https://example.com"));
        assertEquals("ftp://example.com/pub", LinkUris.sanitize("ftp://example.com/pub"));
        assertEquals("mailto:someone@example.com", LinkUris.sanitize("mailto:someone@example.com"));
    }

    public void testSchemeMatchIsCaseInsensitive() {
        assertEquals("HTTPS://EXAMPLE.COM", LinkUris.sanitize("HTTPS://EXAMPLE.COM"));
        assertEquals("MailTo:a@b.c", LinkUris.sanitize("MailTo:a@b.c"));
    }

    public void testTrimsSurroundingWhitespace() {
        assertEquals("http://example.com", LinkUris.sanitize("  http://example.com \n"));
    }

    public void testRejectsDisallowedSchemes() {
        assertNull(LinkUris.sanitize("file:///etc/passwd"));
        assertNull(LinkUris.sanitize("intent://scan/#Intent;scheme=zxing;end"));
        assertNull(LinkUris.sanitize("javascript:alert(1)"));
        assertNull(LinkUris.sanitize("data:text/html;base64,PHNjcmlwdD4="));
    }

    public void testRejectsSchemelessStrings() {
        assertNull(LinkUris.sanitize("example.com"));
        assertNull(LinkUris.sanitize("/etc/passwd"));
    }

    public void testRejectsEmbeddedControlCharacters() {
        assertNull(LinkUris.sanitize("http://exam\tple.com"));
        assertNull(LinkUris.sanitize("http://example.com/\nnext"));
    }

    public void testNullAndEmpty() {
        assertNull(LinkUris.sanitize(null));
        assertNull(LinkUris.sanitize(""));
        assertNull(LinkUris.sanitize("   "));
    }
}
