package io.github.aedev.flow.ui.screens.subscriptions

import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.models.YouTubeClient
import io.github.aedev.flow.innertube.models.YouTubeLocale
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.player.stream.PlayabilityVerdict
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import io.github.aedev.flow.utils.premiereDateText
import kotlinx.coroutines.withTimeoutOrNull

/** What one player lookup told the feed about a row. */
internal data class SubscriptionPlayerLookup(
    val videoId: String,
    /** The row with what RSS cannot carry; null when the answer told nothing new. */
    val enriched: Video?,
    /** YouTube refuses the video to this viewer for their country or a membership (#1443). */
    val refusedToViewer: Boolean,
)

/**
 * The duration, live state and scheduled start RSS cannot carry, read from the player endpoint,
 * and whether that endpoint refuses the video to this viewer. RSS lists uploads the viewer's
 * country cannot play, which YouTube's own Videos tab hides. Null when the lookup times out.
 *
 * Asked in English so [PlayabilityVerdict] can read the refusal; `/player` titles are never
 * translated, so the cards keep their original titles.
 */
internal suspend fun fetchSubscriptionPlayerMetadata(
    video: Video,
    timeoutMs: Long,
): SubscriptionPlayerLookup? =
    withTimeoutOrNull(timeoutMs) {
        val response =
            YouTube.player(video.id, client = YouTubeClient.ANDROID, localeOverride = YouTubeLocale.EXTRACTION).getOrNull()
                ?: YouTube.player(video.id, client = YouTubeClient.MOBILE, localeOverride = YouTubeLocale.EXTRACTION).getOrNull()
                ?: return@withTimeoutOrNull null
        SubscriptionPlayerLookup(
            videoId = video.id,
            enriched = enrichedRow(video, response),
            refusedToViewer = PlayabilityVerdict.refusesViewer(response.playabilityStatus, response.videoDetails != null),
        )
    }

private fun enrichedRow(
    video: Video,
    response: PlayerResponse,
): Video? {
    val details = response.videoDetails ?: return null
    // The feed only knows the day the stream was announced; the player endpoint knows
    // the day it starts, and until then "live content" is a scheduled stream, not a live one.
    val scheduledStartMs =
        response.playabilityStatus.liveStreamability
            ?.liveStreamabilityRenderer
            ?.offlineSlate
            ?.liveStreamOfflineSlateRenderer
            ?.scheduledStartTime
            ?.toLongOrNull()
            ?.times(1000L)
            ?.takeIf { it > System.currentTimeMillis() }
    val isUpcoming =
        scheduledStartMs != null ||
            response.playabilityStatus.status.equals("LIVE_STREAM_OFFLINE", ignoreCase = true)
    val isLive = !isUpcoming && (details.isLive == true || details.isLiveContent == true)
    val duration = details.lengthSeconds.toIntOrNull()?.takeIf { it > 0 } ?: 0
    if (!isUpcoming && !isLive && duration <= 0) return null

    val bestThumbnail =
        details.thumbnail
            ?.thumbnails
            ?.maxByOrNull { (it.width ?: 0) * (it.height ?: 0) }
            ?.url
            ?.let { ThumbnailUrlResolver.normalizeVideoThumbnail(video.id, it) }
            ?: ThumbnailUrlResolver.normalizeVideoThumbnail(video.id, video.thumbnailUrl)

    return video.copy(
        title = details.title?.takeIf { it.isNotBlank() } ?: video.title,
        channelName = details.author?.takeIf { it.isNotBlank() } ?: video.channelName,
        channelId = details.channelId.takeIf { it.isNotBlank() } ?: video.channelId,
        thumbnailUrl = bestThumbnail,
        duration = if (isLive || isUpcoming) 0 else duration,
        viewCount = maxOf(video.viewCount, details.viewCount?.toLongOrNull() ?: 0L),
        isLive = isLive || (!isUpcoming && video.isLive),
        isUpcoming = isUpcoming,
        isScheduledLive = isUpcoming && details.isLiveContent == true,
        timestamp = scheduledStartMs ?: video.timestamp,
        uploadDate = scheduledStartMs?.let(::premiereDateText) ?: video.uploadDate,
    )
}

/** Folds a player lookup into the feed row it was made for, keeping what the lookup left blank. */
internal fun Video.withPlayerMetadata(enriched: Video): Video =
    copy(
        title = enriched.title.takeIf { it.isNotBlank() } ?: title,
        channelName = enriched.channelName.takeIf { it.isNotBlank() } ?: channelName,
        channelId = enriched.channelId.takeIf { it.isNotBlank() } ?: channelId,
        thumbnailUrl = enriched.thumbnailUrl.takeIf { it.isNotBlank() } ?: thumbnailUrl,
        duration = enriched.duration.takeIf { it > 0 } ?: duration,
        viewCount = maxOf(viewCount, enriched.viewCount),
        isLive = enriched.isLive || (!enriched.isUpcoming && isLive),
        isUpcoming = enriched.isUpcoming,
        isScheduledLive = enriched.isScheduledLive,
        timestamp = if (enriched.isUpcoming) enriched.timestamp else timestamp,
        uploadDate = if (enriched.isUpcoming) enriched.uploadDate else uploadDate,
    )
