package io.github.aedev.flow.player.quality

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.config.VideoSizeCap
import io.github.aedev.flow.player.state.EnhancedPlayerState
import io.github.aedev.flow.player.state.QualityOption
import io.github.aedev.flow.player.stream.PlaybackTestStreams.av1
import io.github.aedev.flow.player.stream.PlaybackTestStreams.h264
import io.github.aedev.flow.player.stream.PlaybackTestStreams.h264Format
import io.github.aedev.flow.player.stream.PlaybackTestStreams.video
import io.github.aedev.flow.player.stream.PlaybackTestStreams.vp9
import io.github.aedev.flow.player.stream.TestVideoStream
import io.github.aedev.flow.player.stream.testCodec
import io.github.aedev.flow.player.stream.testHeight
import io.github.aedev.flow.player.stream.testItag
import io.github.aedev.flow.player.stream.testUrl
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

/**
 * The quality the player plays and the choices it offers: Auto's starting pick, manual picks by
 * height or by exact stream, the step-downs Auto takes on buffering, and where playback goes when a
 * stream or a whole codec fails.
 */
@UnstableApi
class QualityManagerTest {
    private val h360 = h264(360, 700_000)
    private val h480 = h264(480, 1_200_000)
    private val h720 = h264(720, 2_500_000)
    private val h1080 = h264(1080, 4_500_000)
    private val ladder = listOf(h360, h480, h720, h1080)

    private class Switches {
        val calls = mutableListOf<Pair<TestVideoStream, Long>>()
        val last get() = calls.last()
    }

    private val state = MutableStateFlow(EnhancedPlayerState())
    private val switches = Switches()

    private fun manager(
        estimateBps: Long? = null,
        streams: List<TestVideoStream> = ladder,
    ) = QualityManager(
        bandwidthMeter = estimateBps?.let { bps -> mockk<DefaultBandwidthMeter> { every { bitrateEstimate } returns bps } },
        trackSelector = null,
        videoSizeCap = VideoSizeCap.UHD,
        stateFlow = state,
        onQualitySwitch = { stream, position -> switches.calls += stream to position },
    ).also { it.setAvailableStreams(streams) }

    @Test
    fun `available streams drop duplicate URLs and run tallest first`() {
        val qm = manager(streams = listOf(h480, h1080, h360, h480, h720))

        assertThat(qm.getWorkingStreams().map { it.testHeight }).containsExactly(1080, 720, 480, 360).inOrder()
    }

    @Test
    fun `the current stream is remembered with its height`() {
        val qm = manager()

        qm.setCurrentStream(h720)

        assertThat(qm.getCurrentStream()!!.testUrl).isEqualTo(h720.testUrl)
        assertThat(qm.lastAdaptiveQualityHeight).isEqualTo(720)
        qm.setCurrentStream(null)
        assertThat(qm.getCurrentStream()).isNull()
        assertThat(qm.lastAdaptiveQualityHeight).isEqualTo(0)
    }

    @Test
    fun `Auto starts at the tallest quality 70 percent of the estimate covers`() {
        assertThat(manager(estimateBps = 7_000_000L).selectSmartInitialQuality()!!.testHeight).isEqualTo(1080)
        assertThat(manager(estimateBps = 2_000_000L).selectSmartInitialQuality()!!.testHeight).isEqualTo(480)
        assertThat(manager(estimateBps = 100_000L).selectSmartInitialQuality()!!.testHeight).isEqualTo(360)
    }

    @Test
    fun `without a bandwidth estimate Auto assumes 5 Mbps`() {
        assertThat(manager(estimateBps = null).selectSmartInitialQuality()!!.testHeight).isEqualTo(720)
    }

