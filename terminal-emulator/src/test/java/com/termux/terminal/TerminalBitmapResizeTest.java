package com.termux.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class TerminalBitmapResizeTest {

    @Test
    public void thePixelBufferHoldsOneIntPerPixel() {
        assertEquals(16, TerminalBitmap.pixelBufferLength(4, 4));
        assertEquals(1, TerminalBitmap.pixelBufferLength(1, 1));
    }

    @Test
    public void theSizeCheckUsesLongArithmetic() {
        // 32768 * 32768 * 4 overflows int to 0, so an int check would accept a 4 GiB bitmap.
        assertTrue(TerminalBitmap.requiredBitmapBytes(32768, 32768) > TerminalBitmap.MAX_BITMAP_SIZE);
        assertEquals(64L, TerminalBitmap.requiredBitmapBytes(4, 4));
    }

    @Test
    public void growthKeepsTheSourcePixels() {
        Bitmap source = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        source.setPixel(0, 0, 0xFFFF0000);
        Bitmap resized = TerminalBitmap.resizeBitmap("test", "bitmap", null, source, 8, 8);
        assertNotNull(resized);
        assertEquals(8, resized.getWidth());
        assertEquals(8, resized.getHeight());
        assertEquals(0xFFFF0000, resized.getPixel(0, 0));
    }

    @Test
    public void anOversizedTargetIsRejectedWithoutThrowing() {
        Bitmap source = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        assertNull(TerminalBitmap.resizeBitmap("test", "bitmap", null, source, 32768, 32768));
    }
}
