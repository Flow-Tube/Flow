package io.github.aedev.flow.player.quality

import android.view.Display
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.quality.LadderTestStreams.vp9
import io.github.aedev.flow.player.stream.VideoCodecUtils
import org.junit.Test

class VideoDecoderSupportTest {
    private val hardware = DecoderAnswer(hardware = true, supported = true)
    private val software = DecoderAnswer(hardware = false, supported = true)
    private val refuses = DecoderAnswer(hardware = true, supported = false)

    @Test
    fun `a hardware decoder that takes the stream is enough at any size`() {
        assertThat(decodableForAuto(listOf(hardware), 3840, 2160, 60)).isTrue()
    }

    @Test
    fun `software alone is trusted up to 1080p at 30 fps, in either orientation`() {
        assertThat(decodableForAuto(listOf(software), 1920, 1080, 30)).isTrue()
        assertThat(decodableForAuto(listOf(software), 1080, 1920, 30)).isTrue()
        assertThat(decodableForAuto(listOf(software), 1920, 1080, 60)).isFalse()
        assertThat(decodableForAuto(listOf(software), 2560, 1440, 30)).isFalse()
    }

    @Test
    fun `decoders that refuse the profile or size do not count`() {
        assertThat(decodableForAuto(listOf(refuses), 1280, 720, 30)).isFalse()
        assertThat(decodableForAuto(listOf(refuses, software), 1280, 720, 30)).isTrue()
        assertThat(decodableForAuto(emptyList(), 1280, 720, 30)).isFalse()
    }

    @Test
    fun `an SDR screen keeps HDR out of Auto, wherever the server listed it`() {
        val hdrFirst =
            listOf(vp9(1080, 5_000_000, hdr = true), vp9(1080, 3_500_000), vp9(720, 2_000_000, hdr = true), vp9(720, 2_000_000))

        val sdrOnly = withoutHdrOnSdrScreen(hdrFirst, screenShowsHdr = false)
        val ladder = AdaptiveLadder.forPreload(null, sdrOnly, "vp9")

        assertThat(sdrOnly.none(VideoCodecUtils::isHdr)).isTrue()
        assertThat(ladder.map(VideoCodecUtils::qualityHeightFromStream)).containsExactly(720, 1080).inOrder()
        assertThat(ladder.none(AdaptiveLadder::isHighBitDepth)).isTrue()
    }

    @Test
    fun `an HDR screen keeps every stream, and HDR alone is never emptied`() {
        val streams = listOf(vp9(1080, 5_000_000, hdr = true), vp9(1080, 3_500_000))
        val hdrOnly = listOf(vp9(1080, 5_000_000, hdr = true))

        assertThat(withoutHdrOnSdrScreen(streams, screenShowsHdr = true)).isEqualTo(streams)
        assertThat(withoutHdrOnSdrScreen(hdrOnly, screenShowsHdr = false)).isEqualTo(hdrOnly)
    }

    @Test
    fun `only HDR10 or HLG count as an HDR screen for YouTube`() {
        assertThat(showsYouTubeHdr(intArrayOf(Display.HdrCapabilities.HDR_TYPE_HDR10))).isTrue()
        val dolbyAndHlg = intArrayOf(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION, Display.HdrCapabilities.HDR_TYPE_HLG)
        assertThat(showsYouTubeHdr(dolbyAndHlg)).isTrue()
        assertThat(showsYouTubeHdr(intArrayOf(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION))).isFalse()
        assertThat(showsYouTubeHdr(intArrayOf())).isFalse()
    }
}