    @Test
    fun `Auto's starting pick honours the preferred codec at the chosen height`() {
        val qm = manager(estimateBps = 8_000_000L, streams = ladder + vp9(1080, 3_800_000) + av1(1080, 3_000_000))

        qm.preferredCodecKey = "vp9"
        assertThat(qm.selectSmartInitialQuality()!!.testCodec).isEqualTo("vp9")
        qm.preferredCodecKey = "av1"
        assertThat(qm.selectSmartInitialQuality()!!.testCodec).isEqualTo("av1")
        qm.preferredCodecKey = null
        assertThat(qm.selectSmartInitialQuality()!!.testCodec).isEqualTo("h264")
    }

    @Test
    fun `a failed URL and every copy of the same variant leave the working set`() {
        val qm = manager()
        val sameVariantOtherHost =
            video(h264Format(720, 2_500_000).copy(url = "https://rr2---sn-other.googlevideo.com/videoplayback?itag=136&h=720"))
        qm.setAvailableStreams(ladder + sameVariantOtherHost)

        qm.markStreamFailed(h720.testUrl)

        assertThat(qm.hasStreamFailed(h720.testUrl)).isTrue()
        assertThat(qm.hasStreamFailed(sameVariantOtherHost.testUrl)).isTrue()
        assertThat(qm.hasStreamFailed(h480.testUrl)).isFalse()
        assertThat(qm.getWorkingStreams().map { it.testHeight }).containsExactly(1080, 480, 360).inOrder()
        assertThat(qm.streamErrorCount).isEqualTo(1)
    }

    @Test
    fun `a failed URL the manager does not know still counts as failed`() {
        val qm = manager()

        qm.markStreamFailed("https://elsewhere.invalid/stream")

        assertThat(qm.hasStreamFailed("https://elsewhere.invalid/stream")).isTrue()
        assertThat(qm.getWorkingStreams()).hasSize(4)
    }

    @Test
    fun `a new video forgets failures and returns to Auto`() {
        val qm = manager()
        qm.markStreamFailed(h720.testUrl)
        qm.setManualMode(720)
        qm.setCurrentStream(h720)

        qm.resetForNewVideo()

        assertThat(qm.hasStreamFailed(h720.testUrl)).isFalse()
        assertThat(qm.streamErrorCount).isEqualTo(0)
        assertThat(qm.isAdaptiveQualityEnabled).isTrue()
        assertThat(qm.manualQualityHeight).isNull()
        assertThat(qm.getCurrentStream()).isNull()
    }

    @Test
    fun `quality options list Auto, then one row per height and codec, tallest and most compatible first`() {
        val vp9Hdr = vp9(1080, 5_000_000, fps = 60, hdr = true)
        val h720b = video(h264Format(720, 2_000_000).copy(url = "https://rr1---sn-test.googlevideo.com/videoplayback?itag=136&h=720&alt=1"))
        val qm = manager(streams = ladder + vp9(1080, 3_800_000, fps = 60) + av1(1080, 3_000_000) + vp9Hdr + h720b)

        val options = qm.buildQualityOptions()

        assertThat(options.first()).isEqualTo(QualityOption(height = 0, label = "Auto", bitrate = 0L))
        assertThat(options.drop(1).map { it.label })
            .containsExactly("1080p H264", "1080p60 VP9", "1080p AV1", "720p H264", "480p H264", "360p H264")
            .inOrder()
        val vp9Row = options.single { it.label == "1080p60 VP9" }
        assertThat(vp9Row.codecKey).isEqualTo("vp9")
        assertThat(vp9Row.isHdr).isTrue()
        assertThat(vp9Row.bitrate).isEqualTo(5_000_000L)
        assertThat(vp9Row.streamKey).isEqualTo(vp9Hdr.testUrl)
        val h720Row = options.single { it.label == "720p H264" }
        assertThat(h720Row.streamKey).isEqualTo(h720.testUrl)
        assertThat(h720Row.isHdr).isFalse()
    }

