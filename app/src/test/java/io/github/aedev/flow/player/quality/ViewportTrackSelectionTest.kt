package io.github.aedev.flow.player.quality

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.BandwidthMeter
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.MiniPlayerExpansionState
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Test

class ViewportTrackSelectionTest {
    private val meter = mockk<BandwidthMeter>(relaxed = true) { every { bitrateEstimate } returns 100_000_000L }

    private fun video(
        width: Int,
        height: Int,
        bitrate: Int,
    ) = Format
        .Builder()
        .setSampleMimeType(MimeTypes.VIDEO_H264)
        .setWidth(width)
        .setHeight(height)
        .setAverageBitrate(bitrate)
        .setPeakBitrate(bitrate)
        .build()

    private val group = TrackGroup(video(1920, 1080, 5_000_000), video(1280, 720, 2_500_000), video(854, 480, 1_000_000))

    private fun selection(): AdaptiveTrackSelection =
        ViewportTrackSelection
            .Factory(5_000, 32_500, 25_000, 0.7f)
            .createTrackSelections(
                arrayOf(ExoTrackSelection.Definition(group, 0, 1, 2)),
                meter,
                MediaSource.MediaPeriodId(Any()),
                Timeline.EMPTY,
            ).single() as AdaptiveTrackSelection

    private fun AdaptiveTrackSelection.update(bufferedSeconds: Long) =
        updateSelectedTrack(0L, bufferedSeconds * 1_000_000L, Long.MIN_VALUE + 1, emptyList(), emptyArray<MediaChunkIterator>())

    @After
    fun reset() = PlayerViewport.apply(false)

    @Test
    fun `full size picks the top rung`() {
        val selection = selection()
        selection.update(bufferedSeconds = 0)

        assertThat(selection.selectedFormat.height).isEqualTo(1080)
    }

    @Test
    fun `small picks the 480p rung even with a full buffer`() {
        val selection = selection()
        selection.update(bufferedSeconds = 0)

        PlayerViewport.apply(true)
        selection.update(bufferedSeconds = 60)

        assertThat(selection.selectedFormat.height).isEqualTo(480)
    }

    @Test
    fun `growing again steps back up`() {
        val selection = selection()
        PlayerViewport.apply(true)
        selection.update(bufferedSeconds = 0)

        PlayerViewport.apply(false)
        selection.update(bufferedSeconds = 20)

        assertThat(selection.selectedFormat.height).isEqualTo(1080)
    }

    @Test
    fun `the small viewport fits 480p either way round and nothing larger`() {
        assertThat(PlayerViewport.fitsSmall(854, 480)).isTrue()
        assertThat(PlayerViewport.fitsSmall(480, 854)).isTrue()
        assertThat(PlayerViewport.fitsSmall(1280, 720)).isFalse()
        assertThat(PlayerViewport.fitsSmall(-1, -1)).isTrue()
    }

    @Test
    fun `the switch off keeps full size in the mini player and PiP`() {
        assertThat(PlayerViewport.isSmall(MiniPlayerExpansionState.COLLAPSED, inPip = false, lowerQualityWhenSmall = true)).isTrue()
        assertThat(PlayerViewport.isSmall(MiniPlayerExpansionState.EXPANDED, inPip = true, lowerQualityWhenSmall = true)).isTrue()
        assertThat(PlayerViewport.isSmall(MiniPlayerExpansionState.COLLAPSED, inPip = true, lowerQualityWhenSmall = false)).isFalse()
        assertThat(PlayerViewport.isSmall(MiniPlayerExpansionState.EXPANDED, inPip = false, lowerQualityWhenSmall = true)).isFalse()
    }
}
