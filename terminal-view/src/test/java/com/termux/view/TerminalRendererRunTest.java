package com.termux.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;

import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Call-sequence tests for the renderer's glyph stage: how many glyph draws a run produces, where they start, and
 * when the canvas is scaled onto the cells. Every assertion is on recorded calls, never on pixels.
 */
public class TerminalRendererRunTest extends TestCase {

    private static final float CELL_WIDTH = 10f;

    private static final class StubOutput extends TerminalOutput {
        @Override public void write(byte[] data, int offset, int count) { }
        @Override public void titleChanged(String oldTitle, String newTitle) { }
        @Override public void onCopyTextToClipboard(String text) { }
        @Override public void onPasteTextFromClipboard() { }
        @Override public void onBell() { }
        @Override public void onColorsChanged() { }
    }

    /** A paint whose metrics are fixed: "X" and every single ASCII char measure {@link #cellWidth}. */
    private static class StubPaint extends Paint {
        /** The advance of one cell. */
        float cellWidth = CELL_WIDTH;
        /** A non-ASCII char whose single-char advance is {@link #wideCharWidth} (the mismatch path). */
        char wideChar = 0;
        float wideCharWidth = 2 * CELL_WIDTH;
        /** When set (>= 0), the advance reported for a multi-char slice starting at this char index. */
        int spanAdvanceStartChar = -1;
        float spanAdvance = -1f;

        @Override public float getFontSpacing() { return 20f; }
        @Override public float ascent() { return -15f; }
        @Override public float measureText(String text) { return cellWidth; }
        @Override public float measureText(CharSequence text, int start, int end) { return cellWidth; }
        @Override public float measureText(char[] text, int index, int count) {
            if (count == 1) return (text[index] == wideChar) ? wideCharWidth : cellWidth;
            if (spanAdvance >= 0f && index == spanAdvanceStartChar) return spanAdvance;
            return cellWidth * count;
        }
    }

    /** A canvas that records glyph draws as {startCharIndex, charCount, x} plus the transform calls. */
    private static final class RecordingCanvas extends Canvas {
        final List<String> calls = new ArrayList<>();
        final List<float[]> glyphs = new ArrayList<>();
        final List<Float> scales = new ArrayList<>();

        @Override public void drawText(char[] text, int index, int count, float x, float y, Paint paint) {
            calls.add("text");
            glyphs.add(new float[]{index, count, x});
        }

        @Override public void drawTextRun(char[] text, int index, int count, int contextIndex, int contextCount,
                                         float x, float y, boolean isRtl, Paint paint) {
            calls.add("textRun");
            glyphs.add(new float[]{index, count, x});
        }

        @Override public void drawRect(float left, float top, float right, float bottom, Paint paint) {
            calls.add("rect");
        }

        @Override public void scale(float sx, float sy) {
            calls.add("scale");
            scales.add(sx);
        }

        @Override public int save() {
            calls.add("save");
            return 0;
        }

        @Override public void restore() {
            calls.add("restore");
        }
    }

    /** Advances scripted per consultation, so the policy sees exactly what a test wants it to see. */
    private static final class ScriptedAdvances implements RunAdvancesSource {
        private final float[][] script;
        private int call;
        /** How often the renderer asked, whether or not the script had an entry for it. */
        int calls;

        ScriptedAdvances(float[]... script) {
            this.script = script;
        }

        @Override public int advances(char[] text, int start, int count, float[] out) {
            calls++;
            assertTrue("source consulted more often than scripted: " + calls, call < script.length);
            float[] advances = script[call++];
            System.arraycopy(advances, 0, out, 0, advances.length);
            return advances.length;
        }
    }

    private TerminalEmulator mTerminal;
    private StubPaint mPaint;
    private RecordingCanvas mCanvas;

    @Override protected void setUp() {
        mTerminal = new TerminalEmulator(new StubOutput(), 10, 3, 10, 20, 3, null);
        mPaint = new StubPaint();
        mCanvas = new RecordingCanvas();
    }

    private void enter(String string) {
        byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
        mTerminal.append(bytes, bytes.length);
    }

    /** Renders row 0. The row is space-filled to the full width, so its final run covers all 10 cells. */
    private void render(RunAdvancesSource advances) {
        TerminalRenderer renderer = new TerminalRenderer(14, Typeface.MONOSPACE, mPaint, advances);
        renderer.render(mTerminal, mCanvas, 0, -1, -1, -1, -1, 0, 1);
    }

    public void testAlignedRunDrawsOneShapedSpan() {
        enter("===\r\n");
        render(new ScriptedAdvances(new float[]{10f, 10f, 10f, 10f, 10f, 10f, 10f, 10f, 10f, 10f}));
        assertEquals(1, mCanvas.glyphs.size());
        assertEquals(0f, mCanvas.glyphs.get(0)[0], 0.001f);
        assertEquals(10f, mCanvas.glyphs.get(0)[1], 0.001f);
        assertEquals(0f, mCanvas.glyphs.get(0)[2], 0.001f);
        assertTrue("an aligned run is never scaled: " + mCanvas.scales, mCanvas.scales.isEmpty());
    }

