package com.termux.app.ui.background;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Pure math behind the acrylic terminal background: the API &lt; 31 downscale fallback for the
 * blur, the live-wallpaper fallback colour, and the opacity clamp.
 */
public class BackgroundFxTest {

    @Test
    public void blurDownscaleIsOneAtZeroRadius() {
        assertEquals(1f, BackgroundFx.INSTANCE.blurDownscale(0f), 0.001f);
    }

    @Test
    public void blurDownscaleGrowsWithRadiusAndClamps() {
        assertEquals(1f + 25f / 8f, BackgroundFx.INSTANCE.blurDownscale(25f), 0.001f);
        assertEquals(8f, BackgroundFx.INSTANCE.blurDownscale(1000f), 0.001f);
    }

    @Test
    public void blurDownscaleNeverGoesBelowOne() {
        assertEquals(1f, BackgroundFx.INSTANCE.blurDownscale(-10f), 0.001f);
    }

    @Test
    public void wallpaperFallbackColorPrefersPrimary() {
        assertEquals(0xFF123456, BackgroundFx.INSTANCE.wallpaperFallbackColor(true, 0xFF123456, 0xFF000000));
        assertEquals(0xFF000000, BackgroundFx.INSTANCE.wallpaperFallbackColor(false, 0xFF123456, 0xFF000000));
    }

    @Test
    public void clampOpacityCoercesRange() {
        assertEquals(0.1f, BackgroundFx.INSTANCE.clampOpacity(5), 0.001f);
        assertEquals(0.6f, BackgroundFx.INSTANCE.clampOpacity(60), 0.001f);
        assertEquals(1f, BackgroundFx.INSTANCE.clampOpacity(150), 0.001f);
    }
}
