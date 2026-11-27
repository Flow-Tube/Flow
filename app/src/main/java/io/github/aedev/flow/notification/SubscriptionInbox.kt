package io.github.aedev.flow.notification

import android.util.Log
import io.github.aedev.flow.data.local.NotificationRepository
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.SubscriptionRepository
import io.github.aedev.flow.data.local.entity.NotificationEntity
import io.github.aedev.flow.data.model.Video
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lists new uploads from every followed channel in the inbox. Each path that adds rows to the
 * subscription feed cache hands them here, because a row is new only once: the next writer sees it
 * as cached.
 */
@Singleton
class SubscriptionInbox
    @Inject
    constructor(
        private val inbox: NotificationRepository,
        private val preferences: PlayerPreferences,
        private val subscriptions: SubscriptionRepository,
    ) {
        /** [added] rows just written to the feed cache, [cachedBefore] that cache's rows for their channels beforehand. */
        suspend fun record(
            added: List<Video>,
            cachedBefore: List<Video>,
            now: Long,
        ) {
            if (added.isEmpty()) return
            try {
                val picked =
                    selectInboxUploads(
                        written = added,
                        cachedBefore = cachedBefore,
                        followedAt = subscriptions.getAllSubscriptions().first().associate { it.channelId to it.subscribedAt },
                        sinceMs = preferences.inboxFeedSince(now - INBOX_BACKFILL_MS),
                        showShorts = preferences.effectiveSubscriptionShowShorts.first(),
                        notifyShorts = preferences.notifShortsEnabled.first(),
                        now = now,
                    )
                if (picked.isNotEmpty()) inbox.recordNew(picked.map { it.toInboxEntry() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't list new uploads in the inbox", e)
            }
        }

        private companion object {
            const val TAG = "SubscriptionInbox"
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
