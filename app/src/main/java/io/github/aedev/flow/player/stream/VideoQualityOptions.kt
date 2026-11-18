package io.github.aedev.flow.player.stream

import io.github.aedev.flow.data.local.VideoQuality
import io.github.aedev.flow.player.stream.model.VideoStream

/**
 * Maps resolved video streams onto the [VideoQuality] rows the player's quality menu is built from.
 *
 * Heights are normalised first, so the several stream heights YouTube reports for one quality class
 * (and the transposed height of a portrait stream) collapse onto a single row.
 */
object VideoQualityOptions {
    /** Every quality [videoStreams] can actually serve, ascending, with AUTO offered last. */
    fun availableQualities(videoStreams: List<VideoStream>): List<VideoQuality> =
        videoStreams
            .map { VideoCodecUtils.normalizeQualityHeight(VideoCodecUtils.qualityHeightFromStream(it)) }
            .distinct()
            .sorted()
            .map { VideoQuality.fromHeight(it) }
            .distinct() + listOf(VideoQuality.AUTO)

    /** The row [stream] belongs to; AUTO when there is no fixed stream because adaptive selection owns the choice. */
    fun qualityOf(stream: VideoStream?): VideoQuality =
        stream
            ?.let { VideoQuality.fromHeight(VideoCodecUtils.normalizeQualityHeight(VideoCodecUtils.qualityHeightFromStream(it))) }
            ?: VideoQuality.AUTO
}
