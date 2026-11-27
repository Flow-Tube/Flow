package io.github.aedev.flow.data.innertube

import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.subscriptions.StreamedUploadDate
import io.github.aedev.flow.utils.ThumbnailUrlResolver

/** Merge regular and shorts lists with independent caps, sorted by date. */
internal fun buildSweepFeed(
    regular: List<Video>,
    shorts: List<Video>,
): List<Video> {
    val mergedRegular =
        regular
            .sortedByDescending { it.timestamp }
            .mergeDuplicateVideos()
            .take(MAX_REGULAR_VIDEOS)
    val mergedShorts =
        shorts
            .sortedByDescending { it.timestamp }
            .mergeDuplicateVideos()
            .distinctBy { it.channelId.ifBlank { it.id } }
            .take(MAX_SHORTS)
    return (mergedRegular + mergedShorts)
        .sortedByDescending { it.timestamp }
        .mergeDuplicateVideos()
}

internal fun List<Video>.mergeDuplicateVideos(): List<Video> {
    val now = System.currentTimeMillis()
    return groupBy { it.id }
        .values
        .map { candidates ->
            val primary = candidates.first()
            val timestampSource =
                candidates
                    .filter { it.timestamp > 0L }
                    .maxByOrNull { it.timestamp }
                    ?: primary
            val bestChannelThumbnail =
                candidates.firstOrNull { it.channelThumbnailUrl.isNotBlank() }?.channelThumbnailUrl
                    ?: primary.channelThumbnailUrl
            val bestVideoThumbnail =
                candidates
                    .asSequence()
                    .map { ThumbnailUrlResolver.normalizeVideoThumbnail(it.id, it.thumbnailUrl) }
                    .firstOrNull { it.isNotBlank() }
                    ?: ThumbnailUrlResolver.normalizeVideoThumbnail(primary.id, primary.thumbnailUrl)
            val bestDescription =
                candidates.firstOrNull { it.description.isNotBlank() }?.description
                    ?: primary.description
            val isLive = candidates.any { it.isLive }
            val isUpcoming = candidates.any { it.isUpcoming && it.timestamp > now + 60_000L }
            val streamed = !isLive && !isUpcoming && candidates.any { StreamedUploadDate.isMarked(it.uploadDate) }

            primary.copy(
                duration = candidates.maxOf { it.duration },
                viewCount = candidates.maxOf { it.viewCount },
                thumbnailUrl = bestVideoThumbnail,
                uploadDate = StreamedUploadDate.markedIf(streamed, timestampSource.uploadDate),
                timestamp = timestampSource.timestamp,
                timestampIsExact = timestampSource.timestampIsExact,
                description = bestDescription,
                channelThumbnailUrl = bestChannelThumbnail,
                isShort = candidates.any { it.isShort },
                isLive = isLive,
                isUpcoming = isUpcoming,
            )
        }.sortedByDescending { it.timestamp }
}

internal fun compactAccumulator(
    videos: MutableList<Video>,
    maxSize: Int,
) {
    if (videos.size <= maxSize * 2) return
    val compacted = videos.mergeDuplicateVideos().take(maxSize)
    videos.clear()
    videos.addAll(compacted)
}

internal const val MAX_REGULAR_VIDEOS = 3000
internal const val MAX_SHORTS = 300
