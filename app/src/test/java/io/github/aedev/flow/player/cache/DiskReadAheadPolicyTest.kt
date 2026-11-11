package io.github.aedev.flow.player.cache

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.cache.DiskReadAheadPolicy.OFF
import io.github.aedev.flow.player.cache.DiskReadAheadPolicy.PLAYER_MARGIN_MS
import io.github.aedev.flow.player.cache.DiskReadAheadPolicy.WHOLE_VIDEO
import org.junit.Test

/** #1696: what the disk read-ahead writes, and when it stops. */
class DiskReadAheadPolicyTest {
    private val minute = 60_000L
    private val hour = 60 * minute

    @Test
    fun `off plans nothing and never tops up`() {
        assertThat(DiskReadAheadPolicy.plan(OFF, 0L, 0L, hour)).isNull()
        assertThat(DiskReadAheadPolicy.needsTopUp(OFF, 0L, 0L, hour)).isFalse()
    }

    @Test
    fun `the window starts past the player's own buffer and ends minutes after the position`() {
        val window = DiskReadAheadPolicy.plan(10, positionMs = minute, bufferedPositionMs = 2 * minute, durationMs = hour)

        assertThat(window!!.first).isEqualTo(2 * minute + PLAYER_MARGIN_MS)
        assertThat(window.last).isEqualTo(11 * minute - 1)
    }

    @Test
    fun `the window stops at the end of the video`() {
        val window = DiskReadAheadPolicy.plan(20, positionMs = 50 * minute, bufferedPositionMs = 51 * minute, durationMs = hour)

        assertThat(window!!.last).isEqualTo(hour - 1)
    }

    @Test
    fun `a buffer already past the window leaves nothing to write`() {
        assertThat(DiskReadAheadPolicy.plan(5, positionMs = 0L, bufferedPositionMs = 5 * minute, durationMs = hour)).isNull()
    }

    @Test
    fun `the whole video needs a known duration`() {
        assertThat(DiskReadAheadPolicy.plan(WHOLE_VIDEO, 0L, 0L, durationMs = 0L)).isNull()
        assertThat(DiskReadAheadPolicy.plan(WHOLE_VIDEO, 0L, 0L, durationMs = hour)!!.last).isEqualTo(hour - 1)
    }

    @Test
    fun `a top-up waits until half the window is used`() {
        assertThat(DiskReadAheadPolicy.needsTopUp(10, positionMs = 2 * minute, coveredUntilMs = 10 * minute, durationMs = hour)).isFalse()
        assertThat(DiskReadAheadPolicy.needsTopUp(10, positionMs = 6 * minute, coveredUntilMs = 10 * minute, durationMs = hour)).isTrue()
        assertThat(DiskReadAheadPolicy.needsTopUp(10, positionMs = 0L, coveredUntilMs = 0L, durationMs = 0L)).isTrue()
    }

    @Test
    fun `nothing more is written once the end is covered`() {
        assertThat(DiskReadAheadPolicy.needsTopUp(10, positionMs = 55 * minute, coveredUntilMs = hour, durationMs = hour)).isFalse()
        assertThat(DiskReadAheadPolicy.needsTopUp(WHOLE_VIDEO, positionMs = 0L, coveredUntilMs = hour, durationMs = hour)).isFalse()
    }

    @Test
    fun `bytes follow the bitrate, with headroom at the end`() {
        // 8 Mbps is 1,000 bytes per millisecond.
        val range = DiskReadAheadPolicy.byteRange(fromMs = 1_000L, toMs = 2_000L, bitrate = 8_000_000)

        assertThat(range!!.first).isEqualTo(1_000_000L)
        assertThat(range.last).isEqualTo(2_200_000L - 1)
    }

    @Test
    fun `bytes never pass the stream's own length`() {
        val range = DiskReadAheadPolicy.byteRange(fromMs = 1_000L, toMs = 2_000L, bitrate = 8_000_000, contentLength = 1_500_000L)

        assertThat(range!!.last).isEqualTo(1_500_000L - 1)
        assertThat(DiskReadAheadPolicy.byteRange(2_000L, 3_000L, 8_000_000, contentLength = 1_500_000L)).isNull()
    }

    @Test
    fun `an unknown bitrate writes nothing`() {
        assertThat(DiskReadAheadPolicy.byteRange(0L, minute, bitrate = 0)).isNull()
    }

    @Test
    fun `the budget is half the cache, and unlimited for an unlimited cache`() {
        assertThat(DiskReadAheadPolicy.budgetBytes(1_000L)).isEqualTo(500L)
        assertThat(DiskReadAheadPolicy.budgetBytes(0L)).isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun `only googlevideo streams are written ahead`() {
        assertThat(DiskReadAheadPolicy.isWritableStream("https", "rr3---sn-abc.googlevideo.com")).isTrue()
        assertThat(DiskReadAheadPolicy.isWritableStream("sabr", "id")).isFalse()
        assertThat(DiskReadAheadPolicy.isWritableStream("file", null)).isFalse()
        assertThat(DiskReadAheadPolicy.isWritableStream("https", "manifest.googlevideo.com.example.org")).isFalse()
    }
}
