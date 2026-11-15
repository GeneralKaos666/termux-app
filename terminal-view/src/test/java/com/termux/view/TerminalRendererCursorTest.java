package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;

import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Call-sequence tests for the cursor stage: the alpha and glow the cursor rect is drawn with, the paint state it
 * leaves behind for the glyphs, and the eased position of bar/underline cursors. Assertions are on recorded calls,
 * never on pixels. A block cursor also repaints its cell in the text colour (its reverse video), so the cursor rect
 * is always the last rect of its cell.
 */
public class TerminalRendererCursorTest {

    private static final float CELL_WIDTH = 10f;

    private static final class StubOutput extends TerminalOutput {
        @Override public void write(byte[] data, int offset, int count) { }
        @Override public void titleChanged(String oldTitle, String newTitle) { }
        @Override public void onCopyTextToClipboard(String text) { }
        @Override public void onPasteTextFromClipboard() { }
        @Override public void onBell() { }
        @Override public void onColorsChanged() { }
    }

    /** A paint with fixed metrics that records the cursor state changes made for the cursor rect. */
    private static class RecordingPaint extends Paint {
        int lastAlpha = 255;
        float lastShadowRadius = 0f;

        @Override public float getFontSpacing() { return 20f; }
        @Override public float ascent() { return -15f; }
        @Override public float measureText(String text) { return CELL_WIDTH; }
        @Override public float measureText(CharSequence text, int start, int end) { return CELL_WIDTH; }
        @Override public float measureText(char[] text, int index, int count) { return CELL_WIDTH * count; }

        @Override public void setAlpha(int alpha) { lastAlpha = alpha; }
        @Override public void setShadowLayer(float radius, float dx, float dy, int color) { lastShadowRadius = radius; }
        @Override public void clearShadowLayer() { lastShadowRadius = 0f; }
    }

    /** A canvas that records rect draws as {left, top, right, bottom, alpha, shadowRadius} and ignores glyphs. */
    private static final class RecordingCanvas extends Canvas {
        final List<float[]> rects = new ArrayList<>();

        @Override public void drawRect(float left, float top, float right, float bottom, Paint paint) {
            RecordingPaint recordingPaint = (RecordingPaint) paint;
            rects.add(new float[]{left, top, right, bottom, recordingPaint.lastAlpha, recordingPaint.lastShadowRadius});
        }

        @Override public void drawText(char[] text, int index, int count, float x, float y, Paint paint) { }
        @Override public void drawTextRun(char[] text, int index, int count, int contextIndex, int contextCount,
                                          float x, float y, boolean isRtl, Paint paint) { }
        @Override public int save() { return 0; }
        @Override public void restore() { }
        @Override public void scale(float sx, float sy) { }
    }

    private TerminalEmulator mTerminal;
    private RecordingPaint mPaint;
    private RecordingCanvas mCanvas;

    @Before
    public void setUp() {
        mTerminal = new TerminalEmulator(new StubOutput(), 10, 3, 10, 20, 3, null);
        mPaint = new RecordingPaint();
        mCanvas = new RecordingCanvas();
    }

    private void enter(String string) {
        byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
        mTerminal.append(bytes, bytes.length);
    }

    /** Renders row 0 with the given cursor effects; a negative eased row/column disables easing. */
    private void render(float cursorAlpha, float glowRadius, float easedCursorRow, float easedCursorCol) {
        TerminalRenderer renderer = new TerminalRenderer(14, Typeface.MONOSPACE, true, mPaint, null);
        renderer.render(mTerminal, mCanvas, 0, -1, -1, -1, -1, 0, 1,
            cursorAlpha, glowRadius, easedCursorRow, easedCursorCol);
    }

    /** The cursor rect: drawn after the cell repaint a block cursor's reverse video causes. */
    private float[] cursorRect() {
        assertTrue("the cursor rect was not drawn", !mCanvas.rects.isEmpty());
        return mCanvas.rects.get(mCanvas.rects.size() - 1);
    }

    @Test
    public void cursorRectUsesAlphaAndGlow() {
        render(0.5f, 4f, -1f, -1f);
        assertEquals(128f, cursorRect()[4], 1f);
        assertEquals(4f, cursorRect()[5], 0.01f);
        assertEquals("the glyphs after the cursor rect must stay opaque", 255, mPaint.lastAlpha);
        assertEquals("the glyphs after the cursor rect must not be haloed", 0f, mPaint.lastShadowRadius, 0.01f);
    }

    @Test
    public void cursorHiddenAtZeroAlpha() {
        render(0f, 0f, -1f, -1f);
        assertEquals(0, mCanvas.rects.size());
    }

    @Test
    public void barCursorEasesToEasedPosition() {
        enter("\033[6 q");
        render(1f, 0f, 0f, 5.5f);
        assertEquals(55f, cursorRect()[0], 0.01f);
    }

    @Test
    public void blockCursorIgnoresEasing() {
        render(1f, 0f, 0f, 5.5f);
        assertEquals(0f, cursorRect()[0], 0.01f);
    }
}
