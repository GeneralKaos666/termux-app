package com.termux.app.domain.keys

/** One row of a key-layout preview card, already limited to what fits. */
data class KeyPreviewRow(val keys: List<String>)

/** Most rows a gallery card shows; a preset with a third row is not previewed past it. */
private const val MAX_PREVIEW_ROWS = 2

/**
 * The rows drawn in a preset's gallery card.
 *
 * The gallery never scrolls a card sideways, so keys past [maxColumns] are dropped rather than
 * wrapped into another row: the card stays a recognisable miniature of the first two rows.
 */
fun previewRows(preset: KeySetPresets.Preset, maxColumns: Int = 8): List<KeyPreviewRow> =
    preset.rows
        .take(MAX_PREVIEW_ROWS)
        .map { row -> KeyPreviewRow(row.take(maxColumns).map { it.key }) }
        .filter { it.keys.isNotEmpty() }
