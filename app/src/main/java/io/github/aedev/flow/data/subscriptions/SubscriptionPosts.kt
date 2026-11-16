package io.github.aedev.flow.data.subscriptions

import io.github.aedev.flow.data.community.PostAge
import io.github.aedev.flow.innertube.pages.renderer.CommunityPost
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

data class SubscriptionPost(
    val post: CommunityPost,
    val channelId: String,
    val publishedAt: Long,
)

data class PostsChannel(
    val id: String,
    val name: String,
    val avatarUrl: String,
)

/** Newest cached upload first, so the most active channels are read first; channels without one keep their order at the end. */
internal fun List<PostsChannel>.byLatestUpload(latestUploadAt: Map<String, Long>): List<PostsChannel> =
    sortedByDescending { latestUploadAt[it.id] ?: Long.MIN_VALUE }

internal fun recentPosts(
    channelId: String,
    posts: List<CommunityPost>,
    hl: String?,
    now: Long,
): List<SubscriptionPost> =
    posts.mapNotNull { post ->
        PostAge
            .publishedAt(post.publishedTimeText, hl, now)
            ?.takeIf { PostAge.isRecent(it, now) }
            ?.let { SubscriptionPost(post, channelId, it) }
    }

internal fun mergeByAge(round: List<List<SubscriptionPost>>): List<SubscriptionPost> =
    round
        .flatten()
        .distinctBy { it.post.id }
        .sortedByDescending(SubscriptionPost::publishedAt)

/** Each channel's recent posts as last fetched, for [ttl]. */
internal class ChannelPostsCache(
    private val ttl: Duration,
) {
    private class Entry(
        val posts: List<SubscriptionPost>,
        val fetchedAt: Long,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun get(
        channelId: String,
        now: Long,
    ): List<SubscriptionPost>? = entries[channelId]?.takeIf { now - it.fetchedAt < ttl.toMillis() }?.posts

    fun put(
        channelId: String,
        posts: List<SubscriptionPost>,
        now: Long,
    ) {
        entries[channelId] = Entry(posts, now)
    }

    fun clear() = entries.clear()
}
