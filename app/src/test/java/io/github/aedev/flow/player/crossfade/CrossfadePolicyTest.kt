package io.github.aedev.flow.player.crossfade

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.CrossfadeCurve
import org.junit.Test

class CrossfadePolicyTest {
    private val song =
        CrossfadePolicy.Moment(
            fadeMs = 6_000L,
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
        assertThat(CrossfadePolicy.fadeMediaMs(20_000L, 1f)).isEqualTo(CrossfadePolicy.MAX_MS)
    }

    @Test
    fun `no fade when off, paused, last, a video, the sleep timer's last song or shorter than the fade`() {
        listOf(
            song.copy(fadeMs = 0L),
            song.copy(playing = false),
            song.copy(hasNext = false),
            song.copy(showsVideo = true),
            song.copy(pausesAtEndOfSong = true),
            song.copy(durationMs = 0L),
            song.copy(durationMs = 6_200L),
        ).forEach { assertThat(CrossfadePolicy.fadeStartMs(it)).isNull() }
    }

    @Test
    fun `a short song still fades, and Repeat one fades a song into its own start`() {
        assertThat(CrossfadePolicy.fadeStartMs(song.copy(durationMs = 15_000L))).isEqualTo(9_000L)
        assertThat(CrossfadePolicy.fadeStartMs(song.copy(durationMs = 6_300L))).isEqualTo(300L)
        assertThat(CrossfadePolicy.fadeStartMs(song.copy(repeatsOne = true, hasNext = false))).isEqualTo(194_000L)
        assertThat(CrossfadePolicy.fadeStartMs(song.copy(repeatsOne = true, nextIsSameAlbum = true))).isEqualTo(194_000L)
    }

    @Test
    fun `a song with too little time for a tail fades through on the session player`() {
        val song = { duration: Long -> song.copy(fadeMs = 3_000L, durationMs = duration) }
        val align = { s: CrossfadePolicy.Moment, position: Long ->
            CrossfadePolicy.alignStartMs(CrossfadePolicy.fadeStartMs(s)!!, position, s.durationMs, s.speed, s.fadeMs)
        }

        assertThat(align(song(7_500L), 0L)).isEqualTo(1_500L)
        assertThat(align(song(4_000L), 0L)).isNull()
    }

    @Test
    fun `a fade-through waits for the fade point, then fades what is left`() {
        assertThat(CrossfadePolicy.fadeThrough(positionMs = 190_000L, durationMs = 200_000L, speed = 1f, fadeMs = 3_000L))
            .isEqualTo(CrossfadePolicy.FadeThrough(holdMs = 7_000L, outMs = 3_000L))
        assertThat(CrossfadePolicy.fadeThrough(positionMs = 198_000L, durationMs = 200_000L, speed = 1f, fadeMs = 3_000L))
            .isEqualTo(CrossfadePolicy.FadeThrough(holdMs = 0L, outMs = 2_000L))
        assertThat(CrossfadePolicy.fadeThrough(positionMs = 198_000L, durationMs = 200_000L, speed = 2f, fadeMs = 3_000L))
            .isEqualTo(CrossfadePolicy.FadeThrough(holdMs = 0L, outMs = 1_000L))
    }

    @Test
    fun `a song already at its end has nothing to fade out, only the next song fades in`() {
        assertThat(CrossfadePolicy.fadeThrough(positionMs = 199_900L, durationMs = 200_000L, speed = 1f, fadeMs = 3_000L))
            .isEqualTo(CrossfadePolicy.FadeThrough(holdMs = 100L, outMs = 0L))
        assertThat(CrossfadePolicy.QUICK_FADE_IN_MS).isEqualTo(400L)
    }

    @Test
    fun `a lined-up tail takes over just before the fade, and a short fade can still hand off at its start`() {
        assertThat(CrossfadePolicy.earliestHandOffMs(fadeStartMs = 194_000L, speed = 1f)).isEqualTo(193_500L)
        assertThat(CrossfadePolicy.latestHandOffMs(durationMs = 200_000L, speed = 1f, fadeMs = 6_000L)).isEqualTo(198_000L)
        assertThat(CrossfadePolicy.latestHandOffMs(durationMs = 200_000L, speed = 1f, fadeMs = 250L)).isEqualTo(199_750L)
    }