    @Test
    fun `picking a height pins it and reloads the best stream there at the same position`() {
        val qm = manager(streams = ladder + vp9(720, 2_000_000))
        qm.preferredCodecKey = "vp9"

        assertThat(qm.switchQualityByHeight(720, 42_000L)).isTrue()

        assertThat(switches.last.first.testCodec).isEqualTo("vp9")
        assertThat(switches.last.second).isEqualTo(42_000L)
        assertThat(qm.isAdaptiveQualityEnabled).isFalse()
        assertThat(qm.manualQualityHeight).isEqualTo(720)
        assertThat(qm.getCurrentStream()!!.testCodec).isEqualTo("vp9")
        assertThat(state.value.currentQuality).isEqualTo(720)
        assertThat(state.value.effectiveQuality).isEqualTo(720)
        assertThat(state.value.currentQualityKey).isEqualTo(switches.last.first.testUrl)
    }

    @Test
    fun `picking the pinned height again or one that is not offered changes nothing`() {
        val qm = manager()
        qm.switchQualityByHeight(720, 0L)
        switches.calls.clear()

        assertThat(qm.switchQualityByHeight(720, 0L)).isFalse()
        assertThat(qm.switchQualityByHeight(1440, 0L)).isFalse()
        assertThat(switches.calls).isEmpty()
    }

    @Test
    fun `picking an exact stream reloads that stream`() {
        val av1 = av1(1080, 3_000_000)
        val qm = manager(streams = ladder + av1)

        assertThat(qm.switchQualityByStreamKey(av1.testUrl, 9_000L)).isTrue()

        assertThat(switches.last.first.testItag).isEqualTo(399)
        assertThat(switches.last.second).isEqualTo(9_000L)
        assertThat(qm.manualQualityHeight).isEqualTo(1080)
        assertThat(state.value.currentQuality).isEqualTo(1080)
        assertThat(state.value.currentQualityKey).isEqualTo(av1.testUrl)
    }

    @Test
    fun `an exact stream on YouTube's DASH manifest switches inside the source without a reload`() {
        val qm = manager()
        qm.isDashSource = true

        assertThat(qm.switchQualityByStreamKey(h480.testUrl, 0L)).isTrue()

        assertThat(switches.calls).isEmpty()
        assertThat(qm.getCurrentStream()!!.testUrl).isEqualTo(h480.testUrl)
        assertThat(state.value.currentQuality).isEqualTo(480)
    }

    @Test
    fun `an unknown stream key changes nothing`() {
        val qm = manager()

        assertThat(qm.switchQualityByStreamKey("https://unknown.invalid", 0L)).isFalse()
        assertThat(switches.calls).isEmpty()
    }

    @Test
    fun `a quality option switches by its stream key, and Auto by height`() {
        val qm = manager()

        qm.switchQuality(QualityOption(height = 480, label = "480p H264", bitrate = 1L, streamKey = h480.testUrl), 0L)
        assertThat(qm.getCurrentStream()!!.testUrl).isEqualTo(h480.testUrl)

        qm.switchQuality(QualityOption(height = 0, label = "Auto", bitrate = 0L), 0L)
        assertThat(qm.isAdaptiveQualityEnabled).isTrue()
    }

    @Test
    fun `returning to Auto reloads at the bandwidth pick so a ladder can take over`() {
        val qm = manager()
        qm.switchQualityByHeight(720, 0L)
        switches.calls.clear()

        assertThat(qm.switchQualityByHeight(0, 5_000L)).isTrue()

        assertThat(qm.isAdaptiveQualityEnabled).isTrue()
        assertThat(qm.manualQualityHeight).isNull()
        assertThat(switches.last.first.testHeight).isEqualTo(720)
        assertThat(switches.last.second).isEqualTo(5_000L)
        assertThat(state.value.currentQuality).isEqualTo(0)
        assertThat(state.value.effectiveQuality).isEqualTo(720)
        assertThat(state.value.currentQualityKey).isNull()
    }

    @Test
    fun `returning to Auto while a ladder already adapts does not reload`() {
        val qm = manager()
        qm.setActiveLadder(ladder)

        qm.switchQualityByHeight(0, 0L)

        assertThat(switches.calls).isEmpty()
        assertThat(state.value.currentQuality).isEqualTo(0)
    }

