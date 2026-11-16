package io.github.aedev.flow.ui.components.videoplayer.ambient

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlin.math.max
import kotlin.math.min

/** WCAG 2 AA for body text. */
internal const val MIN_TEXT_CONTRAST = 4.5f

/** Aimed a little above the minimum so encoding the light never rounds a pixel below it. */
private const val CONTRAST_AIM = 4.6f

/** Dark surfaces: the share of the frame's light added on top of the surface. */
internal const val GLOW_GAIN_DARK = 0.32f

/** Light surfaces: the share of the surface given up to the frame's light, which makes it a pastel. */
internal const val GLOW_MIX_LIGHT = 0.30f

/** The status bar over the expanded player always has light icons. */
internal const val STATUS_ICON_LUMINANCE = 1f

internal fun relativeLuminance(
    r: Float,
    g: Float,
    b: Float,
): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b

internal fun contrastRatio(
    luminanceA: Float,
    luminanceB: Float,
): Float = (max(luminanceA, luminanceB) + 0.05f) / (min(luminanceA, luminanceB) + 0.05f)

/**
 * How the frame's light lands on one surface, in linear light. A dark surface keeps its colour and
 * gains [gain] of the light; a light one gives [mix] of itself up to it. Either way the result is
 * then pulled back toward the surface until text of [textLuminance] keeps [MIN_TEXT_CONTRAST].
 */
@Immutable
internal data class AmbientGlowTone(
    val backgroundR: Float,
    val backgroundG: Float,
    val backgroundB: Float,
    val textLuminance: Float,
    val gain: Float,
    val mix: Float,
) {
    private val backgroundLuminance = relativeLuminance(backgroundR, backgroundG, backgroundB)
    private val textIsLighter = textLuminance > backgroundLuminance
    private val luminanceLimit =
        if (textIsLighter) {
            (textLuminance + 0.05f) / CONTRAST_AIM - 0.05f
        } else {
            CONTRAST_AIM * (textLuminance + 0.05f) - 0.05f
        }

    /**
     * Writes the toned light for one linear [r], [g], [b] sample into [out] at [at]. [weight] fades
     * it toward the plain surface, 1 keeping all of it and 0 none.
     */
    fun apply(
        r: Float,
        g: Float,
        b: Float,
        out: FloatArray,
        at: Int,
        weight: Float = 1f,
    ) {
        val keep = 1f - mix
        val litR = backgroundR * keep + r * gain
        val litG = backgroundG * keep + g * gain
        val litB = backgroundB * keep + b * gain
        val lit = relativeLuminance(litR, litG, litB)
        // Luminance is linear in linear light, so the step back toward the surface that meets the
        // contrast bound exactly has a closed form.
        val held =
            when {
                textIsLighter && lit > luminanceLimit -> (luminanceLimit - backgroundLuminance) / (lit - backgroundLuminance)
                !textIsLighter && lit < luminanceLimit -> (backgroundLuminance - luminanceLimit) / (backgroundLuminance - lit)
                else -> 1f
            }.takeIf { it.isFinite() } ?: 0f
        val t = held.coerceIn(0f, 1f) * weight.coerceIn(0f, 1f)
        out[at] = backgroundR + (litR - backgroundR) * t
        out[at + 1] = backgroundG + (litG - backgroundG) * t
        out[at + 2] = backgroundB + (litB - backgroundB) * t
    }

    companion object {
        /** The tone for a page of [background] carrying [text], from the two theme colours. */
        fun forSurface(
            background: Color,
            text: Color,
            darkGain: Float = GLOW_GAIN_DARK,
            lightMix: Float = GLOW_MIX_LIGHT,
        ): AmbientGlowTone {
            val bg = background.convert(ColorSpaces.LinearSrgb)
            val fg = text.convert(ColorSpaces.LinearSrgb)
            val textLuminance = relativeLuminance(fg.red, fg.green, fg.blue)
            val dark = textLuminance > relativeLuminance(bg.red, bg.green, bg.blue)
            return AmbientGlowTone(
                backgroundR = bg.red,
                backgroundG = bg.green,
                backgroundB = bg.blue,
                textLuminance = textLuminance,
                gain = if (dark) darkGain else lightMix,
                mix = if (dark) 0f else lightMix,
            )
        }

        /** The status-bar strip: the player's black ground under light icons, on every theme. */
        fun forStatusBar(ground: Color): AmbientGlowTone {
            val bg = ground.convert(ColorSpaces.LinearSrgb)
            return AmbientGlowTone(bg.red, bg.green, bg.blue, STATUS_ICON_LUMINANCE, GLOW_GAIN_DARK, 0f)
        }
    }
}

/** The light along the top edge of the frame, one value per column, for the status-bar strip. */
internal fun glowStripLight(
    grid: FloatArray,
    width: Int,
    tone: AmbientGlowTone,
    out: FloatArray,
) {
    for (x in 0 until width) {
        val o = x * 3
        tone.apply(grid[o], grid[o + 1], grid[o + 2], out, o)
    }
}

/**
 * The light under the player: the frame's bottom two rows stretched down over [rows] rows, fading
 * into the plain page by the last one so the area below the glow joins it without an edge.
 */
internal fun glowPageLight(
    grid: FloatArray,
    width: Int,
    height: Int,
    rows: Int,
    tone: AmbientGlowTone,
    out: FloatArray,
) {
    val last = (height - 1) * width * 3
    val above = (height - 2).coerceAtLeast(0) * width * 3
    for (y in 0 until rows) {
        val weight = 1f - smoothStep(y / (rows - 1).coerceAtLeast(1).toFloat())
        for (x in 0 until width) {
            val c = x * 3
            tone.apply(
                (grid[last + c] + grid[above + c]) / 2f,
                (grid[last + c + 1] + grid[above + c + 1]) / 2f,
                (grid[last + c + 2] + grid[above + c + 2]) / 2f,
                out,
                (y * width + x) * 3,
                weight,
            )
        }
    }
}

internal fun smoothStep(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}
