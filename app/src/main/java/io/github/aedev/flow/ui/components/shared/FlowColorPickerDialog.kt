package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.utils.parseHexColor
import io.github.aedev.flow.utils.sanitizeHexInput
import io.github.aedev.flow.utils.toHexArgb
import io.github.aedev.flow.utils.toHexRgb
import kotlin.math.roundToInt

private val PreviewHeight = 56.dp
private val PreviewBorder = 1.dp
private val ControlSpacing = 8.dp
private const val MAX_HUE = 360f
private const val MAX_CHANNEL = 255f
private const val DARK_TEXT_LUMINANCE = 0.5f

/**
 * Edits one colour role: type a hex value, or dial it in by hue, saturation, brightness and alpha.
 * With [allowAlpha] off the colour stays opaque.
 */
@Composable
fun FlowColorPickerDialog(
    title: String,
    initialArgb: Long,
    onDismiss: () -> Unit,
    onApply: (Long) -> Unit,
    allowAlpha: Boolean = true,
) {
    val hexOf: (Long) -> String = if (allowAlpha) Long::toHexArgb else Long::toHexRgb
    val initialHsv =
        remember(initialArgb) {
            FloatArray(3).also { android.graphics.Color.colorToHSV(initialArgb.toInt(), it) }
        }
    var hue by remember(initialArgb) { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember(initialArgb) { mutableFloatStateOf(initialHsv[1]) }
    var brightness by remember(initialArgb) { mutableFloatStateOf(initialHsv[2]) }
    var alpha by remember(initialArgb) {
        mutableFloatStateOf(if (allowAlpha) android.graphics.Color.alpha(initialArgb.toInt()) / MAX_CHANNEL else 1f)
    }
    val argb = hsvaToArgb(hue, saturation, brightness, alpha)
    var hexInput by remember(initialArgb) { mutableStateOf(caretAtEnd(hexOf(initialArgb))) }
    val hexValid = parseHexColor(hexInput.text) != null

    fun syncHex(value: Long) {
        hexInput = caretAtEnd(hexOf(value))
    }

    FlowAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(ControlSpacing),
            ) {
                val preview = Color(argb)
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(PreviewHeight)
                            .clip(MaterialTheme.shapes.medium)
                            .background(preview)
                            .border(PreviewBorder, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = hexOf(argb),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (preview.luminance() > DARK_TEXT_LUMINANCE) Color.Black else Color.White,
                    )
                }
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { raw ->
                        val sanitized = sanitizeHexInput(raw.text)
                        // The # the field puts back on its own would leave the caret in front of it,
                        // and the next digits would land out of order.
                        hexInput = if (sanitized == raw.text) raw else caretAtEnd(sanitized)
                        parseHexColor(sanitized)?.let { parsed ->
                            val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(parsed.toInt(), it) }
                            hue = hsv[0]
                            saturation = hsv[1]
                            brightness = hsv[2]
                            if (allowAlpha) alpha = android.graphics.Color.alpha(parsed.toInt()) / MAX_CHANNEL
                        }
                    },
                    label = { Text(stringResource(R.string.settings_color_hex)) },
                    supportingText = {
                        Text(
                            stringResource(
                                if (allowAlpha) R.string.appearance_customizer_format_hint else R.string.settings_color_hex_opaque_hint,
                            ),
                        )
                    },
                    isError = !hexValid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
                ColorSlider(R.string.appearance_color_hue, hue, 0f..MAX_HUE) {
                    hue = it
                    syncHex(hsvaToArgb(hue, saturation, brightness, alpha))
                }
                ColorSlider(R.string.appearance_color_saturation, saturation, 0f..1f) {
                    saturation = it
                    syncHex(hsvaToArgb(hue, saturation, brightness, alpha))
                }
                ColorSlider(R.string.appearance_color_brightness, brightness, 0f..1f) {
                    brightness = it
                    syncHex(hsvaToArgb(hue, saturation, brightness, alpha))
                }
                if (allowAlpha) {
                    ColorSlider(R.string.appearance_color_alpha, alpha, 0f..1f) {
                        alpha = it
                        syncHex(hsvaToArgb(hue, saturation, brightness, alpha))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(argb) }, enabled = hexValid) { Text(stringResource(R.string.settings_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun ColorSlider(
    label: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(text = stringResource(label), style = MaterialTheme.typography.labelMedium)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

private fun caretAtEnd(text: String) = TextFieldValue(text, TextRange(text.length))

private fun hsvaToArgb(
    hue: Float,
    saturation: Float,
    brightness: Float,
    alpha: Float,
): Long =
    android.graphics.Color
        .HSVToColor(
            (alpha * MAX_CHANNEL).roundToInt().coerceIn(0, MAX_CHANNEL.toInt()),
            floatArrayOf(hue.coerceIn(0f, MAX_HUE), saturation.coerceIn(0f, 1f), brightness.coerceIn(0f, 1f)),
        ).toLong() and 0xFFFFFFFFL
