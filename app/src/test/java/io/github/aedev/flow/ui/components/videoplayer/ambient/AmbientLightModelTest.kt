package io.github.aedev.flow.ui.components.videoplayer.ambient

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class AmbientLightModelTest {
    private val width = 4
    private val height = 18
    private val rows = 40
    private val dark = AmbientGlowTone(0.0070f, 0.0060f, 0.0091f, textLuminance = 0.76f, gain = IMMERSIVE_GAIN_DARK, mix = 0f)
    private val light = AmbientGlowTone(0.991f, 0.930f, 1f, textLuminance = 0.011f, gain = IMMERSIVE_MIX_LIGHT, mix = IMMERSIVE_MIX_LIGHT)

    /** Column x is lit only in red, and only in its bottom half: a picture with distinct shapes. */
    private fun shapedGrid() =
        FloatArray(width * height * 3).also { grid ->
            for (y in height / 2 until height) {
                for (x in 0 until width) grid[(y * width + x) * 3] = 0.05f * (x + 1)
            }
        }

    private fun light(
        grid: FloatArray,
        tone: AmbientGlowTone = dark,
        nearFraction: Float = 0.25f,
    ) = FloatArray(width * rows * 3).also { pageLight(grid, width, height, rows, nearFraction, tone, it, FloatArray(width * 3)) }

    private fun FloatArray.red(
        row: Int,
        column: Int,
    ) = this[(row * width + column) * 3]

    private fun FloatArray.luminance(
        row: Int,
        column: Int,
    ): Float {
        val o = (row * width + column) * 3
        return relativeLuminance(this[o], this[o + 1], this[o + 2])
    }

    @Test
    fun `next to the player the light keeps the picture's columns apart`() {
        val out = light(shapedGrid())

        for (x in 1 until width) assertThat(out.red(0, x)).isGreaterThan(out.red(0, x - 1))
        assertThat(out.red(0, width - 1)).isWithin(1e-5f).of(0.0070f + 0.2f * IMMERSIVE_GAIN_DARK)
    }

    @Test
    fun `far from the player the light is each column's average`() {
        val out = light(shapedGrid(), nearFraction = 0.2f)
        // Row at 45% of the page: past two near zones, before the bottom fall-off begins.
        val row = (rows - 1) * 45 / 100
        val average = 0.05f * width * (height / 2) / height

        assertThat(out.red(row, width - 1)).isWithin(1e-4f).of(0.0070f + average * IMMERSIVE_GAIN_DARK)
    }

    @Test
    fun `light falls off toward the page at the bottom`() {
        val flat = FloatArray(width * height * 3) { 0.2f }
        val out = light(flat)

        assertThat(out.luminance(rows - 1, 0)).isLessThan(out.luminance(rows / 2, 0))
        assertThat(out.luminance(rows - 1, 0)).isGreaterThan(relativeLuminance(0.0070f, 0.0060f, 0.0091f))
    }

    @Test
    fun `text stays readable over any picture in both themes`() {
        val random = Random(1882)
        repeat(50) {
            val grid = FloatArray(width * height * 3) { random.nextFloat() }
            val outDark = light(grid, dark)
            val outLight = light(grid, light)
            for (row in 0 until rows) {
                for (x in 0 until width) {
                    assertThat(contrastRatio(0.76f, outDark.luminance(row, x))).isAtLeast(MIN_TEXT_CONTRAST)
                    assertThat(contrastRatio(0.011f, outLight.luminance(row, x))).isAtLeast(MIN_TEXT_CONTRAST)
                }
            }
        }
    }
}
