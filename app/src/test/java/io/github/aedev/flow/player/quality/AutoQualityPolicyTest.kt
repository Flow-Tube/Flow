package io.github.aedev.flow.player.quality

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.quality.LadderTestStreams.h264
import io.github.aedev.flow.player.quality.LadderTestStreams.vp9
import io.github.aedev.flow.player.stream.VideoCodecUtils
import org.junit.Test

class AutoQualityPolicyTest {
    private val ladder =
        listOf(
            h264(360, 700_000),
            h264(480, 1_200_000),
            h264(720, 2_500_000),
            h264(1080, 4_500_000),
        )

    private fun height(stream: io.github.aedev.flow.player.stream.model.VideoStream?) =
        stream?.let(VideoCodecUtils::qualityHeightFromStream)

    @Test
    fun `starts at the tallest quality whose bitrate fits 70 percent of the estimate`() {
        assertThat(height(AutoQualityPolicy.initialPick(ladder, 7_000_000L, null))).isEqualTo(1080)
        assertThat(height(AutoQualityPolicy.initialPick(ladder, 5_000_000L, null))).isEqualTo(720)
        assertThat(height(AutoQualityPolicy.initialPick(ladder, 2_000_000L, null))).isEqualTo(480)
    }

    @Test
    fun `starts at the lowest quality when nothing fits`() {
        assertThat(height(AutoQualityPolicy.initialPick(ladder, 300_000L, null))).isEqualTo(360)
    }

    @Test
    fun `the preferred codec wins at the chosen height`() {
        val mixed = ladder + vp9(1080, 3_800_000)

        val pick = AutoQualityPolicy.initialPick(mixed, 8_000_000L, "vp9")

        assertThat(VideoCodecUtils.codecKeyFromStream(pick!!)).isEqualTo("vp9")
    }

    @Test
    fun `steps up only when the estimate covers the next bitrate with room to spare`() {
        val current = ladder[1]

        assertThat(AutoQualityPolicy.stepUp(current, ladder, 3_000_000L, null)).isNull()
        assertThat(height(AutoQualityPolicy.stepUp(current, ladder, 3_400_000L, null))).isEqualTo(720)
    }

    @Test
    fun `steps down when the estimate falls well below the current bitrate`() {
        val current = ladder[2]

        assertThat(AutoQualityPolicy.stepDownForBandwidth(current, ladder, 2_000_000L, null)).isNull()
        assertThat(height(AutoQualityPolicy.stepDownForBandwidth(current, ladder, 1_600_000L, null))).isEqualTo(480)
    }

    @Test
    fun `the step thresholds leave a band where nothing switches`() {
        val current = ladder[1]
        val estimate = 2_000_000L

        assertThat(AutoQualityPolicy.stepUp(current, ladder, estimate, null)).isNull()
        assertThat(AutoQualityPolicy.stepDownForBandwidth(current, ladder, estimate, null)).isNull()
    }

    @Test
    fun `a step keeps the current codec when the next height has it`() {
        val mixed = ladder + vp9(720, 2_000_000)

        val next = AutoQualityPolicy.stepUp(ladder[1], mixed, 10_000_000L, "vp9")

        assertThat(VideoCodecUtils.codecKeyFromStream(next!!)).isEqualTo("h264")
    }
}
