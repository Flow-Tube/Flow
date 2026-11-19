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
    fun `the single sheet level is used while it changes often enough`() {
        val levels = listOf(level(0, 48, 6_000L), level(1, 80, 2_000L), level(2, 160, 2_000L))

        assertThat(ambientStoryboardLevel(levels)?.index).isEqualTo(0)
    }

    @Test
    fun `a long video moves to a finer level`() {
        val levels = listOf(level(0, 48, 72_000L), level(1, 80, 2_000L), level(2, 160, 2_000L))

        assertThat(ambientStoryboardLevel(levels)?.index).isEqualTo(1)
    }

    @Test
    fun `levels smaller than the light grid are skipped`() {
        val levels = listOf(level(0, 24, 2_000L), level(1, 80, 2_000L))

        assertThat(ambientStoryboardLevel(levels)?.index).isEqualTo(1)
    }

    @Test
    fun `with every level too coarse the most frequent one is used`() {
        val levels = listOf(level(0, 48, 90_000L), level(1, 80, 20_000L))

        assertThat(ambientStoryboardLevel(levels)?.index).isEqualTo(1)
    }

    @Test
    fun `no levels means no storyboard light`() {
        assertThat(ambientStoryboardLevel(emptyList())).isNull()
    }

    @Test
    fun `the target looks ahead but never past the end`() {
        assertThat(storyboardTargetMs(10_000L, 60_000L)).isEqualTo(10_000L + STORYBOARD_LOOKAHEAD_MS)
        assertThat(storyboardTargetMs(59_000L, 60_000L)).isEqualTo(60_000L)
        assertThat(storyboardTargetMs(5_000L, 0L)).isEqualTo(5_000L + STORYBOARD_LOOKAHEAD_MS)
    }

    @Test
    fun `the next fetch lands on the first new frame after the update interval`() {
        val delay = storyboardDelayMs(targetMs = 12_500L, intervalMs = 2_000L, speed = 1f)

        val next = 12_500L + delay
        assertThat(next % 2_000L).isEqualTo(0L)
        assertThat(delay).isAtLeast(STORYBOARD_UPDATE_MS)
        assertThat(delay).isLessThan(STORYBOARD_UPDATE_MS + 2_000L)
    }

    @Test
    fun `a coarse level waits for its own next frame`() {
        val delay = storyboardDelayMs(targetMs = 3_000L, intervalMs = 6_000L, speed = 1f)

        assertThat(delay).isEqualTo(9_000L)
    }

    @Test
    fun `faster playback shortens the wall clock wait`() {
        val normal = storyboardDelayMs(targetMs = 12_500L, intervalMs = 2_000L, speed = 1f)
        val double = storyboardDelayMs(targetMs = 12_500L, intervalMs = 2_000L, speed = 2f)

        assertThat(double).isEqualTo(normal / 2)
    }

    @Test
    fun `very fast playback still waits a second`() {
        assertThat(storyboardDelayMs(targetMs = 0L, intervalMs = 2_000L, speed = 16f)).isEqualTo(1_000L)
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
}
