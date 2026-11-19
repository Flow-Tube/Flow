package io.github.aedev.flow.ui.components.videoplayer.ambient

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.stream.StoryboardLevel
import org.junit.Test

class AmbientStoryboardFramesTest {
    private fun level(
        index: Int,
        width: Int,
        intervalMs: Long,
    ) = StoryboardLevel(
        index = index,
        thumbnailWidth = width,
        thumbnailHeight = width * 9 / 16,
        frameCount = 100,
        columns = 10,
        rows = 10,
        intervalMs = intervalMs,
        baseUrl = "https://i.ytimg.com/sb/id/storyboard3_L\$L/\$N.jpg",
        nameTemplate = "M\$M",
        signature = "sig",
    )

    @Test
    fun `the level with the closest frames is used`() {
        val levels = listOf(level(0, 48, 7_400L), level(1, 80, 2_000L), level(2, 160, 2_000L))

        assertThat(ambientStoryboardLevel(levels)?.index).isEqualTo(1)
    }

    @Test
    fun `a short video keeps its single sheet level`() {
        val levels = listOf(level(0, 48, 600L), level(1, 80, 2_000L))

        assertThat(ambientStoryboardLevel(levels)?.index).isEqualTo(0)
    }

    @Test
    fun `levels smaller than the light grid are skipped`() {
        val levels = listOf(level(0, 24, 500L), level(1, 80, 2_000L))

        assertThat(ambientStoryboardLevel(levels)?.index).isEqualTo(1)
    }

    @Test
    fun `no levels means no storyboard light`() {
        assertThat(ambientStoryboardLevel(emptyList())).isNull()
    }

    @Test
    fun `steps are whole storyboard frames at least two seconds apart`() {
        assertThat(storyboardStepMs(2_000L)).isEqualTo(2_000L)
        assertThat(storyboardStepMs(600L)).isEqualTo(2_400L)
        assertThat(storyboardStepMs(7_400L)).isEqualTo(7_400L)
    }

    @Test
    fun `a fade is half a step within its bounds`() {
        assertThat(storyboardFadeMs(2_000L)).isEqualTo(1_000L)
        assertThat(storyboardFadeMs(2_400L)).isEqualTo(1_200L)
        assertThat(storyboardFadeMs(7_400L)).isEqualTo(2_500L)
    }

    @Test
    fun `the next fade is centred on the next frame`() {
        val wait = storyboardDelayMs(positionMs = 10_000L, stepMs = 2_000L, fadeMs = 1_000L, speed = 1f)

        val fadeStart = 10_000L + wait
        assertThat(fadeStart + 500L).isEqualTo(12_000L)
    }

    @Test
    fun `waking a little early still picks the frame it woke for`() {
        val early = 11_500L - 40L
        val target = storyboardTargetMs(early, fadeMs = 1_000L, durationMs = 60_000L)

        assertThat(target / 2_000L).isEqualTo(6L)
        val wait = storyboardDelayMs(early, stepMs = 2_000L, fadeMs = 1_000L, speed = 1f)
        assertThat(early + wait + 500L).isEqualTo(14_000L)
    }

    @Test
    fun `the target never passes the end`() {
        assertThat(storyboardTargetMs(59_900L, fadeMs = 1_000L, durationMs = 60_000L)).isEqualTo(60_000L)
    }

    @Test
    fun `faster playback shortens the wall clock wait`() {
        val normal = storyboardDelayMs(positionMs = 10_000L, stepMs = 2_000L, fadeMs = 1_000L, speed = 1f)
        val double = storyboardDelayMs(positionMs = 10_000L, stepMs = 2_000L, fadeMs = 1_000L, speed = 2f)

        assertThat(double).isEqualTo(normal / 2)
    }

    @Test
    fun `the live frame lights the video wherever it can be read cheaply`() {
        assertThat(ambientUsesStoryboard(hasStoryboard = true, captureSupported = true, hot = false, blockingSurface = false)).isFalse()
        assertThat(ambientUsesStoryboard(hasStoryboard = true, captureSupported = false, hot = false, blockingSurface = false)).isTrue()
        assertThat(ambientUsesStoryboard(hasStoryboard = true, captureSupported = true, hot = true, blockingSurface = false)).isTrue()
        assertThat(ambientUsesStoryboard(hasStoryboard = true, captureSupported = true, hot = false, blockingSurface = true)).isTrue()
        assertThat(ambientUsesStoryboard(hasStoryboard = false, captureSupported = true, hot = true, blockingSurface = true)).isFalse()
    }

    @Test
    fun `chroma boost leaves grey alone and keeps luminance`() {
        val grid = floatArrayOf(0.4f, 0.4f, 0.4f, 0.1f, 0.3f, 0.2f)
        val before = relativeLuminance(0.1f, 0.3f, 0.2f)

        boostChroma(grid, 1.15f)

        assertThat(grid[0]).isWithin(1e-6f).of(0.4f)
        assertThat(relativeLuminance(grid[3], grid[4], grid[5])).isWithin(1e-5f).of(before)
        assertThat(grid[4] - grid[3]).isWithin(1e-5f).of(0.2f * 1.15f)
    }

