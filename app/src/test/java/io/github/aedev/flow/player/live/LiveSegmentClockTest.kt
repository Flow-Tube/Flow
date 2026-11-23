package io.github.aedev.flow.player.live

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Heads are the ones googlevideo reported for two 24/7 streams on 23 Nov. */
class LiveSegmentClockTest {
    private val newsHead = LiveHead(sequence = 2_028_589, mediaTimeMs = 10_094_662_121, serverTimeMs = 1_795_449_845_151)
    private val radioHead = LiveHead(sequence = 4_347_676, mediaTimeMs = 21_738_287_133, serverTimeMs = 1_795_449_861_491)
    private val twelveHoursMs = 43_200_000L

    @Test
    fun `segments take the stream's measured average, in microseconds`() {
        assertThat(LiveSegmentClock(newsHead, 5_000, twelveHoursMs).segmentDurationUs).isEqualTo(4_976_198)
        assertThat(LiveSegmentClock(radioHead, 5_000, twelveHoursMs).segmentDurationUs).isEqualTo(4_999_978)
    }

    @Test
    fun `a head whose time does not count from sequence 0 falls back inside the clamp`() {
        val restarted = newsHead.copy(mediaTimeMs = 600_000)
        val clock = LiveSegmentClock(restarted, 5_000, twelveHoursMs)
        assertThat(clock.segmentDurationUs).isEqualTo(4_900_000)
        assertThat(LiveSegmentClock(newsHead.copy(sequence = 0), 5_000, twelveHoursMs).segmentDurationUs).isEqualTo(5_000_000)
    }

    @Test
    fun `the head lines up exactly with its own media time`() {
        val clock = LiveSegmentClock(newsHead, 5_000, twelveHoursMs)
        val templateTimeOfHeadUs = newsHead.sequence * clock.segmentDurationUs + clock.presentationTimeOffsetUs
        assertThat(templateTimeOfHeadUs).isEqualTo(newsHead.mediaTimeMs * 1_000)
    }

    @Test
    fun `the head becomes available at the moment it was reported`() {
        val clock = LiveSegmentClock(radioHead, 5_000, twelveHoursMs)
        val headAvailableAtMs = clock.availabilityStartTimeMs + (radioHead.sequence + 1) * clock.segmentDurationUs / 1_000
        assertThat(headAvailableAtMs).isEqualTo(radioHead.serverTimeMs)
    }

    @Test
    fun `the window is twelve hours on a long stream and the whole stream on a young one`() {
        assertThat(LiveSegmentClock(newsHead, 5_000, twelveHoursMs).timeShiftBufferDepthMs).isEqualTo(twelveHoursMs)
        val young = LiveHead(sequence = 719, mediaTimeMs = 3_595_000, serverTimeMs = 1_795_449_845_151)
        assertThat(LiveSegmentClock(young, 5_000, twelveHoursMs).timeShiftBufferDepthMs).isEqualTo(3_600_000)
    }

    @Test
    fun `segments that come slower than the average move availability later, never earlier`() {
        val clock = LiveSegmentClock(newsHead, 5_000, twelveHoursMs)
        val before = clock.availabilityStartTimeMs
        // 120 segments of exactly 5 s, against a 4.976 s average.
        clock.observe(newsHead.copy(sequence = newsHead.sequence + 120, serverTimeMs = newsHead.serverTimeMs + 600_000))
        assertThat(clock.availabilityStartTimeMs).isEqualTo(before + 2_856)
    }

    @Test
    fun `a head seen late in its segment does not move the edge forward`() {
        val clock = LiveSegmentClock(radioHead, 5_000, twelveHoursMs)
        val before = clock.availabilityStartTimeMs
        clock.observe(radioHead.copy(serverTimeMs = radioHead.serverTimeMs + 4_000))
        assertThat(clock.availabilityStartTimeMs).isEqualTo(before)
    }

    @Test
    fun `an older head is ignored`() {
        val clock = LiveSegmentClock(radioHead, 5_000, twelveHoursMs)
        clock.observe(radioHead.copy(sequence = radioHead.sequence - 5, serverTimeMs = radioHead.serverTimeMs + 1_000))
        assertThat(clock.head).isEqualTo(radioHead)
    }
}
