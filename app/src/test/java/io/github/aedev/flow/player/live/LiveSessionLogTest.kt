package io.github.aedev.flow.player.live

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LiveSessionLogTest {
    private var clock = 0L
    private var traffic = LiveTraffic()
    private val lines = mutableListOf<String>()
    private val log = LiveSessionLog(traffic = { traffic }, now = { clock }, emit = { lines += it })

    @Test
    fun `one line at start and one a minute with that minute's traffic`() {
        log.start("vid", "hls")
        traffic = LiveTraffic(manifestRequests = 24, manifestBytes = 24 * 1_024 * 1_024L, mediaRequests = 24, mediaBytes = 2_048)
        clock = 30_000
        log.tick(windowMs = 3_600_000, behindLiveMs = 9_000)
        clock = 60_000
        log.tick(windowMs = 3_600_000, behindLiveMs = 9_000)

        assertThat(lines)
            .containsExactly(
                "live start vid source=hls",
                "live vid source=hls t=60s window=3600s behind=9s stalls=0/0 manifest=24req/24576KB media=24req/2KB",
            ).inOrder()
    }

    @Test
    fun `buffering before the first frame and right after a seek is not a stall`() {
        log.start("vid", "segments")
        log.onBuffering()
        log.onReady()
        log.onSeek()
        clock = 4_000
        log.onBuffering()
        clock = 10_000
        log.onBuffering()
        clock = 60_000
        log.tick(windowMs = 43_200_000, behindLiveMs = 12_000)

        assertThat(lines.last()).contains("stalls=1/1")
    }

    @Test
    fun `nothing is logged after stop`() {
        log.start("vid", "hls")
        log.stop()
        clock = 120_000
        log.tick(windowMs = 1, behindLiveMs = 1)

        assertThat(lines).hasSize(1)
    }
}
