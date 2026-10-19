package com.termux.terminal.kitty;

import android.graphics.Bitmap;

/**
 * Decodes a kitty graphics payload into a {@link Bitmap}.
 *
 * <p>This seam keeps {@code TerminalEmulator} free of Android bitmap handling and lets tests
 * substitute a deterministic decoder (the {@code :terminal-emulator} unit tests run with Android
 * stubs that would otherwise return {@code null} from every real decoder call).
 */
public interface KittyGraphicsImageDecoder {
    /**
     * @param format one of {@code 24} (RGB), {@code 32} (RGBA) or {@code 100} (PNG).
     * @param width  declared width in pixels, used for the raw {@code 24}/{@code 32} formats.
     * @param height declared height in pixels, used for the raw {@code 24}/{@code 32} formats.
     * @param data   decoded image bytes.
     * @return the decoded bitmap, or {@code null} when the data is not a valid image.
     */
    Bitmap decode(int format, int width, int height, byte[] data);
}
