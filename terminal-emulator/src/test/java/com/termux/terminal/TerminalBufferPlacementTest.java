package com.termux.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Rect;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

/**
 * Tests for the unified placement registry that kitty graphics, sixel and iTerm2 share.
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class TerminalBufferPlacementTest {

    private static Bitmap bitmap(int width, int height) {
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    }

    private static boolean contains(int[] values, int value) {
        for (int v : values) {
            if (v == value) return true;
        }
        return false;
    }

    @Test
    public void placeRegistersImageAndReturnsBitmapNum() {
        TerminalBuffer buffer = new TerminalBuffer(10, 10, 4);

        int[] result = buffer.placeTerminalBitmap(bitmap(20, 40), 5, 1, 0, 20, 40, 0, 0);

        assertTrue(result[0] >= TerminalBuffer.TERMINAL_BITMAP__NUM_START);
        assertEquals(0, result[1]);
        assertTrue(TextStyle.isTerminalBitmap(buffer.getStyleAt(0, 1)));
        assertTrue(contains(buffer.getImagePlacements(5), result[0]));
    }

    @Test
    public void offsetRectsAreClampedToTheBitmap() {
        TerminalBuffer buffer = new TerminalBuffer(10, 10, 4);
        buffer.placeTerminalBitmap(bitmap(60, 40), 5, 0, 0, 20, 40, 5, 5);

        // Local cell (1, 0): the 5px origin offset shifts the source rect left/up and clamps at 0.
        Rect rect = buffer.getSixelRect(buffer.getStyleAt(0, 1));

        assertEquals(1 * 20 - 5, rect.left);
        assertEquals(0, rect.top);
        assertEquals(2 * 20 - 5, rect.right);
        assertEquals(1 * 40 - 5, rect.bottom);
    }

    @Test
    public void zeroOffsetsMatchTheOldBehaviour() {
        TerminalBuffer buffer = new TerminalBuffer(10, 10, 4);
        buffer.placeTerminalBitmap(bitmap(40, 40), 5, 0, 0, 20, 40, 0, 0);

        Rect first = buffer.getSixelRect(buffer.getStyleAt(0, 0));
        Rect second = buffer.getSixelRect(buffer.getStyleAt(0, 1));

        assertEquals(new Rect(0, 0, 20, 40), first);
        assertEquals(new Rect(20, 0, 40, 40), second);
    }

    @Test
    public void deleteByImageIdBlanksPlaceholderCells() {
        TerminalBuffer buffer = new TerminalBuffer(10, 10, 4);
        buffer.placeTerminalBitmap(bitmap(20, 40), 5, 1, 0, 20, 40, 0, 0);
        long style = buffer.getStyleAt(0, 1);

        buffer.deleteTerminalBitmaps(5, 0, 'i', 0, 0);

        assertFalse(TextStyle.isTerminalBitmap(buffer.getStyleAt(0, 1)));
        assertTrue(buffer.getSelectedText(1, 0, 1, 0).trim().isEmpty());
        assertNull(buffer.getTerminalBitmap(style));
        assertEquals(0, buffer.getImagePlacements(5).length);
    }

    @Test
    public void deleteAllClearsEveryImage() {
        TerminalBuffer buffer = new TerminalBuffer(10, 10, 4);
        buffer.placeTerminalBitmap(bitmap(20, 40), 5, 1, 0, 20, 40, 0, 0);
        buffer.placeTerminalBitmap(bitmap(20, 40), 6, 5, 0, 20, 40, 0, 0);
        long styleA = buffer.getStyleAt(0, 1);
        long styleB = buffer.getStyleAt(0, 5);

        buffer.deleteTerminalBitmaps(0, 0, 'a', 0, 0);

        assertNull(buffer.getTerminalBitmap(styleA));
        assertNull(buffer.getTerminalBitmap(styleB));
        assertEquals(0, buffer.getImagePlacements(5).length);
        assertEquals(0, buffer.getImagePlacements(6).length);
        assertFalse(TextStyle.isTerminalBitmap(buffer.getStyleAt(0, 1)));
        assertFalse(TextStyle.isTerminalBitmap(buffer.getStyleAt(0, 5)));
    }

    @Test
    public void deleteAtCursorHitsOnlyIntersectingPlacements() {
        TerminalBuffer buffer = new TerminalBuffer(10, 10, 4);
        buffer.placeTerminalBitmap(bitmap(40, 40), 1, 0, 0, 20, 40, 0, 0); // covers columns 0..1
        int[] b = buffer.placeTerminalBitmap(bitmap(20, 40), 2, 5, 0, 20, 40, 0, 0); // column 5

        buffer.deleteTerminalBitmaps(0, 0, 'c', 0, 0);

        assertEquals(0, buffer.getImagePlacements(1).length);
        assertTrue(contains(buffer.getImagePlacements(2), b[0]));
        assertFalse(TextStyle.isTerminalBitmap(buffer.getStyleAt(0, 0)));
        assertTrue(TextStyle.isTerminalBitmap(buffer.getStyleAt(0, 5)));
    }

    @Test
    public void deleteMissingImageIsANoOp() {
        TerminalBuffer buffer = new TerminalBuffer(10, 10, 4);
        int[] result = buffer.placeTerminalBitmap(bitmap(20, 40), 5, 1, 0, 20, 40, 0, 0);

        buffer.deleteTerminalBitmaps(99, 0, 'i', 0, 0);

        assertTrue(contains(buffer.getImagePlacements(5), result[0]));
        assertTrue(TextStyle.isTerminalBitmap(buffer.getStyleAt(0, 1)));
    }

    @Test
    public void placementAtLastRowScrolls() {
        TerminalBuffer buffer = new TerminalBuffer(10, 10, 3);
        int transcriptBefore = buffer.getActiveTranscriptRows();

        int[] result = buffer.placeTerminalBitmap(bitmap(20, 80), 5, 0, 2, 20, 40, 0, 0);

        assertEquals(1, result[1]);
        assertEquals(transcriptBefore + 1, buffer.getActiveTranscriptRows());
        assertTrue(TextStyle.isTerminalBitmap(buffer.getStyleAt(2, 0)));
    }

    @Test
    public void transcriptScanFindsStampedCellsInHistory() {
        TerminalBuffer buffer = new TerminalBuffer(10, 10, 4);
        buffer.placeTerminalBitmap(bitmap(20, 40), 5, 1, 0, 20, 40, 0, 0);

        buffer.scrollDownOneLine(0, buffer.mScreenRows, TextStyle.NORMAL);

        assertTrue(TextStyle.isTerminalBitmap(buffer.getStyleAt(-1, 1)));
        buffer.deleteTerminalBitmaps(5, 0, 'i', 0, 0);
        assertFalse(TextStyle.isTerminalBitmap(buffer.getStyleAt(-1, 1)));
        assertEquals(0, buffer.getImagePlacements(5).length);
    }
}
