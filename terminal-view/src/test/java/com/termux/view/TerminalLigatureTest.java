package com.termux.view;

import android.graphics.Paint;
import android.graphics.Typeface;

import junit.framework.TestCase;

/**
 * The ligature toggle is a renderer-construction setting: it becomes a font feature string on the one paint the
 * renderer keeps. The paint is a stub so the value can be read back on this host (a stubbed {@link Paint} would
 * silently discard it).
 */
public class TerminalLigatureTest extends TestCase {

    private static final class RecordingPaint extends Paint {
        String featureSettings;

        @Override public void setFontFeatureSettings(String featureSettings) {
            this.featureSettings = featureSettings;
        }
    }

    public void testLigaturesOnRequestsLigaturesAndContextualAlternates() {
        RecordingPaint paint = new RecordingPaint();
        new TerminalRenderer(14, Typeface.MONOSPACE, true, paint, null);
        assertEquals("+liga,+calt", paint.featureSettings);
    }

    public void testLigaturesOffDisablesBoth() {
        RecordingPaint paint = new RecordingPaint();
        new TerminalRenderer(14, Typeface.MONOSPACE, false, paint, null);
        assertEquals("-liga,-calt", paint.featureSettings);
    }
}
