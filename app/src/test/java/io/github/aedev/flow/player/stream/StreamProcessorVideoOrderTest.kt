package io.github.aedev.flow.player.stream

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.stream.PlaybackTestStreams.av1
import io.github.aedev.flow.player.stream.PlaybackTestStreams.h264
import io.github.aedev.flow.player.stream.PlaybackTestStreams.vp9
import org.junit.Test

/** The order every video stream list in the player starts from. */
class StreamProcessorVideoOrderTest {
    @Test
    fun `streams run tallest first, then H264, VP9, AV1, then higher bitrate, with duplicate URLs dropped`() {
        val h720 = h264(720, 2_500_000)
        val vp1080 = vp9(1080, 3_800_000)
        val av1080 = av1(1080, 3_000_000)
        val h1080 = h264(1080, 4_500_000)
        val vp720 = vp9(720, 2_000_000)

        val ordered = StreamProcessor.processVideoStreams(listOf(h720, av1080, vp1080, h720, vp720, h1080))

        assertThat(ordered.map { it.testUrl })
            .containsExactly(h1080.testUrl, vp1080.testUrl, av1080.testUrl, h720.testUrl, vp720.testUrl)
            .inOrder()
    }

    @Test
    fun `at one height and codec the higher bitrate leads`() {
        val low = vp9(720, 1_500_000)
        val hdr = vp9(720, 2_600_000, hdr = true)

        assertThat(StreamProcessor.processVideoStreams(listOf(low, hdr)).map { it.testUrl })
            .containsExactly(hdr.testUrl, low.testUrl)
            .inOrder()
    }
}
