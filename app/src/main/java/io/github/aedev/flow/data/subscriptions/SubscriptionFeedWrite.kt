package io.github.aedev.flow.data.subscriptions

import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.subscriptions.SubscriptionFeedMerger.preservingEnrichedMetadata

/**
 * What one write of a refresh puts back in the feed cache: [rows] replace the rows of the channels
 * written. [fetchedChannelIds] are the channels that actually answered, the only ones that may be
 * stamped fresh.
 */
internal data class SubscriptionFeedWrite(
    val rows: List<Video>,
    val fetchedChannelIds: List<String>,
)

/**
 * A channel keeps its earlier rows when it is written again: RSS lists only its last fifteen
 * uploads, so replacing a channel with what one pass saw would cut a quiet channel down to a week or
 * two (#1099). The lookback window still ages rows out, and each channel keeps its newest
 * [maxPerChannel].
 *
 * A cached row of an upload the pass knows is a reel ([reelIds]) is filed as a Short: an earlier
 * pass that could not tell may have kept it as a video, and RSS repeats a reel for weeks (#1393).
 *
 * A channel that failed this pass is not stamped fresh, so the next pass retries it instead of
 * showing it empty for the whole TTL (#1094). A channel unsubscribed while the refresh ran is
 * dropped, fetched rows and all.
 */
internal fun subscriptionFeedWrite(
    channelIds: List<String>,
    freshVideos: List<Video>,
    cachedSlice: List<Video>,
    failedChannelIds: Set<String>,
    subscribedChannelIds: Set<String>,
    now: Long,
    windowMs: Long,
    maxPerChannel: Int,
    reelIds: Set<String> = emptySet(),
): SubscriptionFeedWrite {
    val cached = cachedSlice.map { if (!it.isShort && it.id in reelIds) it.copy(isShort = true) else it }
    val priorById =
        cached
            .filter { it.id.isNotBlank() }
            .groupBy { it.id }
            .mapValues { (_, candidates) -> SubscriptionFeedMerger.mergeDuplicates(candidates, now) }
    val rows =
        SubscriptionFeedMerger
            .mergeSubscriptionFeed(
                freshVideos = freshVideos.map { fresh -> fresh.preservingEnrichedMetadata(priorById[fresh.id]) },
                cachedVideos = cached,
                now = now,
                windowMs = windowMs,
                maxItems = Int.MAX_VALUE,
            ).filter { it.channelId.isBlank() || it.channelId in subscribedChannelIds }
            .newestPerChannel(maxPerChannel)
            .withHighQualityThumbnails()
    return SubscriptionFeedWrite(
        rows = rows,
        fetchedChannelIds = channelIds.filter { it !in failedChannelIds },
    )
}

/** Keeps the first [max] rows of each channel, in the order given, which is newest first. */
private fun List<Video>.newestPerChannel(max: Int): List<Video> {
    val kept = HashMap<String, Int>()
    return filter { video ->
        val count = kept.getOrDefault(video.channelId, 0)
        kept[video.channelId] = count + 1
        count < max
    }
}
