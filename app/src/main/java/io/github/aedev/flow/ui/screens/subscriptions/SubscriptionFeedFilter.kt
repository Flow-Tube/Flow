package io.github.aedev.flow.ui.screens.subscriptions

import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.recommendation.FeedExclusions
import io.github.aedev.flow.data.subscriptions.withRelativeUploadDates
import io.github.aedev.flow.data.subscriptions.withStableUploadSortKeys

/** Everything that decides which cached feed rows the Subscriptions screen shows. */
internal data class SubscriptionFeedFilters(
    val showVideos: Boolean = true,
    val showShorts: Boolean = true,
    val showLive: Boolean = true,
    val showUpcoming: Boolean = true,
    val watchedVideoIds: Set<String> = emptySet(),
    val watchedShortIds: Set<String> = emptySet(),
    val unplayableVideoIds: Set<String> = emptySet(),
    /** The selected group's channels; null when no group is selected. */
    val allowedChannelIds: Set<String>? = null,
    val excludedShortsChannelIds: Set<String> = emptySet(),
    val exclusions: FeedExclusions = FeedExclusions.NONE,
)

internal data class SubscriptionFeedSections(
    val recentVideos: List<Video>,
    val shorts: List<Video>,
)

/**
 * Splits the cached feed into the long-form list and the one-reel-per-channel Shorts shelf.
 *
 * A video marked not interested and every upload of a blocked channel stay out even though the
 * viewer subscribed (owner decision D5); the engine-inferred suppressions do not apply here.
 */
internal fun subscriptionFeedSections(
    videos: List<Video>,
    filters: SubscriptionFeedFilters,
    now: Long,
): SubscriptionFeedSections {
    val visible =
        videos
            .filter { video -> video.id !in filters.unplayableVideoIds && !filters.exclusions.hides(video) }
            .filter { video ->
                when {
                    video.isUpcoming -> filters.showUpcoming
                    video.isShort -> filters.showShorts
                    video.isLive -> filters.showLive
                    else -> filters.showVideos
                }
            }.withStableUploadSortKeys(now)
    val (shorts, regular) = visible.partition { it.isShort }
    val latestShortPerChannel =
        shorts
            .groupBy { it.channelId }
            .flatMap { (_, channelShorts) -> channelShorts.withStableUploadSortKeys(now).take(1) }
            .withStableUploadSortKeys(now)

    // A collaboration someone else uploaded belongs to the group of the collaborator it came through.
    fun Video.inSelectedGroup() =
        filters.allowedChannelIds == null ||
            channelId in filters.allowedChannelIds ||
            collaborators.any { it.channelId in filters.allowedChannelIds }

    return SubscriptionFeedSections(
        recentVideos =
            regular
                .filter { it.id !in filters.watchedVideoIds && it.inSelectedGroup() }
                .withRelativeUploadDates(now),
        shorts =
            latestShortPerChannel
                .filter { it.id !in filters.watchedShortIds && it.inSelectedGroup() }
                .filter { it.channelId !in filters.excludedShortsChannelIds }
                .withRelativeUploadDates(now),
    )
}
