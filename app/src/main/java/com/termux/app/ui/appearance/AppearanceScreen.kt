package com.termux.app.ui.appearance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.termux.R
import java.io.File

/**
 * State holder behind the appearance screen.
 *
 * Edits live in [AppearanceState.draft] and a picked font is held as a candidate; nothing is written
 * to `colors.properties`, `font.ttf` or the preferences until [apply]. [previewFontFile] is the file
 * the preview should render with the staged font.
 */
class AppearanceController(private val store: AppearanceStore) {

    val state: AppearanceState = AppearanceState(store.read())

    /** The picked (but not yet installed) font, if any. */
    var pendingFontSource: File? by mutableStateOf(null)
        private set

    /** Whether [apply] should delete the font currently installed. */
    var removeInstalledFont: Boolean by mutableStateOf(false)
        private set

    /** True when the screen holds edits the user has not applied or discarded. */
    val hasUnsavedChanges: Boolean
        get() = state.isDirty || pendingFontSource != null || removeInstalledFont

    /**
     * The font the preview should use: the picked candidate, otherwise the installed font, or `null`
     * for the monospace fallback (including while a removal is staged).
     */
    val previewFontFile: File?
        get() = pendingFontSource?.takeIf { it.isFile }
            ?: if (!removeInstalledFont) state.draft.fontPath?.let(::File)?.takeIf { it.isFile } else null

    /** Stages [source] as the font to install on the next [apply]. */
    fun chooseFont(source: File) {
        pendingFontSource = source
        removeInstalledFont = false
        state.setFontPath(source.path)
    }

    /** Stages the removal of the installed font (or cancels a pending font). */
    fun removeFont() {
        pendingFontSource = null
        removeInstalledFont = state.loaded.fontPath != null
        state.setFontPath(null)
    }

    /**
     * The cursor the preview should draw: the staged override, or the `termux.properties`/engine
     * default when the staged appearance follows the properties file.
     */
    val previewCursorStyle: CursorStyle
        get() = state.draft.cursorStyle ?: store.defaultCursorStyle()

    /** Stages the Termux default scheme. */
    fun resetToDefault() = state.resetToDefault()

    /** Throws away the staged edits, keeping the last applied appearance. */
    fun discard() {
        state.discard()
        pendingFontSource = null
        removeInstalledFont = false
    }

    /** Writes the staged appearance; a rejected font leaves the installed one in place. */
    fun apply(): AppearanceWriteResult {
        if (removeInstalledFont && pendingFontSource == null) store.removeFont()
        val result = store.write(state.draft, fontSource = pendingFontSource)
        pendingFontSource = null
        removeInstalledFont = false
        state.load(store.read())
        return result
    }
}

/** Which colour the picker is currently editing. */
private sealed interface ColorTarget {
    fun current(colors: TerminalColorSet): Int

    fun stage(state: AppearanceState, color: Int)
}

private data object BackgroundTarget : ColorTarget {
    override fun current(colors: TerminalColorSet) = colors.background
    override fun stage(state: AppearanceState, color: Int) = state.setBackground(color)
}

private data object ForegroundTarget : ColorTarget {
    override fun current(colors: TerminalColorSet) = colors.foreground
    override fun stage(state: AppearanceState, color: Int) = state.setForeground(color)
}

private data object CursorColorTarget : ColorTarget {
    override fun current(colors: TerminalColorSet) = colors.cursor
    override fun stage(state: AppearanceState, color: Int) = state.setCursorColor(color)
}

private data class AnsiTarget(val index: Int) : ColorTarget {
    override fun current(colors: TerminalColorSet) = colors.ansi[index]
    override fun stage(state: AppearanceState, color: Int) = state.setAnsiColor(index, color)
}

/**
 * The full-screen appearance editor: colour scheme presets, the managed colours, cursor style and
 * blink, the font family and size, and a live preview of the staged result. Apply commits, Reset
 * stages the Termux default scheme, and Back asks before discarding unsaved edits.
 */
