package io.github.aedev.flow.ui.components.videoplayer.ambient

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class AmbientGlowToneTest {
    // M3 baseline dark background / onBackground and light background / onBackground, in linear light.
    private val dark = AmbientGlowTone(0.0070f, 0.0060f, 0.0091f, textLuminance = 0.76f, gain = GLOW_GAIN_DARK, mix = 0f)
    private val light = AmbientGlowTone(0.991f, 0.930f, 1f, textLuminance = 0.011f, gain = GLOW_MIX_LIGHT, mix = GLOW_MIX_LIGHT)
    private val strip = AmbientGlowTone(0f, 0f, 0f, STATUS_ICON_LUMINANCE, GLOW_GAIN_DARK, 0f)

    private fun AmbientGlowTone.of(
        r: Float,
        g: Float,
        b: Float,
        weight: Float = 1f,
    ) = FloatArray(3).also { apply(r, g, b, it, 0, weight) }

    private fun FloatArray.luminance(at: Int = 0) = relativeLuminance(this[at], this[at + 1], this[at + 2])

    @Test
    fun `dark tone adds a dim share of the light over the background`() {
        val out = dark.of(0.1f, 0.05f, 0.02f)

        assertThat(out[0]).isWithin(1e-5f).of(0.0070f + 0.1f * GLOW_GAIN_DARK)
        assertThat(out[1]).isWithin(1e-5f).of(0.0060f + 0.05f * GLOW_GAIN_DARK)
        assertThat(out[2]).isWithin(1e-5f).of(0.0091f + 0.02f * GLOW_GAIN_DARK)
    }

    @Test
    fun `dark tone holds light text readable over a white frame`() {
        val out = dark.of(1f, 1f, 1f)

        assertThat(contrastRatio(0.76f, out.luminance())).isAtLeast(MIN_TEXT_CONTRAST)
        assertThat(out.luminance()).isGreaterThan(0.05f)
    }

    @Test
    fun `light tone is a pastel of the frame over the background`() {
        val out = light.of(1f, 0f, 0f)

        assertThat(out[0]).isGreaterThan(out[1])
        assertThat(out[1]).isWithin(1e-5f).of(0.930f * (1f - GLOW_MIX_LIGHT))
        assertThat(out.luminance()).isGreaterThan(0.5f)
    }

    @Test
    fun `light tone holds dark text readable over a black frame`() {
        val strongMix = light.copy(gain = 0.9f, mix = 0.9f)
        val out = strongMix.of(0f, 0f, 0f)

        assertThat(contrastRatio(0.011f, out.luminance())).isAtLeast(MIN_TEXT_CONTRAST)
    }

    @Test
    fun `zero weight leaves the plain background`() {
        val out = dark.of(0.8f, 0.4f, 0.2f, weight = 0f)

        assertThat(out.toList()).containsExactly(0.0070f, 0.0060f, 0.0091f).inOrder()
    }

    @Test
    fun `every tone keeps its text readable over any frame`() {
        val random = Random(1377)
        val tones = listOf(dark to 0.76f, light to 0.011f, strip to STATUS_ICON_LUMINANCE)
        repeat(2_000) {
            val r = random.nextFloat()
            val g = random.nextFloat()
            val b = random.nextFloat()
            for ((tone, text) in tones) {
                assertThat(contrastRatio(text, tone.of(r, g, b).luminance())).isAtLeast(MIN_TEXT_CONTRAST)
            }
        }
    }

    @Test
    fun `strip takes the frame's top row`() {
        val width = 4
        val grid = FloatArray(width * 3 * 3) { if (it < width * 3) 0.2f else 0.9f }
        val out = FloatArray(width * 3)

        glowStripLight(grid, width, strip, out)

        assertThat(out[0]).isWithin(1e-5f).of(0.2f * GLOW_GAIN_DARK)
    }

    @Test
    fun `page light keeps column colours at the top and ends on the background`() {
        val width = 2
        val height = 3
        val rows = 5
        // Bottom rows: a red column beside a blue one.
        val grid = FloatArray(width * height * 3)
        for (y in 1 until height) {
            grid[(y * width) * 3] = 0.3f
            grid[(y * width + 1) * 3 + 2] = 0.3f
        }
        val out = FloatArray(width * rows * 3)

        glowPageLight(grid, width, height, rows, dark, out)

        assertThat(out[0]).isGreaterThan(out[2])
        assertThat(out[3 + 2]).isGreaterThan(out[3])
        val last = (rows - 1) * width * 3
        assertThat(out[last]).isWithin(1e-6f).of(0.0070f)
        assertThat(out[last + 3 + 2]).isWithin(1e-6f).of(0.0091f)
        assertThat(out.luminance((rows / 2) * width * 3)).isLessThan(out.luminance(0))
    }
}
