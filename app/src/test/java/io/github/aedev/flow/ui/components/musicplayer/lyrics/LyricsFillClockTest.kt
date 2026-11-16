package io.github.aedev.flow.ui.components.musicplayer.lyrics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LyricsFillClockTest {
    private val frameMs = 8L

    /** Runs frames over a player position that gains [jumpMs] at frame [jumpAt]. */
    private fun run(
        frames: Int,
        jumpAt: Int,
        jumpMs: Long,
        speed: Float = 1f,
    ): List<Pair<Long, Long>> {
        val clock = LyricsFillClock()
        var offsetMs = 0L
        return (0 until frames).map { frame ->
            if (frame == jumpAt) offsetMs = jumpMs
            val player = 10_000L + (frame * frameMs * speed).toLong() + offsetMs
            player to clock.next(player, 1_000L + frame * frameMs, speed)
        }
    }

    @Test
    fun `it starts at the player's position`() {
        assertThat(LyricsFillClock().next(12_345L, 1_000L, 1f)).isEqualTo(12_345L)
    }

    @Test
    fun `a backward correction never moves the fill back`() {
        val shown = run(frames = 200, jumpAt = 50, jumpMs = -230L).map { it.second }

        shown.zipWithNext().forEach { (before, after) -> assertThat(after).isAtLeast(before) }
    }

    @Test
    fun `a backward correction is worked off`() {
        val (player, shown) = run(frames = 200, jumpAt = 50, jumpMs = -230L).last()

        assertThat(shown - player).isAtMost(1L)
    }

    @Test
    fun `a forward correction is worked off without a jump`() {
        val frames = run(frames = 100, jumpAt = 50, jumpMs = 80L)
        val shown = frames.map { it.second }

        shown.zipWithNext().forEach { (before, after) -> assertThat(after - before).isAtMost(13L) }
        assertThat(frames.last().first - frames.last().second).isAtMost(1L)
    }

    @Test
    fun `a seek goes straight to the player in either direction`() {
        assertThat(run(frames = 60, jumpAt = 50, jumpMs = -5_000L)[50].let { it.first - it.second }).isEqualTo(0L)
        assertThat(run(frames = 60, jumpAt = 50, jumpMs = 5_000L)[50].let { it.first - it.second }).isEqualTo(0L)
    }

    @Test
    fun `it keeps up at other playback speeds`() {
        listOf(0.5f, 1.5f, 2f).forEach { speed ->
            val (player, shown) = run(frames = 400, jumpAt = -1, jumpMs = 0L, speed = speed).last()

            assertThat(player - shown).isIn(-2L..2L)
        }
    }
}
