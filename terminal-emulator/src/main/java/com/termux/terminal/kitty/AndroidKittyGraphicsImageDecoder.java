package com.termux.terminal.kitty;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

/**
 * Production {@link KittyGraphicsImageDecoder} that understands the three formats in the kitty
 * subset: {@code 24} (RGB), {@code 32} (RGBA) and {@code 100} (PNG).
 */
public class AndroidKittyGraphicsImageDecoder implements KittyGraphicsImageDecoder {

    @Override
    public Bitmap decode(int format, int width, int height, byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        try {
            if (format == 100) {
                return BitmapFactory.decodeByteArray(data, 0, data.length);
            }
            if (format == 24 || format == 32) {
                if (width <= 0 || height <= 0) {
                    return null;
                }
                int pixelCount = width * height;
                int bytesPerPixel = format == 32 ? 4 : 3;
                if ((long) pixelCount * bytesPerPixel > data.length) {
                    return null;
                }
                int[] pixels = new int[pixelCount];
                if (format == 32) {
                    for (int i = 0; i < pixelCount; i++) {
                        int o = i * 4;
                        int r = data[o] & 0xFF;
                        int g = data[o + 1] & 0xFF;
                        int b = data[o + 2] & 0xFF;
                        int a = data[o + 3] & 0xFF;
                        pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
                    }
                } else {
                    for (int i = 0; i < pixelCount; i++) {
                        int o = i * 3;
                        int r = data[o] & 0xFF;
                        int g = data[o + 1] & 0xFF;
                        int b = data[o + 2] & 0xFF;
                        pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                }
                return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
            }
            return null;
        } catch (OutOfMemoryError e) {
            System.gc();
            return null;
        } catch (Throwable t) {
            return null;
        }
    }
}
