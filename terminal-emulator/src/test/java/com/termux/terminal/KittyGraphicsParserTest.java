package com.termux.terminal;

import com.termux.terminal.kitty.KittyGraphicsCommand;
import com.termux.terminal.kitty.KittyGraphicsParser;

import org.junit.Test;
import static org.junit.Assert.*;

public class KittyGraphicsParserTest {
    @Test
    public void parseStoreAndDisplay() {
        KittyGraphicsCommand cmd = KittyGraphicsParser.parse("Ga=T,f=100,s=10,v=20;", null);
        assertTrue(cmd.isOk());
        assertEquals('T', cmd.action);
        assertEquals(100, cmd.format);
        assertEquals(10, cmd.width);
        assertEquals(20, cmd.height);
    }

    @Test
    public void rejectImageNumber() {
        KittyGraphicsCommand cmd = KittyGraphicsParser.parse("Ga=T,I=5;", null);
        assertFalse(cmd.isOk());
        assertEquals("EINVAL", cmd.errorCode);
    }

    @Test
    public void rejectNonDirectTransmission() {
        KittyGraphicsCommand cmd = KittyGraphicsParser.parse("Ga=T,t=f;", null);
        assertFalse(cmd.isOk());
        assertEquals("EINVAL", cmd.errorCode);
    }

    @Test
    public void queryRequiresImageId() {
        KittyGraphicsCommand cmd = KittyGraphicsParser.parse("Gq;", null);
        assertFalse(cmd.isOk());
        assertEquals("EINVAL", cmd.errorCode);
    }

    @Test
    public void queryWithImageIdOk() {
        KittyGraphicsCommand cmd = KittyGraphicsParser.parse("Ga=q,i=5;", null);
        assertTrue(cmd.isOk());
        assertEquals('q', cmd.action);
        assertEquals(5, cmd.imageId);
    }

    @Test
    public void rejectUnsupportedDelete() {
        KittyGraphicsCommand cmd = KittyGraphicsParser.parse("Ga=d,d=Z;", null);
        assertFalse(cmd.isOk());
    }

    @Test
    public void formatReply() {
        String r = KittyGraphicsParser.formatReply(5, 0, true, null, null);
        assertEquals("\033_Gi=5;OK\033\\", r);
        String r2 = KittyGraphicsParser.formatReply(5, 0, false, "ENOENT", "not found");
        assertEquals("\033_Gi=5;ENOENT:not found\033\\", r2);
    }
}
