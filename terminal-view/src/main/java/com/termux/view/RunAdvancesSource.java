package com.termux.view;

import android.graphics.Paint;
import android.os.Build;

/**
 * The shaped advances of one drawn run, behind a seam so the renderer's grid-snap decisions can be tested without
 * a font. The production implementation delegates to {@link Paint#getTextRunAdvances}.
 */
interface RunAdvancesSource {

    /**
     * Writes the shaped advances of {@code text[start, start + count)} to {@code out[0..n)} and returns {@code n}.
     * Only {@code [0, n)} is meaningful to the caller; the rest of {@code out} must be ignored.
     */
    int advances(char[] text, int start, int count, float[] out);

    /**
     * The platform source for {@code paint}, or {@code null} when the platform cannot report shaped per-character
     * advances. {@code Paint.getTextRunAdvances} is API 29; below it a run cannot be verified against the grid, so
     * the renderer draws each run as a single shaped span and never consults {@link ShapedRunPolicy}.
     */
    static RunAdvancesSource forPaint(final Paint paint) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        return new RunAdvancesSource() {
            @Override
            public int advances(char[] text, int start, int count, float[] out) {
                paint.getTextRunAdvances(text, start, count, start, count, false, out, 0);
                return count;
            }
        };
    }
}