    @Test
    fun `picking a rung of the playing ladder holds Media3 there instead of reloading`() {
        val qm = manager(streams = ladder + vp9(720, 2_000_000))
        qm.preferredCodecKey = "vp9"
        qm.setActiveLadder(ladder)

        assertThat(qm.switchQualityByHeight(720, 42_000L)).isTrue()
        assertThat(qm.switchQualityByStreamKey(h480.testUrl, 42_000L)).isTrue()

        assertThat(switches.calls).isEmpty()
        assertThat(qm.getCurrentStream()!!.testUrl).isEqualTo(h480.testUrl)
        assertThat(qm.isAdaptiveQualityEnabled).isFalse()
        assertThat(state.value.currentQuality).isEqualTo(480)
    }

    @Test
    fun `a pick outside the playing ladder still reloads, and Auto after a pin inside it does not`() {
        val vp9At720 = vp9(720, 2_000_000)
        val qm = manager(streams = ladder + vp9At720)
        qm.setActiveLadder(ladder)

        qm.switchQualityByStreamKey(vp9At720.testUrl, 0L)
        assertThat(switches.last.first.testCodec).isEqualTo("vp9")

        switches.calls.clear()
        qm.switchQualityByStreamKey(h720.testUrl, 0L)
        qm.switchQualityByHeight(0, 0L)
        assertThat(switches.calls).isEmpty()
        assertThat(state.value.currentQuality).isEqualTo(0)
    }

    @Test
    fun `Auto's ladder is every describable rung of the anchor's codec, and none while a quality is pinned`() {
        val qm = manager(streams = ladder + vp9(720, 2_000_000))

        assertThat(qm.adaptiveLadder(h720).map { it.testHeight }).containsExactly(360, 480, 720, 1080).inOrder()
        qm.setManualMode(720)
        assertThat(qm.adaptiveLadder(h720)).isEmpty()
    }

    @Test
    fun `buffering steps Auto one quality down and reloads, then waits before stepping again`() {
        val qm = manager()
        qm.setCurrentStream(h1080)

        qm.checkAdaptiveQualityDowngrade(forceCheck = true, currentPosition = 7_000L)

        assertThat(switches.last.first.testHeight).isEqualTo(720)
        assertThat(switches.last.second).isEqualTo(7_000L)
        assertThat(state.value.currentQuality).isEqualTo(0)
        assertThat(state.value.effectiveQuality).isEqualTo(720)

        qm.checkAdaptiveQualityDowngrade(forceCheck = true, currentPosition = 8_000L)
        assertThat(switches.calls).hasSize(1)
    }

    @Test
    fun `a low estimate steps Auto down, a high one steps it up`() {
        val low = manager(estimateBps = 1_600_000L)
        low.setCurrentStream(h720)
        low.checkAdaptiveQualityDowngrade(forceCheck = false, currentPosition = 0L)
        assertThat(switches.last.first.testHeight).isEqualTo(480)

        val high = manager(estimateBps = 3_400_000L)
        high.setCurrentStream(h480)
        high.checkAdaptiveQualityUpgrade(0L)
        assertThat(switches.last.first.testHeight).isEqualTo(720)
    }

    @Test
    fun `Auto's own steps stand down while Media3 adapts over a ladder or a quality is pinned`() {
        val qm = manager(estimateBps = 50_000_000L)
        qm.setCurrentStream(h480)
        qm.setActiveLadder(ladder)
        qm.checkAdaptiveQualityUpgrade(0L)
        qm.checkAdaptiveQualityDowngrade(forceCheck = true, currentPosition = 0L)

        qm.setActiveLadder(emptyList())
        qm.setManualMode(480)
        qm.checkAdaptiveQualityUpgrade(0L)

        assertThat(switches.calls).isEmpty()
    }