    @Test
    fun `chroma boost stays inside the gamut`() {
        val grid = floatArrayOf(1f, 0f, 0f)

        boostChroma(grid, 1.15f)

        assertThat(grid.all { it in 0f..1f }).isTrue()
    }

    @Test
    fun `area averaging fills every cell from a larger picture`() {
        val w = 48
        val h = 27
        val src = IntArray(w * h) { i -> if (i % w < w / 2) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }
        val grid = FloatArray(DISPLAY_W * DISPLAY_H * 3)

        areaToGrid(src, w, h, grid)

        assertThat(grid[0]).isWithin(1e-4f).of(1f)
        assertThat(grid[2]).isWithin(1e-4f).of(0f)
        val lastCell = (DISPLAY_W * DISPLAY_H - 1) * 3
        assertThat(grid[lastCell]).isWithin(1e-4f).of(0f)
        assertThat(grid[lastCell + 2]).isWithin(1e-4f).of(1f)
    }

    @Test
    fun `area averaging handles a picture smaller than the grid`() {
        val src = IntArray(16 * 9) { 0xFF808080.toInt() }
        val grid = FloatArray(DISPLAY_W * DISPLAY_H * 3)

        areaToGrid(src, 16, 9, grid)

        assertThat(grid.all { it == srgbToLinear(0x80) }).isTrue()
    }

    @Test
    fun `grid difference and lerp agree at the ends`() {
        val a = FloatArray(6) { 0.2f }
        val b = FloatArray(6) { 0.6f }
        val out = FloatArray(6)

        lerpGrid(a, b, 0.25f, out)

        assertThat(out[0]).isWithin(1e-6f).of(0.3f)
        assertThat(gridDifference(a, b)).isWithin(1e-6f).of(0.4f)
        assertThat(gridDifference(a, a)).isEqualTo(0f)
    }

    @Test
    fun `two plain draws match one blended layer at any alpha`() {
        val background = 0.1f
        val from = 0.8f
        val to = 0.3f
        for (alpha in listOf(1f, 0.6f, 0.25f)) {
            for (progress in listOf(0f, 0.3f, 0.7f, 0.99f)) {
                val toAlpha = alpha * progress
                val fromAlpha = previousAlpha(alpha, progress)
                val afterFrom = background * (1f - fromAlpha) + from * fromAlpha
                val drawn = afterFrom * (1f - toAlpha) + to * toAlpha
                val layer = background * (1f - alpha) + alpha * (from + (to - from) * progress)
                assertThat(drawn).isWithin(1e-5f).of(layer)
            }
        }
    }

    private fun sheet(
        tilesX: Int,
        tilesY: Int,
        padTop: Int,
        padBottom: Int,
        content: (tx: Int, ty: Int) -> Int,
    ): IntArray {
        val w = 48
        val h = 27
        val sheet = IntArray(tilesX * w * tilesY * h)
        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                for (y in 0 until h) {
                    val padded = y < padTop || y >= h - padBottom
                    val grey = if (padded) 20 + (tx * 7 + y) % 30 else content(tx, ty)
                    val c = (0xFF shl 24) or (grey shl 16) or (grey shl 8) or grey
                    for (x in 0 until w) sheet[(ty * h + y) * tilesX * w + tx * w + x] = c
                }
            }
        }
        return sheet
    }

    @Test
    fun `padding rows found in every tile are trimmed`() {
        val pixels = sheet(4, 3, padTop = 1, padBottom = 2) { tx, ty -> 120 + (tx * 31 + ty * 17) % 120 }
        val out = IntArray(4)

        storyboardPadding(pixels, 4 * 48, 3 * 27, 48, 27, out)

        assertThat(out.toList()).containsExactly(1, 2, 0, 0).inOrder()
    }

    @Test
    fun `a sheet without padding keeps every row`() {
        val pixels = sheet(4, 3, padTop = 0, padBottom = 0) { _, _ -> 200 }
        val out = IntArray(4)

        storyboardPadding(pixels, 4 * 48, 3 * 27, 48, 27, out)

        assertThat(out.toList()).containsExactly(0, 0, 0, 0).inOrder()
    }

    @Test
    fun `a dark scene in some tiles is not mistaken for padding`() {
        val pixels = sheet(4, 3, padTop = 0, padBottom = 0) { tx, _ -> if (tx == 0) 200 else 8 }
        val out = IntArray(4)

        storyboardPadding(pixels, 4 * 48, 3 * 27, 48, 27, out)

        assertThat(out.toList()).containsExactly(0, 0, 0, 0).inOrder()
    }

    @Test
    fun `an all dark video is not trimmed`() {
        val pixels = sheet(4, 3, padTop = 0, padBottom = 0) { _, _ -> 30 }
        val out = IntArray(4)

        storyboardPadding(pixels, 4 * 48, 3 * 27, 48, 27, out)

        assertThat(out.toList()).containsExactly(0, 0, 0, 0).inOrder()
    }
}
