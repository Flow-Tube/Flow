package io.github.aedev.flow.player

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.RelatedCollection
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.recommendation.FeedExclusions
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PlayerRelatedVideosPolicyTest {
    @Test
    fun `fallback supplies related videos when primary metadata is empty`() {
        val fallback = listOf(video("related"))

        val selected =
            PlayerRelatedVideosPolicy.select(
                videoId = "playing",
                primary = emptyList(),
                fallback = fallback,
                current = emptyList(),
            )

        assertThat(selected).containsExactlyElementsIn(fallback)
    }

    @Test
    fun `empty enrichment never clears current related videos`() {
        val current = listOf(video("current"))

        val selected =
            PlayerRelatedVideosPolicy.select(
                videoId = "playing",
                primary = emptyList(),
                fallback = emptyList(),
                current = current,
            )

        assertThat(selected).containsExactlyElementsIn(current)
    }

    @Test
    fun `selection removes playing video blank ids and duplicates`() {
        val duplicate = video("related")

        val selected =
            PlayerRelatedVideosPolicy.select(
                videoId = "playing",
                primary = listOf(video("playing"), video(""), duplicate, duplicate.copy(title = "duplicate")),
                fallback = emptyList(),
                current = emptyList(),
            )

        assertThat(selected).containsExactly(duplicate)
    }

    @Test
    fun `sanitizing display candidates keeps live and upcoming videos`() {
        val live = video("live").copy(isLive = true)
        val upcoming = video("upcoming").copy(isUpcoming = true)

        val selected = PlayerRelatedVideosPolicy.sanitize("playing", listOf(live, upcoming))

        assertThat(selected).containsExactly(live, upcoming).inOrder()
    }

    @Test
    fun `sanitizing drops reels when shorts are disabled`() {
        val reel = video("reel").copy(isShort = true)
        val shortMusicVideo = video("music").copy(duration = 45)

        val selected = PlayerRelatedVideosPolicy.sanitize("playing", listOf(reel, shortMusicVideo), shortsEnabled = false)

        assertThat(selected).containsExactly(shortMusicVideo)
    }

    @Test
    fun `sanitizing keeps reels when shorts are enabled`() {
        val reel = video("reel").copy(isShort = true)

        val selected = PlayerRelatedVideosPolicy.sanitize("playing", listOf(reel), shortsEnabled = true)

        assertThat(selected).containsExactly(reel)
    }

    @Test
    fun `a blocked creator is dropped from the related list`() {
        val candidates = listOf(video("keep", channelId = "wanted"), video("drop", channelId = "blocked"))

        val sanitized =
            PlayerRelatedVideosPolicy.sanitize(
                videoId = "playing",
                candidates = candidates,
                exclusions = FeedExclusions(blockedChannelIds = setOf("blocked")),
            )

        assertThat(sanitized.map { it.id }).containsExactly("keep")
    }

    @Test
    fun `a candidate with no channel id survives rather than being dropped blindly`() {
        val candidates = listOf(video("unknown", channelId = ""))

        val sanitized =
            PlayerRelatedVideosPolicy.sanitize(
                videoId = "playing",
                candidates = candidates,
                exclusions = FeedExclusions(blockedChannelIds = setOf("blocked")),
            )

        assertThat(sanitized.map { it.id }).containsExactly("unknown")
    }

    @Test
    fun `a source made empty by blocking falls through to the next one`() {
        val primary = listOf(video("blocked-only", channelId = "blocked"))
        val fallback = listOf(video("wanted", channelId = "wanted"))

        val selected =
            PlayerRelatedVideosPolicy.select(
                videoId = "playing",
                primary = primary,
                fallback = fallback,
                current = emptyList(),
                exclusions = FeedExclusions(blockedChannelIds = setOf("blocked")),
            )

        assertThat(selected.map { it.id }).containsExactly("wanted")
    }

    // #1031: autoplay and the queue read this list, so a video marked not interested must leave it.
    @Test
    fun `a video marked not interested and a suppressed channel are dropped`() {
        val candidates =
            listOf(
                video("keep", channelId = "wanted"),
                video("not-interested", channelId = "wanted"),
                video("suppressed", channelId = "cooling-off"),
            )

        val sanitized =
            PlayerRelatedVideosPolicy.sanitize(
                videoId = "playing",
                candidates = candidates,
                exclusions =
                    FeedExclusions(
                        suppressedVideoIds = setOf("not-interested"),
                        suppressedChannelIds = setOf("cooling-off"),
                    ),
            )

        assertThat(sanitized.map { it.id }).containsExactly("keep")
    }

    @Test
    fun `a blocked topic in the title drops the candidate`() {
        val sanitized =
            PlayerRelatedVideosPolicy.sanitize(
                videoId = "playing",
                candidates = listOf(video("keep"), video("topic")),
                exclusions = FeedExclusions(blockedText = { title, _ -> title == "topic" }),
            )

        assertThat(sanitized.map { it.id }).containsExactly("keep")
    }

    @Test
    fun `background autoplay drops what the live source hides`() =
        runTest {
            val source =
                FeedExclusionsSource {
                    FeedExclusions(suppressedVideoIds = setOf("not-interested"), blockedChannelIds = setOf("blocked"))
                }
            val candidates =
                listOf(video("keep"), video("not-interested"), video("from-blocked", channelId = "blocked"))

            val sanitized = PlayerRelatedVideosPolicy.sanitizeHidden("playing", candidates, source)

            assertThat(sanitized.map { it.id }).containsExactly("keep")
        }

    @Test
    fun `background autoplay without a source keeps every candidate`() =
        runTest {
            val candidates = listOf(video("a"), video("b", channelId = "blocked"))

            val sanitized = PlayerRelatedVideosPolicy.sanitizeHidden("playing", candidates, source = null)

            assertThat(sanitized).containsExactlyElementsIn(candidates).inOrder()
        }

    @Test
    fun `watched suggestions are dropped`() {
        val kept = PlayerRelatedVideosPolicy.withoutWatched(listOf(video("a"), video("b"), video("c")), setOf("b"))

        assertThat(kept.map { it.id }).containsExactly("a", "c").inOrder()
    }

    @Test
    fun `an all-watched list is kept whole so autoplay still has a next video`() {
        val videos = listOf(video("a"), video("b"))

        assertThat(PlayerRelatedVideosPolicy.withoutWatched(videos, setOf("a", "b"))).isEqualTo(videos)
    }

    @Test
    fun `background autoplay skips what the watched source reports`() =
        runTest {
            val candidates = listOf(video("watched"), video("fresh"))

            val kept =
                PlayerRelatedVideosPolicy.sanitizeHidden(
                    videoId = "playing",
                    candidates = candidates,
                    source = null,
                    watched = { videos -> videos.filter { it.id == "watched" }.mapTo(HashSet()) { it.id } },
                )

            assertThat(kept.map { it.id }).containsExactly("fresh")
        }

    @Test
    fun `a watched source that hides nothing leaves the list alone`() =
        runTest {
            val videos = listOf(video("a"))

            assertThat(PlayerRelatedVideosPolicy.withoutWatched(videos, WatchedSuggestionsSource { emptySet() })).isEqualTo(videos)
            assertThat(PlayerRelatedVideosPolicy.withoutWatched(videos, null as WatchedSuggestionsSource?)).isEqualTo(videos)
        }

    @Test
    fun `finished Shorts leave the related shelf and an emptied shelf goes`() =
        runTest {
            val shelf = RelatedCollection.ShortsShelf(0, "Shorts", listOf(video("s1"), video("s2")))
            val solo = RelatedCollection.ShortsShelf(1, "More", listOf(video("s3")))
            val asked = mutableListOf<Video>()
            val source =
                WatchedSuggestionsSource { videos ->
                    asked += videos
                    setOf("s1", "s3")
                }

            val kept = PlayerRelatedVideosPolicy.withoutWatchedShorts(listOf(shelf, solo), source)

            assertThat(kept.map { (it as RelatedCollection.ShortsShelf).shorts.map { short -> short.id } }).containsExactly(listOf("s2"))
            assertThat(asked.all { it.isShort }).isTrue()
        }

    @Test
    fun `YouTube's pick heads the list and the rest keep their order`() {
        val videos = listOf(video("a"), video("b"), video("pick"), video("c"))

        val ordered = PlayerRelatedVideosPolicy.upNextFirst(videos, "pick")

        assertThat(ordered.map { it.id }).containsExactly("pick", "a", "b", "c").inOrder()
    }

    @Test
    fun `a pick the filters removed leaves the order as it was`() {
        val kept =
            PlayerRelatedVideosPolicy.sanitize(
                videoId = "playing",
                candidates = listOf(video("a"), video("pick", channelId = "blocked"), video("b")),
                exclusions = FeedExclusions(blockedChannelIds = setOf("blocked")),
            )

        assertThat(PlayerRelatedVideosPolicy.upNextFirst(kept, "pick").map { it.id }).containsExactly("a", "b").inOrder()
        assertThat(PlayerRelatedVideosPolicy.upNextFirst(kept, null)).isEqualTo(kept)
    }

    private fun video(
        id: String,
        channelId: String = "channel-id",
    ) = Video(
        id = id,
        title = id,
        channelName = "channel",
        channelId = channelId,
        thumbnailUrl = "thumbnail",
        duration = 60,
        viewCount = 1L,
        uploadDate = "today",
    )
}
