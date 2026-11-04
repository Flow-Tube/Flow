package io.github.aedev.flow.player.crossfade

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.CrossfadeCurve
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
        assertThat(CrossfadePolicy.nudgedSpeed(1f, deltaMs = 80L)).isLessThan(1f)
        assertThat(CrossfadePolicy.nudgedSpeed(1f, deltaMs = -80L)).isGreaterThan(1f)
        assertThat(CrossfadePolicy.nudgedSpeed(1.25f, deltaMs = 5L)).isEqualTo(1.25f)
        assertThat(CrossfadePolicy.nudgedSpeed(1f, deltaMs = 5_000L)).isWithin(1e-6f).of(0.85f)
        assertThat(CrossfadePolicy.nudgedSpeed(1f, deltaMs = 35L)).isWithin(1e-6f).of(0.95f)
    }

    @Test
    fun `the next tail moves half way to what this one needed, within limits`() {
        assertThat(CrossfadePolicy.learnedHeadStartMs(headStartMs = 100L, deltaMs = -200L)).isEqualTo(200L)
        assertThat(CrossfadePolicy.learnedHeadStartMs(headStartMs = 300L, deltaMs = 40L)).isEqualTo(280L)
        assertThat(CrossfadePolicy.learnedHeadStartMs(headStartMs = 300L, deltaMs = -2_000L)).isEqualTo(800L)
        assertThat(CrossfadePolicy.learnedHeadStartMs(headStartMs = 50L, deltaMs = 500L)).isEqualTo(0L)
    }

    @Test
    fun `a position is extended from the moment it last moved, and only trusted once it has moved`() {
        val anchor = PositionAnchor()

        assertThat(anchor.estimate(positionMs = 1_000L, nowMs = 0L, speed = 1f)).isEqualTo(1_000L)
        assertThat(anchor.anchored).isFalse()

        assertThat(anchor.estimate(positionMs = 1_000L, nowMs = 100L, speed = 1f)).isEqualTo(1_100L)
        assertThat(anchor.estimate(positionMs = 1_250L, nowMs = 240L, speed = 1f)).isEqualTo(1_250L)
        assertThat(anchor.anchored).isTrue()

        assertThat(anchor.estimate(positionMs = 1_250L, nowMs = 340L, speed = 0.5f)).isEqualTo(1_300L)
    }

    @Test
    fun `with time to spare the tail lines up at the usual point`() {
        assertThat(CrossfadePolicy.alignStartMs(fadeStartMs = 179_000L, positionMs = 100_000L, durationMs = 185_000L, speed = 1f))
            .isEqualTo(176_000L)
    }

    @Test
    fun `after a seek close to the end the tail gets a moment to buffer first`() {
        assertThat(CrossfadePolicy.alignStartMs(fadeStartMs = 179_000L, positionMs = 175_000L, durationMs = 185_000L, speed = 1f))
            .isEqualTo(176_500L)
        assertThat(CrossfadePolicy.alignStartMs(fadeStartMs = 167_000L, positionMs = 166_000L, durationMs = 173_000L, speed = 1f))
            .isEqualTo(167_500L)
    }

    @Test
    fun `a seek too close to the end leaves the song to end by itself`() {
        assertThat(CrossfadePolicy.alignStartMs(fadeStartMs = 179_000L, positionMs = 181_400L, durationMs = 185_000L, speed = 1f))
            .isNull()
    }

    @Test
    fun `the fade lasts the chosen seconds, or what is left of the song on the clock`() {
        assertThat(CrossfadePolicy.fadeClockMs(6, positionMs = 170_000L, durationMs = 185_000L, speed = 1f)).isEqualTo(6_000L)
        assertThat(CrossfadePolicy.fadeClockMs(6, positionMs = 181_000L, durationMs = 185_000L, speed = 1f)).isEqualTo(4_000L)
        assertThat(CrossfadePolicy.fadeClockMs(6, positionMs = 181_000L, durationMs = 185_000L, speed = 2f)).isEqualTo(2_000L)
        assertThat(CrossfadePolicy.fadeClockMs(6, positionMs = 185_000L, durationMs = 185_000L, speed = 1f)).isEqualTo(1L)
    }

    @Test
    fun `every curve starts on the outgoing song and ends on the incoming one`() {
        CrossfadeCurve.entries.forEach { curve ->
            assertThat(CrossfadePolicy.fadeOutGain(0f, curve)).isWithin(1e-5f).of(1f)
            assertThat(CrossfadePolicy.fadeInGain(0f, curve)).isWithin(1e-5f).of(0f)
            assertThat(CrossfadePolicy.fadeOutGain(1f, curve)).isWithin(1e-5f).of(0f)
            assertThat(CrossfadePolicy.fadeInGain(1f, curve)).isWithin(1e-5f).of(1f)
        }
    }

    @Test
    fun `equal-power curves keep the loudness, and gentle dips in the middle`() {
        listOf(CrossfadeCurve.EQUAL_POWER, CrossfadeCurve.S_CURVE, CrossfadeCurve.HOLD).forEach { curve ->
            listOf(0.2f, 0.5f, 0.8f).forEach { p ->
                val out = CrossfadePolicy.fadeOutGain(p, curve)
                val inn = CrossfadePolicy.fadeInGain(p, curve)
                assertThat(out * out + inn * inn).isWithin(1e-5f).of(1f)
            }
        }
        val out = CrossfadePolicy.fadeOutGain(0.5f, CrossfadeCurve.GENTLE)
        val inn = CrossfadePolicy.fadeInGain(0.5f, CrossfadeCurve.GENTLE)
        assertThat(out + inn).isWithin(1e-5f).of(1f)
        assertThat(out * out + inn * inn).isLessThan(0.9f)
    }

    @Test
    fun `hold keeps the outgoing song up longer than equal power`() {
        assertThat(CrossfadePolicy.fadeOutGain(0.5f, CrossfadeCurve.HOLD))
            .isGreaterThan(CrossfadePolicy.fadeOutGain(0.5f, CrossfadeCurve.EQUAL_POWER))
    }

    @Test
    fun `a stored curve reads back, and anything else is equal power`() {
        CrossfadeCurve.entries.forEach { assertThat(CrossfadeCurve.fromString(it.name)).isEqualTo(it) }
        assertThat(CrossfadeCurve.fromString(null)).isEqualTo(CrossfadeCurve.EQUAL_POWER)
        assertThat(CrossfadeCurve.fromString("SOMETHING_NEW")).isEqualTo(CrossfadeCurve.EQUAL_POWER)
    }

    @Test
    fun `a skipped song comes back ahead of where it stopped`() {
        assertThat(CrossfadePolicy.skipResumeMs(positionMs = 30_000L, leadMs = 400L, durationMs = 200_000L, failed = false))
            .isEqualTo(30_400L)
    }

    @Test
    fun `a song barely heard, near its end, failed or of unknown length just stops on a skip`() {
        val skip = { position: Long, duration: Long?, failed: Boolean -> CrossfadePolicy.skipResumeMs(position, 400L, duration, failed) }

        assertThat(skip(900L, 200_000L, false)).isNull()
        assertThat(skip(198_000L, 200_000L, false)).isNull()
        assertThat(skip(30_000L, 200_000L, true)).isNull()
        assertThat(skip(30_000L, null, false)).isNull()
    }

    @Test
    fun `by default a song fades when it ends and Next stays immediate`() {
        val defaults = CrossfadePolicy.Settings()

        assertThat(defaults.onAutoAdvance).isTrue()
        assertThat(defaults.onManualSkip).isFalse()
    }
}
