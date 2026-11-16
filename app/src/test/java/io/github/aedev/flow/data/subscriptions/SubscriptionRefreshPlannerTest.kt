package io.github.aedev.flow.data.subscriptions

import io.github.aedev.flow.data.local.ChannelSubscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRefreshPlannerTest {
    private val now = 1_700_000_000_000L
    private val ttl = SubscriptionRefreshPlanner.CHANNEL_FEED_TTL_MS

    private fun subscription(
        id: String,
        lastFeedFetchAt: Long = now,
        lastCheckTime: Long = 0L,
    ) = ChannelSubscription(
        channelId = id,
        channelName = "Channel $id",
        channelThumbnail = "",
        lastCheckTime = lastCheckTime,
        lastFeedFetchAt = lastFeedFetchAt,
    )

    @Test
    fun `no subscriptions means nothing to do`() {
        val plan = SubscriptionRefreshPlanner.plan(emptyList(), now)

        assertTrue(plan.isEmpty)
        assertFalse(plan.isFullRefresh)
    }

    @Test
    fun `fresh channels are skipped`() {
        val plan =
            SubscriptionRefreshPlanner.plan(
                listOf(subscription("a"), subscription("b")),
                now,
            )

        assertTrue(plan.isEmpty)
    }

    @Test
    fun `only the aged out channel is fetched`() {
        val plan =
            SubscriptionRefreshPlanner.plan(
                listOf(
                    subscription("fresh", lastFeedFetchAt = now - ttl / 2),
                    subscription("stale", lastFeedFetchAt = now - ttl - 1),
                ),
                now,
            )

        assertEquals(listOf("stale"), plan.channelIds)
        assertFalse(plan.isFullRefresh)
    }

    @Test
    fun `a never fetched channel is always included`() {
        val plan =
            SubscriptionRefreshPlanner.plan(
                listOf(subscription("known"), subscription("brand-new", lastFeedFetchAt = 0L)),
                now,
            )

        assertEquals(listOf("brand-new"), plan.channelIds)
    }

    @Test
    fun `a pending upload signal beats the ttl`() {
        val plan =
            SubscriptionRefreshPlanner.plan(
                listOf(
                    subscription("quiet", lastFeedFetchAt = now - 1000L),
                    subscription("posted", lastFeedFetchAt = now - 1000L, lastCheckTime = now - 500L),
                ),
                now,
            )

        assertEquals(listOf("posted"), plan.channelIds)
    }

    @Test
    fun `an upload signal older than the last fetch is already covered`() {
        val plan =
            SubscriptionRefreshPlanner.plan(
                listOf(subscription("covered", lastFeedFetchAt = now - 1000L, lastCheckTime = now - 5000L)),
                now,
            )

        assertTrue(plan.isEmpty)
    }

    @Test
    fun `a fetch timestamp in the future is treated as stale`() {
        val plan =
            SubscriptionRefreshPlanner.plan(
                listOf(subscription("clock-skew", lastFeedFetchAt = now + 60_000L)),
                now,
            )

        assertEquals(listOf("clock-skew"), plan.channelIds)
    }

    @Test
    fun `force fetches every channel and reports a full refresh`() {
        val plan =
            SubscriptionRefreshPlanner.plan(
                listOf(subscription("a"), subscription("b")),
                now,
                force = true,
            )

        assertEquals(listOf("a", "b"), plan.channelIds)
        assertTrue(plan.isFullRefresh)
    }

    @Test
    fun `every channel being stale is reported as a full refresh`() {
        val plan =
            SubscriptionRefreshPlanner.plan(
                listOf(
                    subscription("a", lastFeedFetchAt = 0L),
                    subscription("b", lastFeedFetchAt = 0L),
                ),
                now,
            )

        assertEquals(listOf("a", "b"), plan.channelIds)
        assertTrue(plan.isFullRefresh)
    }

    @Test
    fun `a group plan covers only its channels and is never full`() {
        val plan =
            SubscriptionRefreshPlanner.planForGroup(
                listOf(subscription("a"), subscription("b"), subscription("c")),
                groupChannelIds = setOf("a", "b"),
                now = now,
                force = true,
            )

        assertEquals(listOf("a", "b"), plan.channelIds)
        assertFalse(plan.isFullRefresh)
        assertEquals(setOf("a", "b"), plan.scope)
    }

    @Test
    fun `a group whose channels are all stale is still not full`() {
        val plan =
            SubscriptionRefreshPlanner.planForGroup(
                listOf(subscription("a", lastFeedFetchAt = 0L), subscription("b")),
                groupChannelIds = setOf("a"),
                now = now,
            )

        assertEquals(listOf("a"), plan.channelIds)
        assertFalse(plan.isFullRefresh)
    }

    @Test
    fun `a fresh group fetches nothing`() {
        val plan =
            SubscriptionRefreshPlanner.planForGroup(listOf(subscription("a")), groupChannelIds = setOf("a"), now = now)

        assertTrue(plan.isEmpty)
    }

    @Test
    fun `a small group reads every channel's tabs and a large one does not`() {
        val small = (1..SubscriptionRefreshPlanner.DEEP_READ_MAX_CHANNELS).map { subscription("s$it") }
        val large = (1..SubscriptionRefreshPlanner.DEEP_READ_MAX_CHANNELS + 1).map { subscription("l$it") }

        val smallPlan =
            SubscriptionRefreshPlanner.planForGroup(small, small.mapTo(HashSet()) { it.channelId }, now, force = true)
        val largePlan =
            SubscriptionRefreshPlanner.planForGroup(large, large.mapTo(HashSet()) { it.channelId }, now, force = true)

        assertTrue(smallPlan.readAllTabs)
        assertFalse(largePlan.readAllTabs)
    }

    @Test
    fun `a plan for every subscription reads tabs only where RSS falls short`() {
        val plan = SubscriptionRefreshPlanner.plan(listOf(subscription("a")), now, force = true)

        assertFalse(plan.readAllTabs)
        assertEquals(null, plan.scope)
    }

    @Test
    fun `an All refresh plans only the channels All shows (1387)`() {
        val subscriptions = listOf(subscription("a", lastFeedFetchAt = 0L), subscription("b", lastFeedFetchAt = 0L), subscription("c"))

        val stale = SubscriptionRefreshPlanner.planForAll(subscriptions, shown = setOf("a", "c"), now = now)
        val forced = SubscriptionRefreshPlanner.planForAll(subscriptions, shown = setOf("a", "c"), now = now, force = true)

        assertEquals(listOf("a"), stale.channelIds)
        assertFalse(stale.isFullRefresh)
        assertEquals(listOf("a", "c"), forced.channelIds)
        assertFalse(forced.readAllTabs)
        assertEquals(null, forced.scope)
    }

    @Test
    fun `an All refresh with nothing left out is the plain plan`() {
        val subscriptions = listOf(subscription("a", lastFeedFetchAt = 0L), subscription("b"))

        assertEquals(
            SubscriptionRefreshPlanner.plan(subscriptions, now, force = true),
            SubscriptionRefreshPlanner.planForAll(subscriptions, shown = null, now = now, force = true),
        )
    }
}