@Composable
fun AppearanceScreen(
    controller: AppearanceController,
    fontSizeRange: IntRange,
    onPickFont: () -> Unit,
    onApplied: (AppearanceWriteResult) -> Unit,
    onDiscard: () -> Unit,
) {
    val state = controller.state
    val draft = state.draft
    var colorTarget by remember { mutableStateOf<ColorTarget?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }

    BackHandler(enabled = controller.hasUnsavedChanges) { confirmDiscard = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        PreviewPane(draft, controller.previewCursorStyle, previewFontFamily(controller.previewFontFile))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SectionTitle(stringResource(R.string.appearance_section_scheme))
            PresetRow(selected = draft.colors) { state.setColors(it) }

            SectionTitle(stringResource(R.string.appearance_section_colors))
            ColorRow(stringResource(R.string.appearance_color_background), draft.colors.background) {
                colorTarget = BackgroundTarget
            }
            ColorRow(stringResource(R.string.appearance_color_foreground), draft.colors.foreground) {
                colorTarget = ForegroundTarget
            }
            ColorRow(stringResource(R.string.appearance_color_cursor), draft.colors.cursor) {
                colorTarget = CursorColorTarget
            }
            AnsiSwatches(draft.colors) { colorTarget = AnsiTarget(it) }

            SectionTitle(stringResource(R.string.appearance_section_cursor))
            CursorStyleRow(draft.cursorStyle) { state.setCursorStyle(it) }
            BlinkRow(draft.cursorBlinkRate) { state.setCursorBlinkRate(it) }

            SectionTitle(stringResource(R.string.appearance_section_font))
            FontRow(
                fontFile = draft.fontPath?.let(::File),
                onChooseFont = onPickFont,
                onRemoveFont = controller::removeFont,
            )
            FontSizeRow(draft.fontSize, fontSizeRange) { state.setFontSize(it) }

            Spacer(Modifier.height(16.dp))
        }

        HorizontalDivider()
        ActionBar(
            onReset = controller::resetToDefault,
            onApply = { onApplied(controller.apply()) },
        )
    }

    colorTarget?.let { target ->
        ColorPickerDialog(
            initialColor = target.current(draft.colors),
            onDismiss = { colorTarget = null },
            onConfirm = { color ->
                target.stage(state, color)
                colorTarget = null
            },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.appearance_discard_title)) },
            text = { Text(stringResource(R.string.appearance_discard_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    controller.discard()
                    onDiscard()
                }) {
                    Text(stringResource(R.string.appearance_action_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text(stringResource(R.string.appearance_action_cancel))
                }
            },
        )
    }
}

/** Builds the preview typeface, falling back to monospace for a missing or invalid font file. */
@Composable
private fun previewFontFamily(file: File?): FontFamily = remember(file) {
    if (file != null && FontValidator.validate(file) == null) {
        try {
            FontFamily(Font(file))
        } catch (t: Throwable) {
            FontFamily.Monospace
        }
    } else {
        FontFamily.Monospace
    }
}

/** Sample terminal output rendered with the staged colours, cursor and typeface. */
@Composable
private fun PreviewPane(appearance: TerminalAppearance, cursorStyle: CursorStyle, fontFamily: FontFamily) {
    val colors = appearance.colors
    val density = LocalDensity.current
    // The engine's font size is in physical pixels; convert so the preview matches the terminal.
    val textSize = with(density) { appearance.fontSize.toFloat().toSp() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(colors.background))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "user@termux ~ $ ls",
                color = Color(colors.foreground),
                fontFamily = fontFamily,
                fontSize = textSize,
            )
            Cursor(style = cursorStyle, color = Color(colors.cursor), fontSize = appearance.fontSize)
        }
        Text(
            text = "Documents  Downloads  notes.txt",
            color = Color(colors.ansi[4]),
            fontFamily = fontFamily,
            fontSize = textSize,
        )
        Text(
            text = "error: command not found",
            color = Color(colors.ansi[1]),
            fontFamily = fontFamily,
            fontSize = textSize,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            colors.ansi.forEach { ansi ->
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(Color(ansi)),
                )
            }
        }
    }
}

