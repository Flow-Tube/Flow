package io.github.aedev.flow.player.quality

import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.chunk.MediaChunk
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.upstream.BandwidthMeter
import com.google.common.collect.ImmutableList

/**
 * Media3's adaptive selection with the small [PlayerViewport] applied chunk by chunk. Nothing that is
 * already buffered is dropped: the next chunk is picked under the cap, and after the player grows
 * again the normal step up replaces queued low chunks ahead of the playhead, as YouTube does.
 *
 * Media3 does not step down while [maxDurationForQualityDecreaseMs] or more is buffered, so with a
 * full buffer it would go on fetching the large rung in the mini player. While the current rung is
 * above the cap, the buffered time is reported as zero to lift only that hold.
 */
@UnstableApi
internal class ViewportTrackSelection(
    group: TrackGroup,
    tracks: IntArray,
    type: Int,
    bandwidthMeter: BandwidthMeter,
    minDurationForQualityIncreaseMs: Long,
    maxDurationForQualityDecreaseMs: Long,
    minDurationToRetainAfterDiscardMs: Long,
    bandwidthFraction: Float,
    adaptationCheckpoints: List<AdaptationCheckpoint>,
) : AdaptiveTrackSelection(
        group,
        tracks,
        type,
        bandwidthMeter,
        minDurationForQualityIncreaseMs,
        maxDurationForQualityDecreaseMs,
        minDurationToRetainAfterDiscardMs,
        DEFAULT_MAX_WIDTH_TO_DISCARD,
        DEFAULT_MAX_HEIGHT_TO_DISCARD,
        bandwidthFraction,
        DEFAULT_BUFFERED_FRACTION_TO_LIVE_EDGE_FOR_QUALITY_INCREASE,
        adaptationCheckpoints,
        Clock.DEFAULT,
    ) {
    override fun canSelectFormat(
        format: Format,
        trackBitrate: Int,
        effectiveBitrate: Long,
    ): Boolean = fitsViewport(format) && super.canSelectFormat(format, trackBitrate, effectiveBitrate)

    override fun updateSelectedTrack(
        playbackPositionUs: Long,
        bufferedDurationUs: Long,
        availableDurationUs: Long,
        queue: List<MediaChunk>,
        mediaChunkIterators: Array<out MediaChunkIterator>,
    ) {
        val buffered = if (fitsViewport(selectedFormat)) bufferedDurationUs else 0L
        super.updateSelectedTrack(playbackPositionUs, buffered, availableDurationUs, queue, mediaChunkIterators)
    }

    private fun fitsViewport(format: Format): Boolean = !PlayerViewport.small || PlayerViewport.fitsSmall(format.width, format.height)

    class Factory(
        private val minDurationForQualityIncreaseMs: Int,
        private val maxDurationForQualityDecreaseMs: Int,
        private val minDurationToRetainAfterDiscardMs: Int,
        private val bandwidthFraction: Float,
    ) : AdaptiveTrackSelection.Factory(
            minDurationForQualityIncreaseMs,
            maxDurationForQualityDecreaseMs,
            minDurationToRetainAfterDiscardMs,
            bandwidthFraction,
        ) {
        override fun createAdaptiveTrackSelection(
            group: TrackGroup,
            tracks: IntArray,
            type: Int,
            bandwidthMeter: BandwidthMeter,
            adaptationCheckpoints: ImmutableList<AdaptationCheckpoint>,
        ): AdaptiveTrackSelection =
            ViewportTrackSelection(
                group,
                tracks,
                type,
                bandwidthMeter,
                minDurationForQualityIncreaseMs.toLong(),
                maxDurationForQualityDecreaseMs.toLong(),
                minDurationToRetainAfterDiscardMs.toLong(),
                bandwidthFraction,
                adaptationCheckpoints,
            )
    }
}