    @Test
    fun `once a tail has it, the old song fades until it ends`() {
        assertThat(CrossfadePolicy.fadeOutClockMs(positionMs = 196_500L, durationMs = 200_000L, speed = 1f)).isEqualTo(3_500L)
        assertThat(CrossfadePolicy.fadeOutClockMs(positionMs = 196_500L, durationMs = 200_000L, speed = 2f)).isEqualTo(1_750L)
        assertThat(CrossfadePolicy.fadeOutClockMs(positionMs = 200_000L, durationMs = 200_000L, speed = 1f)).isEqualTo(1L)
    }

    @Test
    fun `each song's progress runs on its own clock, and a clock that hasn't started is at 0`() {
        assertThat(CrossfadePolicy.progress(elapsedMs = null, clockMs = 3_000L)).isEqualTo(0f)
        assertThat(CrossfadePolicy.progress(elapsedMs = 1_500L, clockMs = 3_000L)).isWithin(1e-6f).of(0.5f)
        assertThat(CrossfadePolicy.progress(elapsedMs = 9_000L, clockMs = 3_000L)).isEqualTo(1f)
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
        assertThat(
            CrossfadePolicy.alignStartMs(fadeStartMs = 179_000L, positionMs = 100_000L, durationMs = 185_000L, speed = 1f, fadeMs = 6_000L),
        ).isEqualTo(176_000L)
    }

    @Test
    fun `after a seek close to the end the tail gets a moment to buffer first`() {
        assertThat(
            CrossfadePolicy.alignStartMs(fadeStartMs = 179_000L, positionMs = 175_000L, durationMs = 185_000L, speed = 1f, fadeMs = 6_000L),
        ).isEqualTo(176_500L)
        assertThat(
            CrossfadePolicy.alignStartMs(fadeStartMs = 167_000L, positionMs = 166_000L, durationMs = 173_000L, speed = 1f, fadeMs = 6_000L),
        ).isEqualTo(167_500L)
    }

    @Test
    fun `a seek too close to the end leaves the song to end by itself`() {
        assertThat(
            CrossfadePolicy.alignStartMs(fadeStartMs = 179_000L, positionMs = 181_400L, durationMs = 185_000L, speed = 1f, fadeMs = 6_000L),
        ).isNull()
    }

