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
     * One exponential-smoothing step from {@code current} towards {@code target}: {@code factor} is the
     * fraction of the remaining distance covered (clamped to {@code [0,1]}), {@code 1} snaps and {@code 0}
     * holds still.
     */
    static float easeTowards(float current, float target, float factor) {
        float clamped = Math.max(0f, Math.min(1f, factor));
        return current + (target - current) * clamped;
    }
}
