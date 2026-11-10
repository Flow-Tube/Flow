package io.github.aedev.flow.ui.screens.subscriptions

import io.github.aedev.flow.R
import io.github.aedev.flow.data.innertube.SubscriptionRefreshStage
import io.github.aedev.flow.data.local.ChannelSubscription
import io.github.aedev.flow.data.model.Channel
import io.github.aedev.flow.data.model.SubscriptionGroup
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.utils.ThumbnailUrlResolver

enum class SubscriptionSortMode {
    DEFAULT,
    NAME_ASC,
    RECENTLY_UPDATED,
    MOST_WATCHED,
    ;

    companion object {
        fun fromStorage(value: String?): SubscriptionSortMode = entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}

internal fun List<ChannelSubscription>.toSubscribedChannels(): List<Channel> =
    map { sub ->
        Channel(
            id = sub.channelId,
            name = sub.channelName,
            thumbnailUrl = ThumbnailUrlResolver.resolveChannelAvatar(sub.channelThumbnail),
            subscriberCount = 0L,
            isSubscribed = true,
            isMusic = sub.isMusic,
        )
    }

/** The counter line for a refresh in [this] stage. */
internal fun SubscriptionRefreshStage.progressTemplate(): Int =
    when (this) {
        SubscriptionRefreshStage.RSS -> R.plurals.subscriptions_refresh_progress_template
        SubscriptionRefreshStage.CHECKS -> R.plurals.subscriptions_refresh_checks_template
    }

internal fun List<Video>.withSubscriptionAvatars(channels: List<Channel>): List<Video> {
    val avatarByChannelId =
        channels
            .asSequence()
            .filter { it.thumbnailUrl.isNotBlank() }
            .associate { it.id to ThumbnailUrlResolver.resolveChannelAvatar(it.thumbnailUrl) }
    if (avatarByChannelId.isEmpty()) return this

    return map { video ->
        val normalizedExistingAvatar = ThumbnailUrlResolver.resolveChannelAvatar(video.channelThumbnailUrl)
        if (video.channelThumbnailUrl.isBlank()) {
            avatarByChannelId[video.channelId]?.let { avatar ->
                video.copy(
                    channelThumbnailUrl = avatar,
                    channelThumbnailUrls = video.channelThumbnailUrls.ifEmpty { listOf(avatar) },
                )
            } ?: video
        } else if (normalizedExistingAvatar != video.channelThumbnailUrl) {
            video.copy(channelThumbnailUrl = normalizedExistingAvatar)
        } else {
            video
        }
    }
}

data class SubscriptionsUiState(
    val subscribedChannels: List<Channel> = emptyList(),
    val recentVideos: List<Video> = emptyList(),
    val shorts: List<Video> = emptyList(),
    val selectedChannelId: String? = null,
    val isLoading: Boolean = false,
    val isFullWidthView: Boolean = false,
    val sortMode: SubscriptionSortMode = SubscriptionSortMode.DEFAULT,
    val isShortsShelfEnabled: Boolean = true,
    val notificationStates: Map<String, Boolean> = emptyMap(),
    val groups: List<SubscriptionGroup> = emptyList(),
    val selectedGroupName: String? = null,
    val ungroupedEnabled: Boolean = false,
    val ungroupedPosition: Int = UNGROUPED_LAST,
    val showPostsChip: Boolean = true,
    val pinnedChannelIds: List<String> = emptyList(),
    /** Watched videos per channel, loaded only for the Most watched sort. */
    val channelWatchCounts: Map<String, Int> = emptyMap(),
    val refreshStage: SubscriptionRefreshStage = SubscriptionRefreshStage.RSS,
    val refreshProcessedChannels: Int = 0,
    val refreshTotalChannels: Int = 0,
    val lastRefreshTime: Long = 0L,
    val lastRefreshText: String? = null,
    val lastRefreshVideoCount: Int = 0,
    val showLastRefreshVideoCount: Boolean = true,
    val showSubscriptionVideos: Boolean = true,
    val showSubscriptionShorts: Boolean = true,
    val showSubscriptionLive: Boolean = true,
    val showSubscriptionUpcoming: Boolean = true,
    val excludedShortsChannelIds: Set<String> = emptySet(),
    /** Channels the last refresh could not reach at all; surfaced instead of silently showing less. */
    val failedChannelIds: Set<String> = emptySet(),
    val failedChannelReasons: Map<String, String> = emptyMap(),
) {
    /** The selected group's channels, which scope its refreshes and reads; null for every subscription. */
    fun selectedGroupScope(): Set<String>? = groupSelectionScope(selectedChip(), groups, subscribedChannels.map { it.id })

    /** The selected chip, or null for All when the stored selection has no chip, like a switched-off Ungrouped. */
    fun selectedChip(chips: List<SubscriptionGroup> = groupChips()): String? {
        val name = selectedGroupName ?: return null
        return name.takeIf { chips.any { chip -> chip.name == name } }
    }

    /** The rows of Manage groups: the groups, plus Ungrouped while it is switched on and there is a group. */
    fun managedGroups(): List<SubscriptionGroup> =
        if (ungroupedEnabled && groups.isNotEmpty()) {
            groupsWithUngrouped(groups, ungroupedPosition, ungroupedChannelIds(subscribedChannels.map { it.id }, groups))
        } else {
            groups
        }

    /** The group chips: [managedGroups] without an Ungrouped that would show nothing. */
    fun groupChips(): List<SubscriptionGroup> = managedGroups().filterNot { it.isUngrouped() && it.channelIds.isEmpty() }

    /** Display names for [failedChannelIds], falling back to the raw id for an unknown channel. */
    val failedChannelNames: List<String>
        get() {
            if (failedChannelIds.isEmpty()) return emptyList()
            val namesById = subscribedChannels.associate { it.id to it.name }
            return failedChannelIds
                .map { id -> namesById[id]?.takeIf { it.isNotBlank() } ?: id }
                .sorted()
        }
}
