package com.termux.terminal.kitty;

/**
 * Pure helpers that decide the pixel size a kitty image should be scaled to before it is placed.
 *
 * <p>The rules follow the kitty graphics protocol: an explicit pixel size ({@code w}/{@code h})
 * wins, otherwise a cell span ({@code c}/{@code r}) is multiplied by the cell pixel size, and in
 * the absence of either the image keeps its decoded size. Invalid (non-positive) hints never
 * divide or truncate the image.
 */
public final class KittyImageSizing {
    private KittyImageSizing() {
    }

    /**
     * @return {@code {width, height}} the image should be scaled to before placement.
     */
    public static int[] targetPixels(KittyGraphicsCommand cmd, int cellWidth, int cellHeight,
                                     int imageWidth, int imageHeight) {
        // `sourceWidth`/`sourceHeight` are the `w`/`h` keys (explicit pixel size); `width`/`height`
        // on the command are `s`/`v`, the pixel dimensions of the transmitted data.
        int width = imageWidth;
        int height = imageHeight;
        if (cmd.sourceWidth > 0) {
            width = cmd.sourceWidth;
        } else if (cmd.columns > 0 && cellWidth > 0) {
            width = cmd.columns * cellWidth;
        }
        if (cmd.sourceHeight > 0) {
            height = cmd.sourceHeight;
        } else if (cmd.rows > 0 && cellHeight > 0) {
            height = cmd.rows * cellHeight;
        }
        return new int[] {width, height};
    }
}
