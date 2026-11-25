package io.github.aedev.flow.ui.components.videoplayer.controls

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The numbers below are a real storyboard level — 118 frames five seconds apart — on a landscape
 * track roughly 800dp wide at 3x, with 16:9 frames 64dp tall.
 */
private const val INTERVAL_MS = 5_000L
private const val FRAME_COUNT = 118
private const val FRAME_WIDTH = 342f
private const val STRIP_WIDTH = 2400f

class SeekStripGeometryTest {
    private fun framesAt(
        positionMs: Long,
        thumbXPx: Float = STRIP_WIDTH / 2f,
    ) = seekStripFrames(
        positionMs = positionMs,
        intervalMs = INTERVAL_MS,
        frameCount = FRAME_COUNT,
        thumbXPx = thumbXPx,
        frameWidthPx = FRAME_WIDTH,
        stripWidthPx = STRIP_WIDTH,
    )

    @Test
    fun `the frame under the thumb is the one being seeked to`() {
        val position = 62_000L
        val under = framesAt(position).single { it.leftPx <= STRIP_WIDTH / 2f && it.leftPx + FRAME_WIDTH > STRIP_WIDTH / 2f }

        assertThat(under.positionMs).isEqualTo(60_000L)
    }

    @Test
    fun `a position on a frame boundary puts that frame's left edge at the thumb`() {
        val under = framesAt(60_000L).single { it.positionMs == 60_000L }

        assertThat(under.leftPx).isWithin(0.01f).of(STRIP_WIDTH / 2f)
    }

    @Test
    fun `frames sit one frame width apart in the order they play`() {
        val frames = framesAt(62_000L)

        assertThat(frames.map { it.positionMs }).isInOrder()
        frames.zipWithNext { left, right ->
            assertThat(right.leftPx - left.leftPx).isWithin(0.01f).of(FRAME_WIDTH)
            assertThat(right.positionMs - left.positionMs).isEqualTo(INTERVAL_MS)
        }
    }

    @Test
    fun `the strip is filled across its width`() {
        val frames = framesAt(300_000L)

        assertThat(frames.first().leftPx).isAtMost(0f)
        assertThat(frames.last().leftPx + FRAME_WIDTH).isAtLeast(STRIP_WIDTH)
    }

    @Test
    fun `the strip runs out rather than repeating at the start`() {
        val frames = framesAt(0L, thumbXPx = 0f)

        assertThat(frames.first().positionMs).isEqualTo(0L)
        assertThat(frames.first().leftPx).isWithin(0.01f).of(0f)
        assertThat(frames.none { it.positionMs < 0L }).isTrue()
    }

    @Test
    fun `the strip runs out rather than repeating at the end`() {
        val last = (FRAME_COUNT - 1) * INTERVAL_MS
        val frames = framesAt(last + 2_000L, thumbXPx = STRIP_WIDTH)

        assertThat(frames.last().positionMs).isEqualTo(last)
        assertThat(frames.none { it.positionMs > last }).isTrue()
    }

    @Test
    fun `frames scrolled clear of the strip are left out`() {
        val frames = framesAt(300_000L)

        assertThat(frames.all { it.leftPx < STRIP_WIDTH && it.leftPx + FRAME_WIDTH > 0f }).isTrue()
    }

    @Test
    fun `the strip slides faster than the thumb travels`() {
        val before = framesAt(300_000L).first()
        val after =
            seekStripFrames(
                positionMs = 301_000L,
                intervalMs = INTERVAL_MS,
                frameCount = FRAME_COUNT,
                thumbXPx = STRIP_WIDTH / 2f,
                frameWidthPx = FRAME_WIDTH,
                stripWidthPx = STRIP_WIDTH,
            ).single { it.positionMs == before.positionMs }

        // A second of video is a fifth of a frame; the same second moves the thumb a few pixels.
        assertThat(before.leftPx - after.leftPx).isWithin(0.01f).of(FRAME_WIDTH / 5f)
    }

    @Test
    fun `a level with nothing usable in it yields no frames`() {
        assertThat(framesAt(0L)).isNotEmpty()
        assertThat(seekStripFrames(0L, 0L, FRAME_COUNT, 0f, FRAME_WIDTH, STRIP_WIDTH)).isEmpty()
        assertThat(seekStripFrames(0L, INTERVAL_MS, 0, 0f, FRAME_WIDTH, STRIP_WIDTH)).isEmpty()
        assertThat(seekStripFrames(0L, INTERVAL_MS, FRAME_COUNT, 0f, 0f, STRIP_WIDTH)).isEmpty()
        assertThat(seekStripFrames(0L, INTERVAL_MS, FRAME_COUNT, 0f, FRAME_WIDTH, 0f)).isEmpty()
    }

    @Test
    fun `a long video still places frames to the millisecond`() {
        val threeHours = 3 * 60 * 60 * 1_000L
        val frames =
            seekStripFrames(
                positionMs = threeHours - 1_000L,
                intervalMs = INTERVAL_MS,
                frameCount = (threeHours / INTERVAL_MS).toInt(),
                thumbXPx = STRIP_WIDTH / 2f,
                frameWidthPx = FRAME_WIDTH,
                stripWidthPx = STRIP_WIDTH,
            )

        val under = frames.single { it.leftPx <= STRIP_WIDTH / 2f && it.leftPx + FRAME_WIDTH > STRIP_WIDTH / 2f }
        assertThat(under.positionMs).isEqualTo(threeHours - 5_000L)
    }

    @Test
    fun `a live level lays frames across the whole DVR window`() {
        assertThat(stripFrameCount(frameCount = 0, isLive = true, intervalMs = 5_000L, durationMs = 7_200_000L)).isEqualTo(1_440)
        assertThat(stripFrameCount(frameCount = 0, isLive = true, intervalMs = 5_000L, durationMs = 0L)).isEqualTo(0)
        assertThat(stripFrameCount(frameCount = FRAME_COUNT, isLive = false, intervalMs = 5_000L, durationMs = 7_200_000L))
            .isEqualTo(FRAME_COUNT)
    }
}
