package com.termux.view;

/**
 * Pure math for the cursor's visual effects: the fade-blink envelope and the eased movement step.
 * <p/>
 * Kept free of {@code android.*} types so it is unit-testable on the JVM (see {@code CursorFxTest}),
 * in the same spirit as {@code RenderBand}.
 */
final class CursorFx {

    private CursorFx() {
    }

    /**
     * The cursor opacity for the triangular blink envelope at {@code elapsedMs} into a blink cycle of
     * {@code periodMs}: full at the start and end of the cycle, fully faded at the half cycle, and linear
     * in between. A non-positive period means blinking is disabled, so the cursor stays fully opaque.
     */
    static float blinkAlpha(long elapsedMs, long periodMs) {
        if (periodMs <= 0) return 1f;
        long phase = elapsedMs % periodMs;
        if (phase < 0) phase += periodMs;
        float position = (float) phase / (float) periodMs;
        return Math.abs(2f * position - 1f);
    }

    /**
     * One time-based exponential-smoothing step from {@code current} towards {@code target}: the
     * remaining distance halves every {@code halfLifeMs}, so the glide speed depends on elapsed time,
     * not on callback cadence. A non-positive {@code dtMs} means no time has passed, so the value
     * holds; a non-positive {@code halfLifeMs} is degenerate and snaps to {@code target}.
     */
    static float easeTowards(float current, float target, float dtMs, float halfLifeMs) {
        if (dtMs <= 0f) return current;
        if (halfLifeMs <= 0f) return target;
        float factor = 1f - (float) Math.pow(0.5, dtMs / halfLifeMs);
        return current + (target - current) * factor;
    }
}
