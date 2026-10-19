package com.termux.terminal;

import com.termux.terminal.kitty.KittyGraphicsCommand;
import com.termux.terminal.kitty.KittyGraphicsParser;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class KittyGraphicsSizeTest {
    @Test
    public void rgbSize() {
        KittyGraphicsCommand cmd = KittyGraphicsParser.parse("Ga=T,f=24,s=10,v=20;", null);
        assertEquals(600L, cmd.expectedPayloadBytes());
    }

    @Test
    public void rgbaSize() {
        KittyGraphicsCommand cmd = KittyGraphicsParser.parse("Ga=T,f=32,s=10,v=20;", null);
        assertEquals(800L, cmd.expectedPayloadBytes());
    }

    @Test
    public void pngSizeUnknown() {
        KittyGraphicsCommand cmd = KittyGraphicsParser.parse("Ga=T,f=100,s=10,v=20;", null);
        assertEquals(-1L, cmd.expectedPayloadBytes());
    }

    @Test
    public void unknownWhenDimensionsMissing() {
        KittyGraphicsCommand cmd = KittyGraphicsParser.parse("Ga=T,f=32;", null);
        assertEquals(-1L, cmd.expectedPayloadBytes());
    }

    @Test
    public void unknownForErrorCommand() {
        KittyGraphicsCommand cmd = KittyGraphicsParser.parse("Gq;", null);
        assertEquals(-1L, cmd.expectedPayloadBytes());
    }
}
