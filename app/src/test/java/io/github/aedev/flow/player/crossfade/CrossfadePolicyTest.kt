package io.github.aedev.flow.player.crossfade

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CrossfadePolicyTest {
    private val song =
        CrossfadePolicy.Moment(
            fadeSeconds = 6,
            playing = true,
            hasNext = true,
            repeatsOne = false,
            durationMs = 200_000L,
            speed = 1f,
            showsVideo = false,
            pausesAtEndOfSong = false,
            nextIsSameAlbum = false,
            keepAlbumsGapless = true,
        )

    @Test
    fun `a song with a next one fades from its length minus the fade`() {
        assertThat(CrossfadePolicy.fadeStartMs(song)).isEqualTo(194_000L)
    }

    @Test
    fun `the fade keeps its clock length at other speeds`() {
        assertThat(CrossfadePolicy.fadeStartMs(song.copy(speed = 1.5f))).isEqualTo(191_000L)
        assertThat(CrossfadePolicy.fadeMediaMs(20, 1f)).isEqualTo(CrossfadePolicy.MAX_SECONDS * 1_000L)
    }

    @Test
    fun `no fade when off, paused, last, repeating one, a video, the sleep timer's last song or too short`() {
        listOf(
            song.copy(fadeSeconds = 0),
            song.copy(playing = false),
            song.copy(hasNext = false),
            song.copy(repeatsOne = true),
            song.copy(showsVideo = true),
            song.copy(pausesAtEndOfSong = true),
            song.copy(durationMs = 0L),
            song.copy(durationMs = 15_000L),
        ).forEach { assertThat(CrossfadePolicy.fadeStartMs(it)).isNull() }
    }

    @Test
    fun `an album plays gapless unless the user lets it fade`() {
        assertThat(CrossfadePolicy.fadeStartMs(song.copy(nextIsSameAlbum = true))).isNull()
        assertThat(CrossfadePolicy.fadeStartMs(song.copy(nextIsSameAlbum = true, keepAlbumsGapless = false))).isEqualTo(194_000L)
    }

    @Test
    fun `equal-power gains start and end where they should and keep the power constant`() {
        assertThat(CrossfadePolicy.fadeOutGain(0f)).isWithin(1e-6f).of(1f)
        assertThat(CrossfadePolicy.fadeOutGain(1f)).isWithin(1e-6f).of(0f)
        assertThat(CrossfadePolicy.fadeInGain(0f)).isWithin(1e-6f).of(0f)
        assertThat(CrossfadePolicy.fadeInGain(1f)).isWithin(1e-6f).of(1f)
        listOf(0.1f, 0.5f, 0.9f).forEach { p ->
            val power = CrossfadePolicy.fadeOutGain(p).let { it * it } + CrossfadePolicy.fadeInGain(p).let { it * it }
            assertThat(power).isWithin(1e-5f).of(1f)
        }
    }

    @Test
    fun `a tail ahead slows down, one behind speeds up, and an aligned one keeps the base speed`() {
        assertThat(CrossfadePolicy.nudgedSpeed(1f, deltaMs = 80L, remainingMs = 2_000L)).isLessThan(1f)
        assertThat(CrossfadePolicy.nudgedSpeed(1f, deltaMs = -80L, remainingMs = 2_000L)).isGreaterThan(1f)
        assertThat(CrossfadePolicy.nudgedSpeed(1.25f, deltaMs = 5L, remainingMs = 2_000L)).isEqualTo(1.25f)
        assertThat(CrossfadePolicy.nudgedSpeed(1f, deltaMs = 5_000L, remainingMs = 100L)).isWithin(1e-6f).of(0.92f)
    }
}
