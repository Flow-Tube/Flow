package io.github.aedev.flow.ui.screens.player.state

import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.player.stream.UpcomingDetails
import io.github.aedev.flow.player.stream.UpcomingPremiere
import io.github.aedev.flow.utils.parsePremiereTimestamp

/**
 * Decides when the player shows a countdown instead of playback, and what the screen looks like
 * while it does.
 *
 * Everything here is a countdown to a moment still ahead: a release time in the past means the
 * premiere has started (or the feed metadata is stale), and the player must load streams rather
 * than count down to a time that has been and gone.
 */
internal object UpcomingPremierePolicy {
    /**
     * The release time [video]'s own metadata carries, or null when it carries none in the future.
     *
     * A feed timestamp is only trusted when it is comfortably ahead of now, because an upload date
     * is stamped into the same field; anything closer falls back to parsing the premiere label.
     */
    fun releaseTimeFor(
        video: Video,
        nowMs: Long = System.currentTimeMillis(),
    ): Long? {
        if (!video.isUpcoming) return null
        return when {
            video.timestamp > nowMs + TRUSTED_TIMESTAMP_LEAD_MS -> video.timestamp
            else -> parsePremiereTimestamp(video.uploadDate)
        }?.takeIf { it > nowMs }
    }

    /** A video already known to be upcoming with a known release time needs no network probe. */
    fun needsProbe(
        flagged: Boolean,
        listReleaseMs: Long?,
    ): Boolean = !(flagged && listReleaseMs != null)

    /** Combines what the list metadata knew with what the probe found. */
    fun resolve(
        flagged: Boolean,
        listReleaseMs: Long?,
        probe: UpcomingPremiere,
    ): UpcomingPremiere {
        if (!flagged && !probe.isUpcoming) return UpcomingPremiere.NOT_UPCOMING
        return UpcomingPremiere(
            isUpcoming = true,
            scheduledStartMs = listReleaseMs ?: probe.scheduledStartMs,
            details = probe.details,
        )
    }

    /** The countdown state for a video the caller already knows is upcoming, before any load starts. */
    fun applyTo(
        state: VideoPlayerUiState,
        video: Video,
        releaseTimeMs: Long,
        preserveQueueTitle: String?,
    ): VideoPlayerUiState =
        state.resetForVideo(video).copy(
            isLoading = false,
            queueTitle = preserveQueueTitle,
            isUpcoming = true,
            upcomingReleaseTimeMs = releaseTimeMs,
        )

    /**
     * The video the countdown is shown for once a load has resolved it as upcoming. A list row knows
     * the title but never the description, and a deep link knows nothing; [details] fills whatever
     * is still blank.
     */
    fun upcomingVideo(
        videoId: String,
        cached: Video?,
        releaseMs: Long?,
        details: UpcomingDetails? = null,
    ): Video {
        val base =
            cached ?: Video(
                id = videoId,
                title = "",
                channelName = "",
                channelId = "",
                thumbnailUrl = "",
                duration = 0,
                viewCount = 0L,
                uploadDate = "",
            )
        return base.copy(
            title = base.title.ifBlank { details?.title.orEmpty() },
            channelName = base.channelName.ifBlank { details?.channelName.orEmpty() },
            channelId = base.channelId.ifBlank { details?.channelId.orEmpty() },
            thumbnailUrl = base.thumbnailUrl.ifBlank { details?.thumbnailUrl.orEmpty() },
            description = base.description.ifBlank { details?.description.orEmpty() },
            isUpcoming = true,
            isScheduledLive = base.isScheduledLive || details?.isScheduledLive == true,
            timestamp = releaseMs ?: base.timestamp,
        )
    }

    /**
     * The countdown state a finished load lands on. Unlike [applyTo] this keeps what the load
     * already gathered — the related lane it resolved, and the channel and engagement state the
     * screen is holding — because the countdown is the outcome of that load, not a fresh start.
     */
    fun enterFrom(
        state: VideoPlayerUiState,
        upcomingVideo: Video,
        relatedVideos: List<Video>,
        releaseMs: Long?,
    ): VideoPlayerUiState =
        state.copy(
            cachedVideo = upcomingVideo,
            isLoading = false,
            error = null,
            errorHint = null,
            errorOffersSessionReset = false,
            videoStream = null,
            audioStream = null,
            relatedVideos = relatedVideos.ifEmpty { state.relatedVideos },
            hlsUrl = null,
            isLive = false,
            isUpcoming = true,
            upcomingReleaseTimeMs = releaseMs,
        )

    private const val TRUSTED_TIMESTAMP_LEAD_MS = 60_000L
}
