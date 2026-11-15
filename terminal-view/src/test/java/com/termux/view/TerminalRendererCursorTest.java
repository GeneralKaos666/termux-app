package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TextStyle;

import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Call-sequence tests for the cursor stage: the colours and alpha the cursor is drawn with, the halo, the eased
 * position of bar/underline cursors, and the paint state left behind for the glyphs. Assertions are on recorded
 * calls, never on pixels.
 *
 * <p>A block cursor repaints its whole cell (the block colour plus an inverted glyph), so its fade is asserted on
 * the cell's colours rather than on a translucent overlay rect: an overlay cannot fade a cell that has already
 * been painted opaque.
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

    /** A paint with fixed metrics that records the state the cursor and glyph draws leave behind. */
    private static class RecordingPaint extends Paint {
        int lastAlpha = 255;
        int lastColor = 0;
        int shadowCalls = 0;

        @Override public float getFontSpacing() { return 20f; }
        @Override public float ascent() { return -15f; }
        @Override public float measureText(String text) { return CELL_WIDTH; }
        @Override public float measureText(CharSequence text, int start, int end) { return CELL_WIDTH; }
        @Override public float measureText(char[] text, int index, int count) { return CELL_WIDTH * count; }

        @Override public void setAlpha(int alpha) { lastAlpha = alpha; }
        @Override public void setColor(int color) { lastColor = color; }
        @Override public void setShadowLayer(float radius, float dx, float dy, int color) { shadowCalls++; }
        @Override public void clearShadowLayer() { }
    }

    /**
     * Records rects as {@code {left, top, right, bottom, color, alpha}} and glyph draws as
     * {@code {x, color, alpha}}, in call order. {@link #order} keeps the interleaving: {@code true} for a glyph
     * draw, {@code false} for a rect, so a test can pin that the halo is drawn before any glyph.
     */
    private static final class RecordingCanvas extends Canvas {
        final List<float[]> rects = new ArrayList<>();
        final List<float[]> glyphs = new ArrayList<>();
        final List<Boolean> order = new ArrayList<>();

        @Override public void drawRect(float left, float top, float right, float bottom, Paint paint) {
            RecordingPaint recordingPaint = (RecordingPaint) paint;
            rects.add(new float[]{left, top, right, bottom, recordingPaint.lastColor, recordingPaint.lastAlpha});
            order.add(Boolean.FALSE);
        }

        @Override public void drawText(char[] text, int index, int count, float x, float y, Paint paint) {
            RecordingPaint recordingPaint = (RecordingPaint) paint;
            glyphs.add(new float[]{x, recordingPaint.lastColor, recordingPaint.lastAlpha});
            order.add(Boolean.TRUE);
        }

        @Override public void drawTextRun(char[] text, int index, int count, int contextIndex, int contextCount,
                                         float x, float y, boolean isRtl, Paint paint) {
            RecordingPaint recordingPaint = (RecordingPaint) paint;
            glyphs.add(new float[]{x, recordingPaint.lastColor, recordingPaint.lastAlpha});
            order.add(Boolean.TRUE);
        }

        @Override public int save() { return 0; }
        @Override public void restore() { }
        @Override public void scale(float sx, float sy) { }
    }

    private TerminalEmulator mTerminal;
    private RecordingPaint mPaint;
    private RecordingCanvas mCanvas;

    private int mCursorColor;
    private int mDefaultBackground;
    private int mDefaultForeground;

    @Before
    public void setUp() {
        mTerminal = new TerminalEmulator(new StubOutput(), 10, 3, 10, 20, 3, null);
        mPaint = new RecordingPaint();
        mCanvas = new RecordingCanvas();
        mCursorColor = mTerminal.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR];
        mDefaultBackground = mTerminal.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND];
        mDefaultForeground = mTerminal.mColors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND];
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

    /** The same blend the renderer uses for a fading block cursor's colours. */
    private static int blend(int from, int to, float factor) {
        int r = (int) (((from >> 16) & 0xFF) + ((((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * factor) + 0.5f);
        int g = (int) (((from >> 8) & 0xFF) + ((((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * factor) + 0.5f);
        int b = (int) ((from & 0xFF) + (((to & 0xFF) - (from & 0xFF)) * factor) + 0.5f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** The colour of the first glyph drawn at {@code x}, i.e. the cursor cell's glyph. */
    private int glyphColorAtX(float x) {
        for (float[] glyph : mCanvas.glyphs) {
            if (Math.abs(glyph[0] - x) < 0.01f) return (int) glyph[1];
        }
        throw new AssertionError("no glyph drawn at x=" + x);
    }

    /** The rect whose left edge is at {@code x}, i.e. the one covering the cell in that column. */
    private float[] rectAtX(float x) {
        for (float[] rect : mCanvas.rects) {
            if (Math.abs(rect[0] - x) < 0.01f) return rect;
        }
        throw new AssertionError("no rect drawn at x=" + x);
    }

    @Test
    public void blockCursorFadesItsWholeCell() {
        render(0.5f, 0f, -1f, -1f);
        assertEquals("a fading block must not be an opaque cell plus a translucent overlay", 1, mCanvas.rects.size());
        float[] cell = mCanvas.rects.get(0);
        assertEquals("the block's cell background must blend towards the cursor colour",
            blend(mDefaultBackground, mCursorColor, 0.5f), (int) cell[4]);
        assertEquals("the blended cell must be painted opaque", 255f, cell[5], 0.01f);
        assertEquals("the block's glyph must blend towards the cell background",
            blend(mDefaultForeground, mDefaultBackground, 0.5f), glyphColorAtX(0f));
    }

    @Test
    public void blockCursorFadeBlendsTowardsAReverseVideoCell() {
        enter("\033[?5h");
        render(0.5f, 0f, -1f, -1f);
        assertEquals("reverse video makes the cell background the foreground colour",
            blend(mDefaultForeground, mCursorColor, 0.5f), (int) rectAtX(0f)[4]);
        assertEquals("the glyph fades towards the reversed cell's glyph colour",
            blend(mDefaultBackground, mDefaultForeground, 0.5f), glyphColorAtX(0f));
    }

    @Test
    public void blockCursorFadeBlendsTowardsAnInverseAndReverseVideoCell() {
        enter("\033[?5h\033[7ma\033[1D");
        render(0.5f, 0f, -1f, -1f);
        assertEquals("two reversals cancel: the cell keeps its normal background",
            blend(mDefaultBackground, mCursorColor, 0.5f), (int) rectAtX(0f)[4]);
        assertEquals("two reversals cancel: the glyph fades from the normal foreground",
            blend(mDefaultForeground, mDefaultBackground, 0.5f), glyphColorAtX(0f));
    }

    @Test
    public void blockCursorIsSolidAtFullAlpha() {
        render(1f, 0f, -1f, -1f);
        assertEquals(2, mCanvas.rects.size());
        assertEquals("the solid block is the cursor colour", mCursorColor, (int) mCanvas.rects.get(1)[4]);
        assertEquals("the solid block's glyph is the cell background", mDefaultBackground, glyphColorAtX(0f));
    }

    @Test
    public void cursorHiddenAtZeroAlpha() {
        render(0f, 0f, -1f, -1f);
        assertEquals(0, mCanvas.rects.size());
        assertEquals("the cell's text is drawn normally at the off phase", mDefaultForeground, glyphColorAtX(0f));
    }

    @Test
    public void barCursorUsesTheAlphaAndDrawsOneRect() {
        enter("\033[6 q");
        render(0.5f, 0f, -1f, -1f);
        assertEquals("a bar cursor draws exactly one rect, never one inline and one eased", 1, mCanvas.rects.size());
        assertEquals(128f, mCanvas.rects.get(0)[5], 1f);
        assertEquals("the paint must be opaque again for the glyphs", 255, mPaint.lastAlpha);
    }

    @Test
    public void barCursorAtZeroAlphaWithEasingDrawsNothing() {
        enter("\033[6 q");
        render(0f, 4f, 0f, 5.5f);
        assertEquals("an invisible eased bar must not leave a phantom rect", 0, mCanvas.rects.size());
        assertEquals("the cell's text is drawn normally at the off phase", mDefaultForeground, glyphColorAtX(0f));
    }

    @Test
    public void barCursorEasesToEasedPosition() {
        enter("\033[6 q");
        render(1f, 0f, 0f, 5.5f);
        assertEquals(1, mCanvas.rects.size());
        assertEquals(55f, mCanvas.rects.get(0)[0], 0.01f);
    }

    @Test
    public void blockCursorIgnoresEasing() {
        render(1f, 0f, 0f, 5.5f);
        assertTrue("the block cursor must stay on its true cell", mCanvas.rects.size() >= 2);
        for (float[] rect : mCanvas.rects) assertEquals(0f, rect[0], 0.01f);
    }

    @Test
    public void glowDrawsAHaloBeforeTheRows() {
        render(1f, 4f, -1f, -1f);
        // Three expanding halo rects, then the cell background and the cursor rect.
        assertEquals(5, mCanvas.rects.size());
        float[] outermost = mCanvas.rects.get(0);
        assertTrue("the halo must reach outside the cursor cell", outermost[0] < 0f);
        assertTrue("the halo must reach outside the cursor cell", outermost[2] > CELL_WIDTH);
        assertEquals("the halo uses the cursor colour", mCursorColor, (int) outermost[4]);
        assertTrue("the halo is translucent", outermost[5] < 255f);
        assertEquals("no Paint shadow layer: it is ignored for drawRect on a hardware canvas", 0, mPaint.shadowCalls);
        // The halo must be under every glyph, not only earlier in the rect list: the canvas keeps one ordered
        // call log so this cannot pass if the halo is moved after the rows.
        int firstGlyph = mCanvas.order.indexOf(Boolean.TRUE);
        assertTrue("the halo must be drawn before any glyph", firstGlyph >= 3);
        for (int i = 0; i < 3; i++)
            assertEquals("rect " + i + " must be a halo rect", Boolean.FALSE, mCanvas.order.get(i));
        assertEquals("the paint must be opaque again for the glyphs", 255, mPaint.lastAlpha);
    }

    @Test
    public void noGlowDrawsNoHalo() {
        render(1f, 0f, -1f, -1f);
        assertEquals(2, mCanvas.rects.size());
        assertEquals(0, mPaint.shadowCalls);
    }

    @Test
    public void legacyOverloadKeepsTheCursorOpaqueAndHaloFree() {
        TerminalRenderer renderer = new TerminalRenderer(14, Typeface.MONOSPACE, true, mPaint, null);
        renderer.render(mTerminal, mCanvas, 0, -1, -1, -1, -1, 0, 1);
        assertEquals("the 9-argument overload draws no halo", 2, mCanvas.rects.size());
        assertEquals(255f, mCanvas.rects.get(1)[5], 0.01f);
        assertEquals("the glyphs stay opaque", 255, mPaint.lastAlpha);
    }
}