    @Test
    fun `the fade lasts the chosen length, or what is left of the song on the clock`() {
        assertThat(CrossfadePolicy.fadeClockMs(6_000L, positionMs = 170_000L, durationMs = 185_000L, speed = 1f)).isEqualTo(6_000L)
        assertThat(CrossfadePolicy.fadeClockMs(6_000L, positionMs = 181_000L, durationMs = 185_000L, speed = 1f)).isEqualTo(4_000L)
        assertThat(CrossfadePolicy.fadeClockMs(6_000L, positionMs = 181_000L, durationMs = 185_000L, speed = 2f)).isEqualTo(2_000L)
        assertThat(CrossfadePolicy.fadeClockMs(6_000L, positionMs = 185_000L, durationMs = 185_000L, speed = 1f)).isEqualTo(1L)
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
    fun `equal power keeps the loudness all through the fade`() {
        listOf(0.2f, 0.5f, 0.8f).forEach { p ->
            val out = CrossfadePolicy.fadeOutGain(p, CrossfadeCurve.EQUAL_POWER)
            val inn = CrossfadePolicy.fadeInGain(p, CrossfadeCurve.EQUAL_POWER)
            assertThat(out * out + inn * inn).isWithin(1e-5f).of(1f)
        }
    }

    @Test
    fun `halfway through, each curve gives the gains it is named for`() {
        val half = { curve: CrossfadeCurve -> CrossfadePolicy.fadeOutGain(0.5f, curve) to CrossfadePolicy.fadeInGain(0.5f, curve) }

        assertThat(half(CrossfadeCurve.HOLD).first).isWithin(1e-5f).of(0.875f)
        assertThat(half(CrossfadeCurve.HOLD).second).isWithin(1e-4f).of(0.7071f)
        assertThat(half(CrossfadeCurve.GENTLE).first).isWithin(1e-5f).of(0.25f)
        assertThat(half(CrossfadeCurve.GENTLE).second).isWithin(1e-4f).of(0.7071f)
        assertThat(half(CrossfadeCurve.S_CURVE).first).isWithin(1e-5f).of(0.5f)
        assertThat(half(CrossfadeCurve.S_CURVE).second).isWithin(1e-5f).of(0.5f)
    }

    @Test
    fun `every curve only ever lowers the outgoing song and raises the incoming one`() {
        CrossfadeCurve.entries.forEach { curve ->
            (1..20).forEach { step ->
                val before = (step - 1) / 20f
                val after = step / 20f
                assertThat(CrossfadePolicy.fadeOutGain(after, curve)).isAtMost(CrossfadePolicy.fadeOutGain(before, curve))
                assertThat(CrossfadePolicy.fadeInGain(after, curve)).isAtLeast(CrossfadePolicy.fadeInGain(before, curve))
            }
        }
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
    fun `by default a song fades when it ends and when you skip, once a length is chosen`() {
        val defaults = CrossfadePolicy.Settings()

        assertThat(defaults.onAutoAdvance).isTrue()
        assertThat(defaults.onManualSkip).isTrue()
        assertThat(defaults.fadesOnSkip).isFalse()
        assertThat(defaults.copy(fadeMs = 3_000L).fadesOnSkip).isTrue()
    }

    @Test
    fun `the switch decides whether the chosen length plays`() {
        assertThat(CrossfadePolicy.chosenFadeMs(enabled = true, chosenMs = 750)).isEqualTo(750L)
        assertThat(CrossfadePolicy.chosenFadeMs(enabled = false, chosenMs = 750)).isEqualTo(0L)
        assertThat(CrossfadePolicy.chosenFadeMs(enabled = true, chosenMs = 60_000)).isEqualTo(CrossfadePolicy.MAX_MS)
        assertThat(CrossfadePolicy.chosenFadeMs(enabled = true, chosenMs = 0)).isEqualTo(250L)
    }

    @Test
    fun `settings offer short blends, then every whole second up to the longest`() {
        assertThat(CrossfadePolicy.DURATIONS_MS.take(4)).containsExactly(250L, 500L, 750L, 1_000L).inOrder()
        assertThat(CrossfadePolicy.DURATIONS_MS.last()).isEqualTo(CrossfadePolicy.MAX_MS)
        assertThat(CrossfadePolicy.DURATIONS_MS).hasSize(15)
    }

    @Test
    fun `a skip holds for a fade only when fading is on and asked for on a skip`() {
        assertThat(CrossfadePolicy.Settings(fadeMs = 6_000L, onManualSkip = true).fadesOnSkip).isTrue()
        assertThat(CrossfadePolicy.Settings(fadeMs = 0L, onManualSkip = true).fadesOnSkip).isFalse()
        assertThat(CrossfadePolicy.Settings(fadeMs = 6_000L, onManualSkip = false).fadesOnSkip).isFalse()
    }

    @Test
    fun `the first press holds the skip for a new tail, or skips plainly when no tail can start`() {
        val press = { canStart: Boolean -> CrossfadePolicy.onSkipPress(CrossfadePolicy.SkipPhase.NONE, false, canStart) }

        assertThat(press(true)).isEqualTo(CrossfadePolicy.SkipPress.START_TAIL)
        assertThat(press(false)).isEqualTo(CrossfadePolicy.SkipPress.PLAIN)
    }

    @Test
    fun `a press while the tail starts moves the target and builds nothing`() {
        assertThat(CrossfadePolicy.onSkipPress(CrossfadePolicy.SkipPhase.HOLDING, targetIsPlayingSong = false, canStartTail = true))
            .isEqualTo(CrossfadePolicy.SkipPress.MOVE_TARGET)
    }

    @Test
    fun `Next then Previous during the hold stays on the song that never stopped`() {
        assertThat(CrossfadePolicy.onSkipPress(CrossfadePolicy.SkipPhase.HOLDING, targetIsPlayingSong = true, canStartTail = false))
            .isEqualTo(CrossfadePolicy.SkipPress.BACK_TO_PLAYING_SONG)
    }

    @Test
    fun `a press once a tail carries the old song only moves the session under it`() {
        listOf(true, false).forEach { canStart ->
            assertThat(CrossfadePolicy.onSkipPress(CrossfadePolicy.SkipPhase.UNDER_TAIL, false, canStart))
                .isEqualTo(CrossfadePolicy.SkipPress.SEEK_UNDER_TAIL)
        }
    }

    @Test
    fun `a run of presses builds one tail and ends on the last song pressed`() {
        var tails = 0
        var phase = CrossfadePolicy.SkipPhase.NONE
        var target = -1
        var sessionAt = 0

        fun press(next: Int) {
            when (CrossfadePolicy.onSkipPress(phase, next == sessionAt, canStartTail = true)) {
                CrossfadePolicy.SkipPress.START_TAIL -> {
                    tails++
                    phase = CrossfadePolicy.SkipPhase.HOLDING
                    target = next
                }

                CrossfadePolicy.SkipPress.MOVE_TARGET -> {
                    target = next
                }

                CrossfadePolicy.SkipPress.SEEK_UNDER_TAIL -> {
                    sessionAt = next
                }

                else -> {
                    error("not part of this run")
                }
            }
        }

        press(1)
        press(2)
        press(3)
        assertThat(sessionAt).isEqualTo(0)

        sessionAt = target
        phase = CrossfadePolicy.SkipPhase.UNDER_TAIL
        press(4)
        press(5)

        assertThat(sessionAt).isEqualTo(5)
        assertThat(tails).isEqualTo(1)
    }

    @Test
    fun `a skip overlaps for the chosen fade, or just a moment without one`() {
        val fading = CrossfadePolicy.Settings(fadeMs = 6_000L, onManualSkip = true)
        val clock = { settings: CrossfadePolicy.Settings, position: Long ->
            CrossfadePolicy.skipClockMs(settings, position, durationMs = 200_000L, speed = 1f)
        }

        assertThat(clock(fading, 30_000L)).isEqualTo(6_000L)
        assertThat(clock(CrossfadePolicy.Settings(), 30_000L)).isEqualTo(CrossfadePolicy.SKIP_BLEND_MS)
        assertThat(clock(fading.copy(onManualSkip = false), 30_000L)).isEqualTo(CrossfadePolicy.SKIP_BLEND_MS)
        assertThat(clock(CrossfadePolicy.Settings(), 199_900L)).isEqualTo(100L)
    }

    @Test
    fun `only a tail carrying a whole fade straight on from the session starts exactly`() {
        val fading = CrossfadePolicy.Settings(fadeMs = 6_000L, onManualSkip = true)

        assertThat(CrossfadePolicy.tailStartsAtSyncPoint(fading, sessionStillPlaysSong = true)).isFalse()
        assertThat(CrossfadePolicy.tailStartsAtSyncPoint(fading, sessionStillPlaysSong = false)).isTrue()
        assertThat(CrossfadePolicy.tailStartsAtSyncPoint(CrossfadePolicy.Settings(), sessionStillPlaysSong = true)).isTrue()
    }

    @Test
    fun `a fade kept waiting by the next song goes on from where it was`() {
        assertThat(CrossfadePolicy.fadeBeganAtMs(nowMs = 10_000L, progress = 0f, fadeClockMs = 6_000L)).isEqualTo(10_000L)
        assertThat(CrossfadePolicy.fadeBeganAtMs(nowMs = 10_000L, progress = 0.5f, fadeClockMs = 6_000L)).isEqualTo(7_000L)
    }

    @Test
    fun `the lead moves half way to how long the tail took to be heard`() {
        val waited = 700L
        assertThat(CrossfadePolicy.learnedHeadStartMs(headStartMs = 400L, deltaMs = 400L - waited)).isEqualTo(550L)
    }
}