    public void testMisalignedRunIsDrawnSpanBySpan() {
        enter("===\r\n");
        // The first three cells advance as one 25px cluster (the ligature), so the fourth cell boundary is missed.
        render(new ScriptedAdvances(
            new float[]{10f, 10f, 25f, 10f, 10f, 10f, 10f, 10f, 10f, 10f},
            new float[]{10f, 10f, 10f, 10f, 10f, 10f, 10f}));
        assertEquals(2, mCanvas.glyphs.size());
        assertEquals(0f, mCanvas.glyphs.get(0)[0], 0.001f);
        assertEquals(3f, mCanvas.glyphs.get(0)[1], 0.001f);
        assertEquals(0f, mCanvas.glyphs.get(0)[2], 0.001f);
        assertEquals(3f, mCanvas.glyphs.get(1)[0], 0.001f);
        assertEquals(7f, mCanvas.glyphs.get(1)[1], 0.001f);
        assertEquals(30f, mCanvas.glyphs.get(1)[2], 0.001f);
        assertTrue("a span whose total is on-grid is not scaled: " + mCanvas.scales, mCanvas.scales.isEmpty());
    }

    public void testOneCellRemainderNeverConsultsThePolicy() {
        enter("===\r\n");
        // The ninth cell boundary is missed (the tenth cell stands alone), so the run splits after nine cells and
        // the one-cell remainder must not be measured again: one consult for the whole run.
        ScriptedAdvances advances = new ScriptedAdvances(
            new float[]{10f, 10f, 10f, 10f, 10f, 10f, 10f, 10f, 30f, 10f});
        render(advances);
        assertEquals(1, advances.calls);
        assertEquals(2, mCanvas.glyphs.size());
        assertEquals(9f, mCanvas.glyphs.get(0)[1], 0.001f);
        assertEquals(0f, mCanvas.glyphs.get(0)[2], 0.001f);
        assertEquals(1f, mCanvas.glyphs.get(1)[1], 0.001f);
        assertEquals(90f, mCanvas.glyphs.get(1)[2], 0.001f);
    }

    public void testOffGridSpanIsCondensedOntoItsCells() {
        enter("abc" + "\033[44m" + "===\r\n");
        mPaint.spanAdvanceStartChar = 3;
        mPaint.spanAdvance = 40f; // the three-char coloured span measures four cells
        ScriptedAdvances advances = new ScriptedAdvances(
            new float[]{10f, 10f, 10f},       // "abc" run
            new float[]{10f, 10f, 10f},       // the coloured run
            new float[]{10f, 10f, 10f, 10f}); // the row's trailing spaces
        render(advances);
        assertEquals(3, advances.calls);
        assertEquals(3, mCanvas.glyphs.size());
        assertEquals(1, mCanvas.scales.size());
        assertEquals(0.75f, mCanvas.scales.get(0), 0.0001f);
        // The condensed span is pre-divided so that, after the canvas scale, it starts exactly at its first cell.
        assertEquals(40f, mCanvas.glyphs.get(1)[2], 0.001f);
        assertEquals(40f * 0.75f, mCanvas.glyphs.get(1)[2] * mCanvas.scales.get(0), 0.001f);
        assertEquals(3f, mCanvas.glyphs.get(1)[1], 0.001f);
        assertEquals(0f, mCanvas.glyphs.get(0)[2], 0.001f);
        assertEquals(60f, mCanvas.glyphs.get(2)[2], 0.001f);
    }

    public void testRunBackgroundIsDrawnOncePerRunEvenWhenSplit() {
        enter("\033[44m===\r\n");
        ScriptedAdvances advances = new ScriptedAdvances(
            new float[]{10f, 25f, 10f},                        // the coloured run, split after two cells
            new float[]{10f, 10f, 10f, 10f, 10f, 10f, 10f});   // the row's trailing spaces
        render(advances);
        assertEquals(2, advances.calls);
        assertEquals("one span plus the run's one-cell remainder, plus the trailing run",
            3, mCanvas.glyphs.size());
        assertEquals(1, Collections.frequency(mCanvas.calls, "rect"));
    }

    public void testNoShapedAdvancesDrawsOneSpan() {
        enter("===\r\n");
        // Below API 29 RunAdvancesSource.forPaint returns null (the host stub reports API 0), so a run is drawn as
        // one shaped span and the policy is never consulted at all.
        TerminalRenderer renderer = new TerminalRenderer(14, Typeface.MONOSPACE, mPaint, null);
        renderer.render(mTerminal, mCanvas, 0, -1, -1, -1, -1, 0, 1);
        assertEquals(1, mCanvas.glyphs.size());
        assertEquals(10f, mCanvas.glyphs.get(0)[1], 0.001f);
        assertTrue(mCanvas.scales.isEmpty());
    }

    public void testWideCodePointIsStillDrawnPerCharScaled() {
        enter("\u00e9\u00e9\u00e9\r\n");
        mPaint.wideChar = '\u00e9'; // each 'é' measures two cells, so the per-codepoint check breaks every run
        ScriptedAdvances advances = new ScriptedAdvances(new float[]{10f, 10f, 10f, 10f, 10f, 10f, 10f});
        render(advances);
        assertEquals(1, advances.calls); // the three one-cell runs are never verified
        assertEquals(4, mCanvas.glyphs.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(1f, mCanvas.glyphs.get(i)[1], 0.001f);
            assertEquals(0.5f, mCanvas.scales.get(i), 0.0001f);
            assertEquals(20f * i, mCanvas.glyphs.get(i)[2], 0.001f);
        }
        assertEquals(7f, mCanvas.glyphs.get(3)[1], 0.001f);
    }
}
