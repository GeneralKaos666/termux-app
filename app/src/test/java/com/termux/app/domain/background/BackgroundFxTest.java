package com.termux.app.domain.background;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void liveWallpaperAlwaysUsesTheFallbackColour() {
        assertTrue(BackgroundFx.INSTANCE.useWallpaperFallbackColor(true, true));
        assertTrue(BackgroundFx.INSTANCE.useWallpaperFallbackColor(true, false));
    }

    @Test
    public void staticWallpaperUsesAFrameOnlyWhenItIsUsable() {
        assertFalse(BackgroundFx.INSTANCE.useWallpaperFallbackColor(false, true));
        assertTrue(BackgroundFx.INSTANCE.useWallpaperFallbackColor(false, false));
    }

    @Test
    public void clampBlurRadiusCoercesRange() {
        assertEquals(0f, BackgroundFx.INSTANCE.clampBlurRadius(-5), 0.001f);
        assertEquals(12f, BackgroundFx.INSTANCE.clampBlurRadius(12), 0.001f);
        assertEquals(25f, BackgroundFx.INSTANCE.clampBlurRadius(60), 0.001f);
    }

    @Test
    public void centerCropOfAOnePixelPictureStillCoversOnePixel() {
        BackgroundFx.Crop crop = BackgroundFx.INSTANCE.centerCrop(1, 1, 1080, 2400);
        assertEquals(0, crop.getLeft());
        assertEquals(0, crop.getTop());
        assertEquals(1, crop.getWidth());
        assertEquals(1, crop.getHeight());
    }

    @Test
    public void centerCropKeepsTheSourceAspectRatioAndCentres() {
        BackgroundFx.Crop crop = BackgroundFx.INSTANCE.centerCrop(4000, 2000, 1080, 2400);
        assertEquals(900, crop.getWidth());
        assertEquals(2000, crop.getHeight());
        assertEquals(1550, crop.getLeft());
        assertEquals(0, crop.getTop());
    }

    @Test
    public void centerCropNeverExceedsTheSource() {
        BackgroundFx.Crop crop = BackgroundFx.INSTANCE.centerCrop(10, 1000, 1080, 2400);
        assertEquals(10, crop.getWidth());
        assertTrue(crop.getHeight() <= 1000);
        assertTrue(crop.getHeight() >= 1);
    }

    @Test
    public void centerCropOfAnEmptySizeIsEmpty() {
        BackgroundFx.Crop crop = BackgroundFx.INSTANCE.centerCrop(0, 0, 1080, 2400);
        assertEquals(0, crop.getWidth());
        assertEquals(0, crop.getHeight());
    }
}
