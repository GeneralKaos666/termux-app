package com.termux.terminal;

import com.termux.terminal.kitty.KittyBase64;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class KittyBase64Test {
    @Test
    public void decodesStandardAlphabet() {
        byte[] decoded = KittyBase64.decode("AAAA");
        assertNotNull(decoded);
        assertEquals(3, decoded.length);
        assertEquals(0, decoded[0]);
        assertEquals(0, decoded[1]);
        assertEquals(0, decoded[2]);
    }

    @Test
    public void decodesWithPadding() {
        assertArrayEquals(new byte[] {'A'}, KittyBase64.decode("QQ=="));
    }

    @Test
    public void decodesPngSignature() {
        byte[] decoded = KittyBase64.decode("iVBORw0KGgo=");
        assertNotNull(decoded);
        assertEquals(8, decoded.length);
        assertEquals((byte) 0x89, decoded[0]);
        assertEquals((byte) 'P', decoded[1]);
        assertEquals((byte) 'N', decoded[2]);
        assertEquals((byte) 'G', decoded[3]);
        assertEquals((byte) 0x0d, decoded[4]);
        assertEquals((byte) 0x0a, decoded[5]);
        assertEquals((byte) 0x1a, decoded[6]);
        assertEquals((byte) 0x0a, decoded[7]);
    }

    @Test
    public void returnsNullOnInvalidCharacter() {
        assertNull(KittyBase64.decode("A!A="));
    }

    @Test
    public void returnsNullOnWhitespace() {
        assertNull(KittyBase64.decode("AA A"));
    }

    @Test
    public void returnsNullOnImpossibleLength() {
        assertNull(KittyBase64.decode("A"));
    }

    @Test
    public void returnsNullOnNull() {
        assertNull(KittyBase64.decode(null));
    }

    @Test
    public void decodesEmptyToEmpty() {
        byte[] decoded = KittyBase64.decode("");
        assertNotNull(decoded);
        assertEquals(0, decoded.length);
    }
}
