package com.termux.terminal;

import com.termux.terminal.kitty.KittyGraphicsCommand;
import com.termux.terminal.kitty.KittyGraphicsParser;
import com.termux.terminal.kitty.KittyGraphicsTransfer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class KittyGraphicsTransferTest {
    @Test
    public void nonChunkedCompletesImmediately() {
        KittyGraphicsCommand cmd = KittyGraphicsParser.parse("Ga=T,f=100,s=10,v=20;", null);
        KittyGraphicsTransfer transfer = new KittyGraphicsTransfer();
        KittyGraphicsTransfer.Result result = transfer.accept(cmd);
        assertTrue(result.complete);
        assertEquals(cmd, result.command);
        assertNull(result.errorCode);
        assertFalse(transfer.isActive());
    }

    @Test
    public void twoChunksConcatenatePayload() {
        KittyGraphicsCommand c1 = KittyGraphicsParser.parse("Ga=T,i=1,s=1,v=1,f=32,m=1;", "AAA");
        KittyGraphicsCommand c2 = KittyGraphicsParser.parse("Gm=0;", "AAA");
        KittyGraphicsTransfer transfer = new KittyGraphicsTransfer();
        KittyGraphicsTransfer.Result r1 = transfer.accept(c1);
        assertFalse(r1.complete);
        assertNull(r1.command);
        assertTrue(transfer.isActive());
        KittyGraphicsTransfer.Result r2 = transfer.accept(c2);
        assertTrue(r2.complete);
        assertNotNull(r2.command);
        assertEquals("AAAAAA", r2.command.payload);
        assertEquals(1, r2.command.imageId);
        assertEquals('T', r2.command.action);
        assertFalse(transfer.isActive());
    }

    @Test
    public void threeChunksConcatenatePayload() {
        KittyGraphicsCommand c1 = KittyGraphicsParser.parse("Ga=T,i=1,s=1,v=1,f=32,m=1;", "AA");
        KittyGraphicsTransfer transfer = new KittyGraphicsTransfer();
        KittyGraphicsTransfer.Result r1 = transfer.accept(c1);
        assertFalse(r1.complete);
        assertNull(r1.errorCode);
        assertTrue(transfer.isActive());
        KittyGraphicsCommand c2 = KittyGraphicsParser.parse("Gm=1;", "AA");
        KittyGraphicsTransfer.Result r2 = transfer.accept(c2);
        assertFalse(r2.complete);
        assertNull(r2.errorCode);
        assertTrue(transfer.isActive());
        KittyGraphicsCommand c3 = KittyGraphicsParser.parse("Gm=0;", "AA");
        KittyGraphicsTransfer.Result r3 = transfer.accept(c3);
        assertTrue(r3.complete);
        assertNotNull(r3.command);
        assertEquals("AAAAAA", r3.command.payload);
        assertEquals(1, r3.command.imageId);
        assertEquals('T', r3.command.action);
        assertFalse(transfer.isActive());
    }

    @Test
    public void middleChunkOverBudgetErrors() {
        // f=32, s=1, v=1 expects 4 bytes = 8 base64 chars; the first chunk is already at the cap,
        // so a further m=1 continuation must be refused without waiting for the final chunk.
        KittyGraphicsCommand c1 = KittyGraphicsParser.parse("Ga=T,f=32,s=1,v=1,m=1;", "AAAAAAAA");
        KittyGraphicsTransfer transfer = new KittyGraphicsTransfer();
        transfer.accept(c1);
        assertTrue(transfer.isActive());
        KittyGraphicsCommand c2 = KittyGraphicsParser.parse("Gm=1;", "AAAA");
        KittyGraphicsTransfer.Result r2 = transfer.accept(c2);
        assertFalse(r2.complete);
        assertEquals("EINVAL", r2.errorCode);
        assertFalse(transfer.isActive());
    }

    @Test
    public void laterChunkWithKeysOtherThanMOrQErrors() {
        KittyGraphicsCommand c1 = KittyGraphicsParser.parse("Ga=T,i=1,s=1,v=1,f=32,m=1;", "AA");
        KittyGraphicsCommand c2 = KittyGraphicsParser.parse("Ga=T,i=1,m=0;", "AA");
        KittyGraphicsTransfer transfer = new KittyGraphicsTransfer();
        transfer.accept(c1);
        KittyGraphicsTransfer.Result r2 = transfer.accept(c2);
        assertFalse(r2.complete);
        assertEquals("EINVAL", r2.errorCode);
        assertFalse(transfer.isActive());
    }

    @Test
    public void laterChunkWithDifferentImageIdErrors() {
        KittyGraphicsCommand c1 = KittyGraphicsParser.parse("Ga=T,i=1,s=1,v=1,f=32,m=1;", "AA");
        KittyGraphicsCommand c2 = KittyGraphicsParser.parse("Gi=2,m=0;", "AA");
        KittyGraphicsTransfer transfer = new KittyGraphicsTransfer();
        transfer.accept(c1);
        KittyGraphicsTransfer.Result r2 = transfer.accept(c2);
        assertFalse(r2.complete);
        assertEquals("EINVAL", r2.errorCode);
        assertFalse(transfer.isActive());
    }

    @Test
    public void oversizedAccumulatedPayloadErrors() {
        // f=32, s=1, v=1 expects 4 bytes = 8 base64 chars.
        KittyGraphicsCommand c1 = KittyGraphicsParser.parse("Ga=T,f=32,s=1,v=1,m=1;", "AAAAAAAA"); // 8 chars
        KittyGraphicsTransfer transfer = new KittyGraphicsTransfer();
        KittyGraphicsTransfer.Result r1 = transfer.accept(c1);
        assertFalse(r1.complete);
        assertTrue(transfer.isActive());
        KittyGraphicsCommand c2 = KittyGraphicsParser.parse("Gm=0;", "AAAA"); // pushes over
        KittyGraphicsTransfer.Result r2 = transfer.accept(c2);
        assertFalse(r2.complete);
        assertEquals("EINVAL", r2.errorCode);
        assertFalse(transfer.isActive());
    }

    @Test
    public void secondStartChunkWhileActiveErrorsAndResets() {
        KittyGraphicsCommand c1 = KittyGraphicsParser.parse("Ga=T,i=1,s=1,v=1,f=32,m=1;", "AA");
        KittyGraphicsTransfer transfer = new KittyGraphicsTransfer();
        transfer.accept(c1);
        assertTrue(transfer.isActive());
        KittyGraphicsCommand c2 = KittyGraphicsParser.parse("Ga=T,i=2,s=1,v=1,f=32,m=1;", "AA");
        KittyGraphicsTransfer.Result r2 = transfer.accept(c2);
        assertFalse(r2.complete);
        assertEquals("EINVAL", r2.errorCode);
        assertFalse(transfer.isActive());
    }

    @Test
    public void strayFinalChunkCompletesImmediately() {
        KittyGraphicsCommand c = KittyGraphicsParser.parse("Gm=0;", "AA");
        KittyGraphicsTransfer transfer = new KittyGraphicsTransfer();
        KittyGraphicsTransfer.Result r = transfer.accept(c);
        assertTrue(r.complete);
        assertNotNull(r.command);
        assertEquals("AA", r.command.payload);
        assertFalse(transfer.isActive());
    }

    @Test
    public void resetClearsActiveTransfer() {
        KittyGraphicsCommand c1 = KittyGraphicsParser.parse("Ga=T,i=1,s=1,v=1,f=32,m=1;", "AA");
        KittyGraphicsTransfer transfer = new KittyGraphicsTransfer();
        transfer.accept(c1);
        assertTrue(transfer.isActive());
        transfer.reset();
        assertFalse(transfer.isActive());
    }
}
