package io.github.aedev.flow.data.subscriptions

import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.utils.ThumbnailUrlResolver

/**
 * Reconciles the same video arriving from more than one source.
 *
 * RSS, the channel tabs and the on-demand player lookup each know different fields well, so a
 * refresh must combine them rather than let the last writer win — otherwise an enriched duration or
 * a real upload date is lost the next time a thinner source reports the same id.
 */
object SubscriptionFeedMerger {
    /**
     * [freshVideos] wins on identity and ordering; [cachedVideos] contributes anything the fresh
     * copy does not know. Items outside the lookback window are dropped unless they are upcoming.
     */
    fun mergeSubscriptionFeed(
        freshVideos: List<Video>,
        cachedVideos: List<Video>,
        now: Long,
        windowMs: Long,
        maxItems: Int,
    ): List<Video> {
        val cutoff = now - windowMs
        return (freshVideos + cachedVideos)
            .asSequence()
            .filter { video ->
                SubscriptionFeedTimestamps.effectiveUploadTimestamp(video, now) >= cutoff || video.isUpcoming
            }.toList()
            .groupBy { it.id }
            .values
            .map { candidates -> mergeDuplicates(candidates, now) }
            .withStableUploadSortKeys(now)
            .take(maxItems)
    }

    fun mergeDuplicates(
        candidates: List<Video>,
        now: Long,
    ): Video {
        val primary = candidates.first()
        // RSS dates a premiere by when it was created; its start time only comes from the channel tab (#1430).
        val futureUpcoming =
            candidates.firstOrNull { candidate ->
                candidate.isUpcoming &&
                    SubscriptionFeedTimestamps.effectiveUploadTimestamp(candidate, now) > now + 60_000L
            }
        val metadataSource =
            when {
                futureUpcoming != null -> {
                    futureUpcoming
                }

                SubscriptionFeedTimestamps.hasStableUploadMetadata(primary, now) -> {
                    primary
                }

                else -> {
                    candidates.firstOrNull { SubscriptionFeedTimestamps.hasStableUploadMetadata(it, now) }
                        ?: primary
                }
            }
        val hasStableMetadata = SubscriptionFeedTimestamps.hasStableUploadMetadata(metadataSource, now)
        val metadataTimestamp =
            SubscriptionFeedTimestamps
                .effectiveUploadTimestamp(metadataSource, now)
                .takeIf { hasStableMetadata && it > 0L }
        val bestChannelThumbnail =
            candidates.firstOrNull { it.channelThumbnailUrl.isNotBlank() }?.channelThumbnailUrl
                ?: primary.channelThumbnailUrl
        val bestChannelThumbnails =
            candidates
                .flatMap { video -> video.channelThumbnailUrls.ifEmpty { listOf(video.channelThumbnailUrl) } }
                .filter { it.isNotBlank() }
                .distinct()
        val bestVideoThumbnail =
            ThumbnailUrlResolver.preferredVideoThumbnail(
                videoId = primary.id,
                urls = candidates.map { it.thumbnailUrl },
            )
        val bestDescription =
            candidates.firstOrNull { it.description.isNotBlank() }?.description
                ?: primary.description

        return primary.copy(
            viewCount = candidates.maxOf { it.viewCount },
            thumbnailUrl = bestVideoThumbnail,
            uploadDate = if (hasStableMetadata) metadataSource.uploadDate else "",
            timestamp = metadataTimestamp ?: 0L,
            timestampIsExact = metadataSource.timestampIsExact && metadataTimestamp == metadataSource.timestamp,
            duration = candidates.maxOf { it.duration },
            description = bestDescription,
            channelThumbnailUrl = bestChannelThumbnail,
            channelThumbnailUrls = bestChannelThumbnails,
            isShort = candidates.any { it.isShort },
            isLive = candidates.any { it.isLive },
            isUpcoming = futureUpcoming != null,
        )
    }

    /**
     * Keeps fields a previous pass had already resolved — chiefly the duration and view count the
     * on-demand player lookup filled in, which no RSS entry can supply.
     */
    fun Video.preservingEnrichedMetadata(prior: Video?): Video {
        if (prior == null) return this
        return copy(
            duration = if (duration > 0) duration else prior.duration,
            viewCount = maxOf(viewCount, prior.viewCount),
            thumbnailUrl =
                ThumbnailUrlResolver.preferredVideoThumbnail(
                    videoId = id,
                    urls = listOf(thumbnailUrl, prior.thumbnailUrl),
                ),
            channelThumbnailUrl = channelThumbnailUrl.ifBlank { prior.channelThumbnailUrl },
            channelThumbnailUrls = channelThumbnailUrls.ifEmpty { prior.channelThumbnailUrls },
            description = description.ifBlank { prior.description },
        )
    }
}
