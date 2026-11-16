package io.github.aedev.flow.data.subscriptions

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.pages.renderer.CommunityPost
import org.junit.Test
import java.time.Duration

class SubscriptionPostsTest {
    private val now = 1_700_000_000_000L

    @Test
    fun `channels with the newest uploads come first and uncached ones keep their order at the end`() {
        val channels = listOf(channel("quiet"), channel("busy"), channel("unknownA"), channel("mid"), channel("unknownB"))
        val latest = mapOf("quiet" to 10L, "busy" to 300L, "mid" to 200L)

        assertThat(channels.byLatestUpload(latest).map(PostsChannel::id))
            .containsExactly("busy", "mid", "quiet", "unknownA", "unknownB")
            .inOrder()
    }

    @Test
    fun `keeps a channel's posts from the last thirty days and drops the rest`() {
        val posts =
            listOf(
                post("new", "2 days ago"),
                post("edited", "3 weeks ago (edited)"),
                post("old", "1 month ago"),
                post("unknown", ""),
            )

        val recent = recentPosts("c1", posts, hl = "en", now = now)

        assertThat(recent.map { it.post.id }).containsExactly("new", "edited").inOrder()
        assertThat(recent.map(SubscriptionPost::channelId).toSet()).containsExactly("c1")
        assertThat(recent.first().publishedAt).isEqualTo(now - Duration.ofDays(2).toMillis())
    }

    @Test
    fun `merges a round of channels newest first, keeping channel order on equal ages`() {
        val round =
            listOf(
                recentPosts("a", listOf(post("a1", "5 days ago"), post("a2", "1 hour ago")), "en", now),
                recentPosts("b", listOf(post("b1", "5 days ago"), post("b2", "2 weeks ago")), "en", now),
                recentPosts("c", listOf(post("a1", "5 days ago")), "en", now),
            )

        assertThat(mergeByAge(round).map { it.post.id })
            .containsExactly("a2", "a1", "b1", "b2")
            .inOrder()
    }

    @Test
    fun `cached posts expire after the time to live and clear on demand`() {
        val cache = ChannelPostsCache(ttl = Duration.ofMinutes(30))
        val posts = recentPosts("a", listOf(post("a1", "1 day ago")), "en", now)
        cache.put("a", posts, now)

        assertThat(cache.get("a", now + Duration.ofMinutes(29).toMillis())).isEqualTo(posts)
        assertThat(cache.get("a", now + Duration.ofMinutes(30).toMillis())).isNull()

        cache.put("a", posts, now)
        cache.clear()
        assertThat(cache.get("a", now)).isNull()
    }

    private fun channel(id: String) = PostsChannel(id = id, name = id, avatarUrl = "")

    private fun post(
        id: String,
        published: String,
    ) = CommunityPost(
        id = id,
        authorName = "Author",
        authorAvatarUrl = "",
        text = "",
        attachment = null,
        likeCountText = "",
        commentCountText = "",
        commentEndpointParams = null,
        publishedTimeText = published,
    )
}
