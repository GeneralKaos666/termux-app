package com.termux.app.ui.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.termux.app.domain.appearance.ANSI_COLOR_COUNT
import com.termux.app.domain.appearance.ColorSchemePreset
import com.termux.app.domain.appearance.ColorSchemes
import com.termux.app.domain.appearance.TerminalColorSet
import kotlin.math.pow

/** Presets split by how light their background is, so a dark pick is not buried among light ones. */
data class PresetGroup(val name: String, val presets: List<ColorSchemePreset>)

/** Relative luminance above this counts as a light theme. */
private const val LIGHT_BACKGROUND_LUMINANCE = 0.5

private const val GROUP_DARK = "Dark"
private const val GROUP_LIGHT = "Light"

/**
 * Splits [presets] into a Dark and a Light group by the relative luminance of each scheme's
 * background. Empty groups are omitted, and the order within each group is the input order.
 */
fun groupPresets(presets: List<ColorSchemePreset>): List<PresetGroup> {
    val dark = presets.filterNot { isLight(it.colors.background) }
    val light = presets.filter { isLight(it.colors.background) }
    return buildList {
        if (dark.isNotEmpty()) add(PresetGroup(GROUP_DARK, dark))
        if (light.isNotEmpty()) add(PresetGroup(GROUP_LIGHT, light))
    }
}

/** WCAG relative luminance of an opaque ARGB colour. */
private fun isLight(argb: Int): Boolean {
    fun channel(value: Int): Double {
        val srgb = (value and 0xff) / 255.0
        return if (srgb <= 0.03928) srgb / 12.92 else ((srgb + 0.055) / 1.055).pow(2.4)
    }

    val red = channel(argb ushr 16)
    val green = channel(argb ushr 8)
    val blue = channel(argb)
    val luminance = 0.2126 * red + 0.7152 * green + 0.0722 * blue
    return luminance > LIGHT_BACKGROUND_LUMINANCE
}

/**
 * The colour-scheme picker: cards grouped by brightness, each showing its name over a strip of its
 * first eight ANSI colours. Selecting a card stages its colours; the user still presses Apply.
 */
@Composable
fun ThemeGallery(
    selected: TerminalColorSet,
    onSelect: (TerminalColorSet) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        groupPresets(ColorSchemes.presets).forEach { group ->
            Text(
                text = group.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            group.presets.chunked(PRESETS_PER_ROW).forEach { rowPresets ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowPresets.forEach { preset ->
                        PresetCard(
                            preset = preset,
                            selected = preset.colors == selected,
                            onClick = { onSelect(preset.colors) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Keep three equal columns on the last row instead of stretching one card.
                    repeat(PRESETS_PER_ROW - rowPresets.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private const val PRESETS_PER_ROW = 3

@Composable
private fun PresetCard(
    preset: ColorSchemePreset,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = shape,
            )
            .clickable(onClick = onClick),
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(16.dp)) {
            val swatches = minOf(8, ANSI_COLOR_COUNT)
            repeat(swatches) { index ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(16.dp)
                        .background(Color(preset.colors.ansi[index])),
                )
            }
        }
        Text(
            text = preset.name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}
