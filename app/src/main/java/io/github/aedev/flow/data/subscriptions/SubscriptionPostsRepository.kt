package io.github.aedev.flow.data.subscriptions

import android.util.Log
import io.github.aedev.flow.data.local.SubscriptionRepository
import io.github.aedev.flow.data.recommendation.FeedExclusions
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** One round of channels: their recent posts, and whether every channel failed with nothing cached to show. */
data class SubscriptionPostsRound(
    val posts: List<SubscriptionPost>,
    val allFailed: Boolean,
)

/**
 * Community posts from subscribed channels. YouTube has no combined posts feed, so each channel costs
 * one browse; results stay in memory for [CACHE_TTL_MINUTES] and nothing is fetched until the Posts view asks.
 */
@Singleton
class SubscriptionPostsRepository
    @Inject
    constructor(
        private val subscriptionRepository: SubscriptionRepository,
        private val feedRepository: SubscriptionFeedRepository,
    ) {
        private val cache = ChannelPostsCache(Duration.ofMinutes(CACHE_TTL_MINUTES))

        /** The subscribed channels in [scope] (every one when null) that [exclusions] allow, most active first. */
        suspend fun channels(
            scope: Set<String>?,
            exclusions: FeedExclusions,
        ): List<PostsChannel> {
            val channels =
                subscriptionRepository
                    .getAllSubscriptions()
                    .first()
                    .asSequence()
                    .filter { scope == null || it.channelId in scope }
                    .filterNot { exclusions.hidesChannel(it.channelId) }
                    .distinctBy { it.channelId }
                    .map { PostsChannel(id = it.channelId, name = it.channelName, avatarUrl = it.channelThumbnail) }
                    .toList()
            return channels.byLatestUpload(feedRepository.latestUploadByChannel())
        }

        /** Each of [channels]' first posts page in one bounded round, from the cache while it is fresh. */
        suspend fun recentPosts(channels: List<PostsChannel>): SubscriptionPostsRound =
            coroutineScope {
                val now = System.currentTimeMillis()
                val hl = YouTube.locale.hl
                val fetched = AtomicInteger()
                val results =
                    channels
                        .map { channel ->
                            async(PerformanceDispatcher.networkIO) {
                                cache.get(channel.id, now)?.let { return@async it }
                                fetched.incrementAndGet()
                                YouTube
                                    .communityPosts(channel.id, channel.name, channel.avatarUrl)
                                    .map { page -> recentPosts(channel.id, page.posts, hl, now) }
                                    .onSuccess { cache.put(channel.id, it, now) }
                                    .onFailure { Log.w(TAG, "Posts of ${channel.id} failed: ${it.message}") }
                                    .getOrNull()
                            }
                        }.awaitAll()
                val failed = results.count { it == null }
                Log.i(TAG, "Posts round: ${channels.size} channels, ${fetched.get()} fetched, $failed failed")
                SubscriptionPostsRound(
                    posts = mergeByAge(results.filterNotNull()),
                    allFailed = channels.isNotEmpty() && failed == channels.size,
                )
            }

        fun invalidate() = cache.clear()

        private companion object {
            const val TAG = "SubscriptionPosts"
            const val CACHE_TTL_MINUTES = 30L
        }
    }