/** A cursor glyph matching the staged shape, sized in dp from the engine's pixel font size. */
@Composable
private fun Cursor(style: CursorStyle, color: Color, fontSize: Int) {
    val density = LocalDensity.current
    val lineHeight = with(density) { (fontSize + 4).toDp() }
    val halfWidth = with(density) { (fontSize / 2).toDp() }
    Spacer(Modifier.width(4.dp))
    val modifier = when (style) {
        CursorStyle.BLOCK -> Modifier.width(halfWidth).height(lineHeight)
        CursorStyle.UNDERLINE -> Modifier.width(halfWidth).height(2.dp)
        CursorStyle.BAR -> Modifier.width(2.dp).height(lineHeight)
    }
    Box(modifier.background(color))
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(12.dp))
    Text(text = text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun PresetRow(selected: TerminalColorSet, onSelect: (TerminalColorSet) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ColorSchemes.presets.forEach { preset ->
            FilterChip(
                selected = preset.colors == selected,
                onClick = { onSelect(preset.colors) },
                label = { Text(preset.name) },
            )
        }
    }
}

@Composable
private fun ColorRow(label: String, color: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Text(colorToHex(color), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RectangleShape)
                .background(Color(color)),
        )
    }
}

@Composable
private fun AnsiSwatches(colors: TerminalColorSet, onSelect: (Int) -> Unit) {
    Spacer(Modifier.height(8.dp))
    for (row in 0 until 2) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (column in 0 until 8) {
                val index = row * 8 + column
                val swatchColor = colors.ansi[index]
                val description = stringResource(R.string.appearance_color_ansi, index, colorToHex(swatchColor))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RectangleShape)
                        .background(Color(swatchColor))
                        .clickable { onSelect(index) }
                        .semantics {
                            contentDescription = description
                            role = Role.Button
                        },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun CursorStyleRow(style: CursorStyle?, onSelect: (CursorStyle?) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = style == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.appearance_cursor_style_default)) },
        )
        CursorStyle.entries.forEach { candidate ->
            FilterChip(
                selected = candidate == style,
                onClick = { onSelect(candidate) },
                label = { Text(stringResource(cursorStyleLabel(candidate))) },
            )
        }
    }
}

/**
 * Blink controls. The chips are the three states the override can hold — follow `termux.properties`,
 * steady, or blinking — and the rate slider only appears for the blinking state.
 */
@Composable
private fun BlinkRow(rate: Int?, onChange: (Int?) -> Unit) {
    val blinking = rate != null && rate != 0
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = rate == null,
            onClick = { onChange(null) },
            label = { Text(stringResource(R.string.appearance_cursor_blink_default)) },
        )
        FilterChip(
            selected = rate == 0,
            onClick = { onChange(0) },
            label = { Text(stringResource(R.string.appearance_cursor_blink_steady)) },
        )
        FilterChip(
            selected = blinking,
            onClick = { onChange(if (blinking) rate else DEFAULT_BLINK_RATE) },
            label = { Text(stringResource(R.string.appearance_cursor_blink)) },
        )
    }
    if (blinking) {
        Slider(
            value = rate.toFloat().coerceIn(CursorBlink.MIN_RATE.toFloat(), CursorBlink.MAX_RATE.toFloat()),
            onValueChange = { onChange(it.toInt()) },
            valueRange = CursorBlink.MIN_RATE.toFloat()..CursorBlink.MAX_RATE.toFloat(),
        )
    }
}

@Composable
private fun FontRow(fontFile: File?, onChooseFont: () -> Unit, onRemoveFont: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = fontFile?.name ?: stringResource(R.string.appearance_font_none),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onChooseFont) { Text(stringResource(R.string.appearance_action_choose_font)) }
        TextButton(onClick = onRemoveFont, enabled = fontFile != null) {
            Text(stringResource(R.string.appearance_action_remove_font))
        }
    }
}

@Composable
private fun FontSizeRow(size: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.appearance_font_size), modifier = Modifier.weight(1f))
        Text(size.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Slider(
        value = size.toFloat().coerceIn(range.first.toFloat(), range.last.toFloat()),
        onValueChange = { onChange(it.toInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = (range.last - range.first - 1).coerceAtLeast(0),
    )
}

@Composable
private fun ActionBar(onReset: () -> Unit, onApply: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onReset, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.appearance_action_reset))
        }
        Button(onClick = onApply) { Text(stringResource(R.string.appearance_action_apply)) }
    }
}

private fun cursorStyleLabel(style: CursorStyle): Int = when (style) {
    CursorStyle.BLOCK -> R.string.appearance_cursor_style_block
    CursorStyle.UNDERLINE -> R.string.appearance_cursor_style_underline
    CursorStyle.BAR -> R.string.appearance_cursor_style_bar
}

private const val DEFAULT_BLINK_RATE = 500
