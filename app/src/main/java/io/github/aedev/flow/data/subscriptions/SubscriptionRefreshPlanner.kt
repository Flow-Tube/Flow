package io.github.aedev.flow.data.subscriptions

import io.github.aedev.flow.data.local.ChannelSubscription

/**
 * Which channels the next subscription-feed refresh should actually hit.
 *
 * [isFullRefresh] means every subscribed channel is included, so the refresh may treat every upload
 * as new and drop the rows of channels no longer followed. A plan for a subscription group is never
 * full: it covers that group's channels and leaves every other row alone.
 */
data class SubscriptionRefreshPlan(
    val channelIds: List<String>,
    val isFullRefresh: Boolean,
    /** Read every channel's tabs too, not only the ones RSS cannot answer for. */
    val readAllTabs: Boolean = false,
    /** The group's channels the screen is showing; null for every subscription. */
    val scope: Set<String>? = null,
) {
    val isEmpty: Boolean get() = channelIds.isEmpty()

    companion object {
        val NOTHING_TO_DO = SubscriptionRefreshPlan(channelIds = emptyList(), isFullRefresh = false)
    }
}

/**
 * Decides staleness per channel rather than for the feed as a whole.
 *
 * Previously any trigger re-fetched every subscription, so the periodic in-screen refresh cost a
 * full sweep of the subscription list. Tracking the last fetch per channel keeps a routine refresh
 * proportional to what has actually aged out.
 */
object SubscriptionRefreshPlanner {
    /** How long a single channel's slice of the feed is considered fresh. */
    const val CHANNEL_FEED_TTL_MS = 30 * 60 * 1000L

    /**
     * A group this small gets every channel's tabs read: about four requests a channel, for up to
     * sixty uploads each instead of RSS's fifteen, so a quiet group does not run dry (#1099).
     */
    const val DEEP_READ_MAX_CHANNELS = 25

    /** [plan] over one group's channels, written back without touching any other channel. */
    fun planForGroup(
        subscriptions: List<ChannelSubscription>,
        groupChannelIds: Set<String>,
        now: Long,
        force: Boolean = false,
    ): SubscriptionRefreshPlan {
        val inGroup = subscriptions.filter { it.channelId in groupChannelIds }
        return plan(inGroup, now, force = force).copy(
            isFullRefresh = false,
            readAllTabs = inGroup.size <= DEEP_READ_MAX_CHANNELS,
            scope = groupChannelIds,
        )
    }

    /**
     * [plan] over the channels the All chip shows, [shown], or every subscription when it is null.
     * The rows of channels left out are kept; they age out of the feed window on their own.
     */
    fun planForAll(
        subscriptions: List<ChannelSubscription>,
        shown: Set<String>?,
        now: Long,
        force: Boolean = false,
    ): SubscriptionRefreshPlan =
        plan(
            subscriptions = if (shown == null) subscriptions else subscriptions.filter { it.channelId in shown },
            now = now,
            force = force,
        )

    fun plan(
        subscriptions: List<ChannelSubscription>,
        now: Long,
        ttlMs: Long = CHANNEL_FEED_TTL_MS,
        force: Boolean = false,
    ): SubscriptionRefreshPlan {
        if (subscriptions.isEmpty()) return SubscriptionRefreshPlan.NOTHING_TO_DO
        if (force) {
            return SubscriptionRefreshPlan(
                channelIds = subscriptions.map { it.channelId },
                isFullRefresh = true,
            )
        }

        val stale = subscriptions.filter { it.needsFeedRefresh(now, ttlMs) }
        return SubscriptionRefreshPlan(
            channelIds = stale.map { it.channelId },
            isFullRefresh = stale.size == subscriptions.size,
        )
    }

    private fun ChannelSubscription.needsFeedRefresh(
        now: Long,
        ttlMs: Long,
    ): Boolean =
        when {
            // Never fetched, including a channel subscribed to since the last refresh.
            lastFeedFetchAt <= 0L -> true

            // A clock change backwards would otherwise pin a channel as fresh indefinitely.
            lastFeedFetchAt > now -> true

            now - lastFeedFetchAt >= ttlMs -> true

            // The background notification check already saw an upload this refresh has not.
            else -> lastCheckTime > lastFeedFetchAt
        }
}
