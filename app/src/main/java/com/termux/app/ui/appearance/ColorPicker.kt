package com.termux.app.ui.appearance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.termux.R
import kotlin.math.abs
import kotlin.math.roundToInt

/** A colour as hue (`0..360`), saturation (`0..1`) and value (`0..1`). */
data class HsvColor(val hue: Float, val saturation: Float, val value: Float)

/**
 * Converts an `0xFFrrggbb` colour to HSV. Mirrors `android.graphics.Color.colorToHSV` but is pure
 * Kotlin, so the picker's maths can be unit-tested on the JVM and the screen needs no dependency.
 */
fun rgbToHsv(color: Int): HsvColor {
    val red = (color shr 16 and 0xff) / 255f
    val green = (color shr 8 and 0xff) / 255f
    val blue = (color and 0xff) / 255f

    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val delta = max - min

    val hue = when {
        delta == 0f -> 0f
        max == red -> 60f * (((green - blue) / delta) % 6f)
        max == green -> 60f * (((blue - red) / delta) + 2f)
        else -> 60f * (((red - green) / delta) + 4f)
    }
    val saturation = if (max == 0f) 0f else delta / max

    return HsvColor(hue = if (hue < 0f) hue + 360f else hue, saturation = saturation, value = max)
}

/** Converts HSV back to an opaque `0xFFrrggbb` colour, wrapping hue and clamping saturation/value. */
fun hsvToRgb(hue: Float, saturation: Float, value: Float): Int {
    val wrappedHue = ((hue % 360f) + 360f) % 360f
    val clampedSaturation = saturation.coerceIn(0f, 1f)
    val clampedValue = value.coerceIn(0f, 1f)

    val chroma = clampedValue * clampedSaturation
    val secondary = chroma * (1f - abs((wrappedHue / 60f) % 2f - 1f))
    val match = clampedValue - chroma

    val (red, green, blue) = when {
        wrappedHue < 60f -> Triple(chroma, secondary, 0f)
        wrappedHue < 120f -> Triple(secondary, chroma, 0f)
        wrappedHue < 180f -> Triple(0f, chroma, secondary)
        wrappedHue < 240f -> Triple(0f, secondary, chroma)
        wrappedHue < 300f -> Triple(secondary, 0f, chroma)
        else -> Triple(chroma, 0f, secondary)
    }

    fun channel(value: Float): Int = ((value + match) * 255f).roundToInt().coerceIn(0, 255)

    return 0xff000000.toInt() or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
}

/** Converts HSV to an opaque `0xFFrrggbb` colour. */
fun hsvToRgb(hsv: HsvColor): Int = hsvToRgb(hsv.hue, hsv.saturation, hsv.value)

/**
 * A dependency-free colour picker: a saturation/value square, a hue slider and a hex field, all
 * driving the same HSV value. Nothing is written anywhere; the caller stages the confirmed colour.
 */
@Composable
fun ColorPickerDialog(
    initialColor: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var hsv by remember(initialColor) { mutableStateOf(rgbToHsv(initialColor)) }
    var hexText by remember(initialColor) { mutableStateOf(colorToHex(initialColor)) }

    fun stage(next: HsvColor) {
        hsv = next
        hexText = colorToHex(hsvToRgb(next))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.appearance_color_picker_title)) },
        text = {
            Column {
                SaturationValueBox(
                    hue = hsv.hue,
                    saturation = hsv.saturation,
                    value = hsv.value,
                    onChange = { saturation, value -> stage(hsv.copy(saturation = saturation, value = value)) },
                )
                Spacer(Modifier.height(16.dp))
                Slider(
                    value = hsv.hue,
                    onValueChange = { hue -> stage(hsv.copy(hue = hue)) },
                    valueRange = 0f..360f,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = hexText,
                    onValueChange = { text ->
                        hexText = text
                        parseColor(text)?.let { hsv = rgbToHsv(it) }
                    },
                    singleLine = true,
                    label = { Text(stringResource(R.string.appearance_color_picker_hex)) },
                )
                Spacer(Modifier.height(8.dp))
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp),
                ) {
                    drawRect(color = Color(hsvToRgb(hsv)))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(hsvToRgb(hsv)) }) {
                Text(stringResource(R.string.appearance_action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.appearance_action_cancel))
            }
        },
    )
}

/**
 * The saturation (x) and value (y) square for [hue], with a draggable marker. [onChange] receives the
 * new saturation and value in `0..1`.
 */
@Composable
private fun SaturationValueBox(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (Float, Float) -> Unit,
) {
    val currentOnChange by rememberUpdatedState(onChange)
    val markerColor = MaterialTheme.colorScheme.surface

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .padding(vertical = 2.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    currentOnChange(
                        (offset.x / size.width).coerceIn(0f, 1f),
                        (1f - offset.y / size.height).coerceIn(0f, 1f),
                    )
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    currentOnChange(
                        (change.position.x / size.width).coerceIn(0f, 1f),
                        (1f - change.position.y / size.height).coerceIn(0f, 1f),
                    )
                }
            },
    ) {
        val pureHue = Color(hsvToRgb(HsvColor(hue, 1f, 1f)))
        drawRect(brush = Brush.horizontalGradient(listOf(Color.White, pureHue)))
        drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))

        val marker = Offset(saturation * size.width, (1f - value) * size.height)
        drawCircle(color = markerColor, radius = 9f, center = marker, style = Stroke(width = 3f))
        drawCircle(color = Color.White, radius = 9f, center = marker, style = Stroke(width = 1f))
    }
}
