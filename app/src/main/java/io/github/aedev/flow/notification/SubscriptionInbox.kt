package io.github.aedev.flow.notification

import io.github.aedev.flow.data.local.ChannelSubscription
import io.github.aedev.flow.data.local.NotificationRepository
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.entity.NotificationEntity
import io.github.aedev.flow.data.model.Video
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Lists the uploads a subscription feed refresh brings in, from every followed channel, in the inbox. */
@Singleton
class SubscriptionInbox
    @Inject
    constructor(
        private val inbox: NotificationRepository,
        private val preferences: PlayerPreferences,
    ) {
        suspend fun record(
            written: List<Video>,
            cachedBefore: List<Video>,
            subscriptions: List<ChannelSubscription>,
            now: Long,
        ) {
            val picked =
                selectInboxUploads(
                    written = written,
                    cachedBefore = cachedBefore,
                    followedAt = subscriptions.associate { it.channelId to it.subscribedAt },
                    sinceMs = preferences.inboxFeedSince(now - INBOX_BACKFILL_MS),
                    showShorts = preferences.effectiveSubscriptionShowShorts.first(),
                    notifyShorts = preferences.notifShortsEnabled.first(),
                    now = now,
                )
            if (picked.isNotEmpty()) inbox.recordNew(picked.map { it.toInboxEntry() })
        }
    }

internal const val INBOX_BACKFILL_MS = 24L * 60L * 60L * 1000L
internal const val INBOX_WINDOW_MS = 7L * INBOX_BACKFILL_MS

/**
 * The rows of a feed write the inbox should list: not cached before the write, published after
 * [sinceMs] and after the channel was followed, within the last week, and not yet to premiere. A
 * channel the cache held nothing for (newly followed, or a cleared cache) adds only its last day, so
 * it can't flood the inbox with a week of uploads.
 */
internal fun selectInboxUploads(
    written: List<Video>,
    cachedBefore: List<Video>,
    followedAt: Map<String, Long>,
    sinceMs: Long,
    showShorts: Boolean,
    notifyShorts: Boolean,
    now: Long,
): List<Video> {
    val known = cachedBefore.mapTo(HashSet()) { it.id }
    val seenChannels = cachedBefore.mapTo(HashSet()) { it.channelId }
    val reels = written.filter { it.isShort }.mapTo(HashSet()) { it.id }
    return written.filter { video ->
        val followed = followedAt[video.channelId] ?: return@filter false
        val firstSight = if (video.channelId in seenChannels) 0L else now - INBOX_BACKFILL_MS
        val floor = maxOf(sinceMs, followed, now - INBOX_WINDOW_MS, firstSight)
        video.id.isNotBlank() &&
            video.id !in known &&
            !video.isUpcoming &&
            video.timestamp > floor &&
            SubscriptionNotificationPolicy.shouldAnnounce(video.id, reels, showShorts, notifyShorts)
    }
}

private fun Video.toInboxEntry() =
    NotificationEntity(
        videoId = id,
        title = title,
        channelName = channelName,
        thumbnailUrl = thumbnailUrl.ifBlank { null },
        timestamp = timestamp,
        type = "NEW_VIDEO",
    )
