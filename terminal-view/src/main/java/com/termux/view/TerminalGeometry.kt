package com.termux.view

/**
 * Converts between view coordinates and terminal cells.
 *
 * Pure arithmetic only, no `android.*` types, so the mapping is unit tested without a device.
 */
object TerminalGeometry {

    /** Column whose cell contains `x`; the part cell past the right edge truncates down. */
    @JvmStatic
    fun cursorColumn(x: Float, fontWidth: Float): Int {
        return (x / fontWidth).toInt()
    }

    /**
     * Row whose line contains `y`, shifted by the current scroll position.
     *
     * The `- 40` offset is existing behaviour, preserved from `TerminalView.getCursorY`;
     * do not derive or correct it here.
     */
    @JvmStatic
    fun cursorRow(y: Float, lineSpacing: Float, topRow: Int): Int {
        return ((y - 40) / lineSpacing + topRow).toInt()
    }

    /** Left edge of `column`, clamped to the last column so a past-the-end point stays inside. */
    @JvmStatic
    fun pointX(column: Int, columns: Int, fontWidth: Float): Int {
        val clamped = if (column > columns) columns else column
        return Math.round(clamped * fontWidth)
    }

    /** Top edge of `row`, relative to the current scroll position. */
    @JvmStatic
    fun pointY(row: Int, topRow: Int, lineSpacing: Float): Int {
        return Math.round((row - topRow) * lineSpacing)
    }
}
