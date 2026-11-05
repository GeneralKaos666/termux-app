package com.termux.view;

/**
 * Converts between view coordinates and terminal cells.
 *
 * <p>Pure arithmetic only, no {@code android.*} types, so the mapping is unit tested without a device.
 */
final class TerminalGeometry {

    private TerminalGeometry() {
    }

    /** Column whose cell contains {@code x}; the part cell past the right edge truncates down. */
    static int cursorColumn(float x, float fontWidth) {
        return (int) (x / fontWidth);
    }

    /**
     * Row whose line contains {@code y}, shifted by the current scroll position.
     *
     * <p>The {@code - 40} offset is existing behaviour, preserved from `TerminalView.getCursorY`; do not
     * derive or correct it here.
     */
    static int cursorRow(float y, float lineSpacing, int topRow) {
        return (int) (((y - 40) / lineSpacing) + topRow);
    }

    /** Left edge of {@code column}, clamped to the last column so a past-the-end point stays inside. */
    static int pointX(int column, int columns, float fontWidth) {
        if (column > columns) {
            column = columns;
        }
        return Math.round(column * fontWidth);
    }

    /** Top edge of {@code row}, relative to the current scroll position. */
    static int pointY(int row, int topRow, float lineSpacing) {
        return Math.round((row - topRow) * lineSpacing);
    }
}
