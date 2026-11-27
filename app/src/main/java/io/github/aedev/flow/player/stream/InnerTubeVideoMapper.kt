package io.github.aedev.flow.player.stream

import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.player.stream.model.StreamType
import io.github.aedev.flow.utils.ThumbnailUrlResolver

/**
 * The played video as the player response describes it.
 *
 * The service layer used to build this from a NewPipe `StreamInfo` it fetched alongside the
 * extraction; the same fields are on `videoDetails`, so the second request bought nothing but the
 * fields [Video] does not carry either way — counts and description, which the screen fills in from
 * the watch response once it is up.
 */
internal object InnerTubeVideoMapper {
    fun videoFromResult(
        videoId: String,
        result: InnerTubeVideoStreamExtractor.VideoExtractionResult,
        fallback: Video,
    ): Video {
        val details = result.playerResponse.videoDetails
        val thumbnail =
            details
                ?.thumbnail
                ?.thumbnails
                ?.maxByOrNull { it.height ?: 0 }
                ?.url
                ?.takeIf { it.isNotBlank() }
                ?: fallback.thumbnailUrl.takeIf { it.isNotBlank() }
                ?: ThumbnailUrlResolver.normalizeVideoThumbnail(videoId, null)
        return fallback.copy(
            id = videoId,
            title = details?.title?.takeIf { it.isNotBlank() } ?: fallback.title,
            channelName = details?.author?.takeIf { it.isNotBlank() } ?: fallback.channelName,
            channelId = details?.channelId?.takeIf { it.isNotBlank() } ?: fallback.channelId,
            thumbnailUrl = thumbnail,
            duration = durationSeconds(result).toInt().takeIf { it > 0 } ?: fallback.duration,
        )
    }

    fun durationSeconds(result: InnerTubeVideoStreamExtractor.VideoExtractionResult): Long =
        result.playerResponse.videoDetails
            ?.lengthSeconds
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?: 0L

    /**
     * Live while it is running, post-live while its DVR window is still served, an ordinary video
     * otherwise. `isLiveContent` stays true on every replay for good, so it says nothing about how to
     * play one: an old replay is a plain video. A result that came back as a manifest alone can only
     * be played from that manifest, whatever the flags say.
     */
    fun streamType(result: InnerTubeVideoStreamExtractor.VideoExtractionResult): StreamType {
        val details = result.playerResponse.videoDetails
        return when {
            details?.isLive == true -> StreamType.LIVE_STREAM
            details?.isPostLiveDvr == true -> StreamType.POST_LIVE_STREAM
            result.isLive -> StreamType.POST_LIVE_STREAM
            else -> StreamType.VIDEO_STREAM
        }
    }
}
