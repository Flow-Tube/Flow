package io.github.aedev.flow.ui.components.videoplayer.ambient

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.util.Half
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.nio.ShortBuffer

/** Rows of light under the player; bilinear upscaling turns them into one smooth fade. */
internal const val GLOW_PAGE_ROWS = 16

/** What the page around the player wants rendered from each frame. */
internal sealed interface AmbientPageStyle {
    val strip: AmbientGlowTone

    data class Glow(
        override val strip: AmbientGlowTone,
        val page: AmbientGlowTone,
    ) : AmbientPageStyle

    /** [nearFraction]: how much of the page's height one player height takes, where the light keeps the picture's shapes. */
    data class Immersive(
        override val strip: AmbientGlowTone,
        val page: AmbientGlowTone,
        val nearFraction: Float,
    ) : AmbientPageStyle
}

/** The light outside the player: [strip] behind the status bar and [page] below the video. */
class AmbientPageFrame internal constructor(
    val strip: ImageBitmap,
    val page: ImageBitmap,
)

/**
 * Turns the smoothed grid into the page's light. The bitmaps are half float in linear light: an
 * 8-bit fade stretched over a few hundred pixels shows steps, which is the banding this avoids.
 * Double buffered like the bars, since the render thread may still be uploading the last pair.
 */
internal class AmbientPageRenderer(
    private val gridWidth: Int,
    private val gridHeight: Int,
) {
    private val linear = ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB)
    private val strips = Array(2) { halfFloatBitmap(gridWidth, 1) }
    private val glowPages by lazy { Array(2) { halfFloatBitmap(gridWidth, GLOW_PAGE_ROWS) } }
    private val immersivePages by lazy { Array(2) { halfFloatBitmap(gridWidth, IMMERSIVE_ROWS) } }
    private val rows = maxOf(GLOW_PAGE_ROWS, IMMERSIVE_ROWS)
    private val light = FloatArray(gridWidth * rows * 3)
    private val halves = ShortArray(gridWidth * rows * 4)
    private val columns = FloatArray(gridWidth * 3)
    private var index = 0

    fun render(
        grid: FloatArray,
        style: AmbientPageStyle,
    ): AmbientPageFrame {
        index = index xor 1
        glowStripLight(grid, gridWidth, style.strip, light)
        val strip = upload(strips[index], gridWidth)
        val page =
            when (style) {
                is AmbientPageStyle.Glow -> {
                    glowPageLight(grid, gridWidth, gridHeight, GLOW_PAGE_ROWS, style.page, light)
                    upload(glowPages[index], gridWidth * GLOW_PAGE_ROWS)
                }

                is AmbientPageStyle.Immersive -> {
                    pageLight(grid, gridWidth, gridHeight, IMMERSIVE_ROWS, style.nearFraction, style.page, light, columns)
                    upload(immersivePages[index], gridWidth * IMMERSIVE_ROWS)
                }
            }
        return AmbientPageFrame(strip.asImageBitmap(), page.asImageBitmap())
    }

    private fun halfFloatBitmap(
        width: Int,
        height: Int,
    ): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGBA_F16, false, linear)

    private fun upload(
        bitmap: Bitmap,
        pixels: Int,
    ): Bitmap {
        for (i in 0 until pixels) {
            val o = i * 3
            val h = i * 4
            halves[h] = Half.toHalf(light[o])
            halves[h + 1] = Half.toHalf(light[o + 1])
            halves[h + 2] = Half.toHalf(light[o + 2])
            halves[h + 3] = HALF_ONE
        }
        bitmap.copyPixelsFromBuffer(ShortBuffer.wrap(halves, 0, pixels * 4))
        return bitmap
    }
}

/** 1.0 in IEEE half precision. */
private const val HALF_ONE: Short = 0x3C00
