package com.termux.app.ui.keys

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.R
import com.termux.app.domain.keys.KeyPreviewRow
import com.termux.app.domain.keys.KeySetPresets
import com.termux.app.domain.keys.previewRows

/** Title shown on a preset card: its settings entry, so the two lists read the same. */
@Composable
private fun presetTitle(preset: KeySetPresets.Preset): String = when (preset.id) {
    KeySetPresets.PRESET_COMPACT -> stringResource(R.string.termux_terminal_view_extra_keys_preset_entry_compact)
    KeySetPresets.PRESET_VIM -> stringResource(R.string.termux_terminal_view_extra_keys_preset_entry_vim)
    KeySetPresets.PRESET_EMACS -> stringResource(R.string.termux_terminal_view_extra_keys_preset_entry_emacs)
    else -> stringResource(R.string.termux_terminal_view_extra_keys_preset_entry_default)
}

/**
 * The preset picker: a horizontal list of cards, each a miniature of the layout it applies, so the
 * shape of a preset is visible before it replaces the user's key bar. The selected card carries a
 * primary border; its Use button applies the choice.
 */
@Composable
fun KeyLayoutGallery(
    selectedId: String,
    onUse: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        items(KeySetPresets.ALL, key = { it.id }) { preset ->
            val selected = preset.id == selectedId
            KeyLayoutCard(
                title = presetTitle(preset),
                rows = previewRows(preset),
                selected = selected,
                onUse = { onUse(preset.id) },
            )
        }
    }
}

@Composable
private fun KeyLayoutCard(
    title: String,
    rows: List<KeyPreviewRow>,
    selected: Boolean,
    onUse: () -> Unit,
) {
    Card(
        modifier = Modifier
            .sizeIn(minWidth = 136.dp, maxWidth = 160.dp)
            .clickable(onClick = onUse),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                rows.forEach { row -> MiniKeyRow(row) }
            }
            Button(onClick = onUse) {
                Text(
                    text = if (selected) {
                        stringResource(R.string.key_gallery_in_use)
                    } else {
                        stringResource(R.string.key_gallery_use)
                    },
                )
            }
        }
    }
}

/** One preview row: the same keys the preset sets, squeezed into a keycap strip. */
@Composable
private fun MiniKeyRow(row: KeyPreviewRow) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        row.keys.forEach { key ->
            Box(
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.shapes.extraSmall,
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(
                    text = key,
                    fontSize = 10.sp,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