    @Test
    fun `an adaptive step on YouTube's DASH manifest stays inside the source`() {
        val qm = manager()
        qm.isDashSource = true
        qm.setCurrentStream(h1080)

        qm.checkAdaptiveQualityDowngrade(forceCheck = true, currentPosition = 0L)

        assertThat(switches.calls).isEmpty()
        assertThat(qm.getCurrentStream()!!.testHeight).isEqualTo(720)
        assertThat(state.value.effectiveQuality).isEqualTo(720)
    }

    @Test
    fun `a corrupt stream in Auto drops to the lowest working MP4 stream`() {
        val webm240 = vp9(240, 300_000)
        val qm = manager(streams = ladder + webm240)
        qm.setCurrentStream(h1080)
        qm.markStreamFailed(h1080.testUrl)
        qm.markStreamFailed(h360.testUrl)

        val next = qm.attemptQualityDowngrade()!!

        assertThat(next.testUrl).isEqualTo(h480.testUrl)
        assertThat(qm.getCurrentStream()!!.testUrl).isEqualTo(h480.testUrl)
        assertThat(qm.streamErrorCount).isEqualTo(0)
        assertThat(state.value.currentQuality).isEqualTo(480)
        assertThat(state.value.currentQualityKey).isEqualTo(h480.testUrl)
        assertThat(state.value.isBuffering).isTrue()
    }

    @Test
    fun `a corrupt stream with a pinned quality keeps the pin`() {
        val qm = manager()
        qm.switchQualityByHeight(720, 0L)

        assertThat(qm.attemptQualityDowngrade()!!.testUrl).isEqualTo(h720.testUrl)
    }

    @Test
    fun `once every stream has failed there is nothing to drop to`() {
        val qm = manager(streams = listOf(h480, h720))
        qm.markStreamFailed(h480.testUrl)
        qm.markStreamFailed(h720.testUrl)

        assertThat(qm.attemptQualityDowngrade()).isNull()
    }

    @Test
    fun `an AV1 stream the CDN refuses falls back to the nearest height in the most compatible codec`() {
        val av1 = av1(1080, 3_000_000)
        val vp9 = vp9(1080, 3_800_000)
        val qm = manager(streams = ladder + av1 + vp9)
        qm.setCurrentStream(av1)

        assertThat(qm.fallbackToAlternateCodec(12_000L)).isTrue()

        assertThat(switches.last.first.testUrl).isEqualTo(h1080.testUrl)
        assertThat(switches.last.second).isEqualTo(12_000L)
        assertThat(qm.hasStreamFailed(av1.testUrl)).isTrue()
        assertThat(state.value.currentQuality).isEqualTo(0)
        assertThat(state.value.effectiveQuality).isEqualTo(1080)
        assertThat(state.value.currentQualityKey).isNull()
        assertThat(state.value.isBuffering).isTrue()
    }

    @Test
    fun `an AV1 fallback keeps a pinned quality pinned at the new stream`() {
        val av1 = av1(720, 1_800_000)
        val vp9 = vp9(720, 2_000_000)
        val qm = manager(streams = listOf(av1, vp9, h480))
        qm.switchQualityByStreamKey(av1.testUrl, 0L)

        assertThat(qm.fallbackToAlternateCodec(0L)).isTrue()

        assertThat(switches.last.first.testUrl).isEqualTo(vp9.testUrl)
        assertThat(qm.manualQualityHeight).isEqualTo(720)
        assertThat(state.value.currentQuality).isEqualTo(720)
        assertThat(state.value.currentQualityKey).isEqualTo(vp9.testUrl)
    }

    @Test
    fun `only AV1 falls back, and only when another codec exists`() {
        val qm = manager()
        qm.setCurrentStream(h720)
        assertThat(qm.fallbackToAlternateCodec(0L)).isFalse()

        val av1 = av1(720, 1_800_000)
        val onlyAv1 = manager(streams = listOf(av1))
        onlyAv1.setCurrentStream(av1)
        assertThat(onlyAv1.fallbackToAlternateCodec(0L)).isFalse()
        assertThat(switches.calls).isEmpty()
    }
}
