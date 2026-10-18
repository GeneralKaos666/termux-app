package com.termux.app.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TextStyle;
import com.termux.view.TerminalRenderer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Behavioral tests for WS5 rendering: an OSC 8 link cell gains an underline, an SGR underline on a
 * link cell is not drawn twice, and an SGR 58 underline color changes the drawn underline's paint.
 *
 * <p>These observe the renderer's <em>draw calls</em> through a recording {@link Canvas} rather than
 * final pixels: Robolectric's native graphics runtime is unsupported on this build host
 * (Linux aarch64), so Bitmap/Canvas rasterization is unavailable in unit tests. Every draw call is
 * recorded with the paint state it was made with, which is what the behavior under test actually
 * consists of.
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class TerminalRendererUnderlineTest {

    private static final int COLUMNS = 10;
    private static final int ROWS = 3;
    private static final int TEXT_SIZE = 20;
    private static final int PAINT_FLAG_UNDERLINE_TEXT = 0x08;
    private static final String LINK_OPEN = "\033]8;;http://example.com\007";
    private static final String LINK_CLOSE = "\033]8;;\007";

    /** Records every draw call with a snapshot of the paint it was made with. */
    private static final class RecordingCanvas extends Canvas {
        final List<String> events = new ArrayList<>();

        @Override
        public void drawTextRun(char[] text, int index, int count, int contextIndex, int contextCount,
                                float x, float y, boolean isRtl, Paint paint) {
            events.add(textEvent(String.valueOf(text, index, count), x, y, paint));
        }

        @Override
        public void drawTextRun(CharSequence text, int start, int end, int contextStart, int contextEnd,
                                float x, float y, boolean isRtl, Paint paint) {
            events.add(textEvent(text.subSequence(start, end).toString(), x, y, paint));
        }

        @Override
        public void drawText(char[] text, int index, int count, float x, float y, Paint paint) {
            events.add(textEvent(String.valueOf(text, index, count), x, y, paint));
        }

        @Override
        public void drawText(CharSequence text, int start, int end, float x, float y, Paint paint) {
            events.add(textEvent(text.subSequence(start, end).toString(), x, y, paint));
        }

        @Override
        public void drawLine(float x1, float y1, float x2, float y2, Paint paint) {
            events.add("line:x1=" + x1 + ",y1=" + y1 + ",x2=" + x2 + ",y2=" + y2 + ":color=" + paint.getColor());
        }

        @Override
        public void drawPath(Path path, Paint paint) {
            events.add("path:color=" + paint.getColor());
        }

        @Override
        public void drawRect(float left, float top, float right, float bottom, Paint paint) {
            events.add("rect:l=" + left + ",t=" + top + ",r=" + right + ",b=" + bottom + ":color=" + paint.getColor());
        }

        private static String textEvent(String text, float x, float y, Paint paint) {
            boolean underline = (paint.getFlags() & PAINT_FLAG_UNDERLINE_TEXT) != 0;
            return "text:" + text + ":x=" + x + ":color=" + paint.getColor() + ":ul=" + underline;
        }
    }

    /** Minimal session output stub; the renderer tests never read session callbacks. */
    private static final class StubOutput extends TerminalOutput {
        @Override public void write(byte[] data, int offset, int count) { }
        @Override public void titleChanged(String oldTitle, String newTitle) { }
        @Override public void onCopyTextToClipboard(String text) { }
        @Override public void onPasteTextFromClipboard() { }
        @Override public void onBell() { }
        @Override public void onColorsChanged() { }
    }

    private static TerminalEmulator emulator(String input) {
        TerminalEmulator emulator = new TerminalEmulator(new StubOutput(), COLUMNS, ROWS, 10, 20, ROWS, null);
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes, bytes.length);
        return emulator;
    }

    private static List<String> render(TerminalEmulator emulator) {
        RecordingCanvas canvas = new RecordingCanvas();
        new TerminalRenderer(TEXT_SIZE, Typeface.MONOSPACE).render(emulator, canvas, 0, -1, -1, -1, -1);
        return canvas.events;
    }

    private static int countUnderlinedTextRuns(List<String> events) {
        int count = 0;
        for (String event : events) {
            if (event.startsWith("text:") && event.endsWith(":ul=true")) count++;
        }
        return count;
    }

    private static boolean hasLineWithColor(List<String> events, int color) {
        for (String event : events) {
            if (event.startsWith("line:") && event.endsWith(":color=" + color)) return true;
        }
        return false;
    }

    private static boolean hasLine(List<String> events) {
        for (String event : events) {
            if (event.startsWith("line:")) return true;
        }
        return false;
    }

    /** Same content rendered twice produces the same draw calls — the control for the difference tests. */
    @Test
    public void testIdenticalContentRendersIdentically() {
        assertEquals(render(emulator("plain")), render(emulator("plain")));
    }

    /** Different content produces different draw calls — proves the recording captures the renderer's output. */
    @Test
    public void testDifferentContentRendersDifferently() {
        assertFalse(render(emulator("plain")).equals(render(emulator("DIFFERENT"))));
    }

    /** A link cell without an SGR underline must draw its text run underlined. */
    @Test
    public void testLinkCellDrawsUnderline() {
        int plainUnderlined = countUnderlinedTextRuns(render(emulator("Link")));
        int linkedUnderlined = countUnderlinedTextRuns(render(emulator(LINK_OPEN + "Link" + LINK_CLOSE)));
        assertTrue("link cell must render a hyperlink underline", linkedUnderlined > plainUnderlined);
    }

    /** A link whose cells already carry an SGR underline must not gain a second underline. */
    @Test
    public void testSgrUnderlineIsNotDoubledForLink() {
        List<String> underlined = render(emulator("\033[4mLink"));
        List<String> underlinedLink = render(emulator("\033[4m" + LINK_OPEN + "Link" + LINK_CLOSE));
        assertEquals("SGR underline on a link cell must not be drawn twice", underlined, underlinedLink);
    }

    /** SGR 58 must stroke the underline with the configured palette color instead of the default path. */
    @Test
    public void testSgr58DrawsColoredUnderline() {
        TerminalEmulator plainEmulator = emulator("\033[4mLink");
        int paletteIndexNine = plainEmulator.mColors.mCurrentColors[9];

        List<String> defaultUnderline = render(plainEmulator);
        List<String> coloredUnderline = render(emulator("\033[4m\033[58;5;9mLink"));

        assertFalse("an uncolored single underline is stroked by the paint, not drawn as a line",
            hasLine(defaultUnderline));
        assertTrue("SGR 58 underline must be stroked as a line", hasLine(coloredUnderline));
        assertTrue("SGR 58 underline must use palette color 9",
            hasLineWithColor(coloredUnderline, paletteIndexNine));
        assertEquals("the colored underline must not also be painted by the text paint",
            0, countUnderlinedTextRuns(coloredUnderline));
    }

    /**
     * A link cell that carries an SGR 58 color but no SGR underline must still show its link underline,
     * stroked in that color: the link underline is an underline of the cell like any other.
     */
    @Test
    public void testLinkWithSgr58DrawsColoredUnderline() {
        TerminalEmulator emulator = emulator("\033[58;5;9m" + LINK_OPEN + "Link" + LINK_CLOSE);
        int paletteIndexNine = emulator.mColors.mCurrentColors[9];

        List<String> coloredUnderline = render(emulator);

        assertTrue("a link cell's underline must take the SGR 58 color",
            hasLineWithColor(coloredUnderline, paletteIndexNine));
        assertEquals("the colored link underline must not also be painted by the text paint",
            0, countUnderlinedTextRuns(coloredUnderline));
    }

    /**
     * The underline bit with no {@code SGR 4:n} style is a plain single underline
     * ({@link TextStyle#UNDERLINE_STYLE_NONE} with the bit set). With an SGR 58 color it must still be
     * stroked: it is a single underline like any other, just colored. (The emulator itself always stores
     * a concrete style, but the renderer's contract covers the whole style encoding, and
     * {@code TerminalBuffer.setChar} takes any style a library consumer builds.)
     */
    @Test
    public void testUnderlineBitWithoutStyleDrawsColoredUnderline() {
        TerminalEmulator emulator = emulator("Link");
        int paletteIndexNine = emulator.mColors.mCurrentColors[9];
        // The underline attribute bit with UNDERLINE_STYLE_NONE (0) in the style bits.
        emulator.getScreen().setChar(0, 0, 'L', TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE, 0, paletteIndexNine);

        List<String> coloredUnderline = render(emulator);

        assertTrue("an untyped underline with an SGR 58 color must be stroked as a line",
            hasLineWithColor(coloredUnderline, paletteIndexNine));
    }
}
