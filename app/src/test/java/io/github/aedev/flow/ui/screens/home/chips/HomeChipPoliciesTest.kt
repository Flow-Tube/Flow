package io.github.aedev.flow.ui.screens.home.chips

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.VideoHistoryEntry
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.recommendation.InterestChip
import org.junit.Test

class HomeChipPoliciesTest {
    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    private fun video(
        id: String,
        channel: String = "c-$id",
        ageDays: Long = 1,
        isShort: Boolean = false,
        isLive: Boolean = false,
    ) = Video(
        id = id,
        title = id,
        channelName = channel,
        channelId = channel,
        thumbnailUrl = "",
        duration = 600,
        viewCount = 10,
        uploadDate = "$ageDays days ago",
        timestamp = now - ageDays * day,
        isShort = isShort,
        isLive = isLive,
    )

    private val claude = InterestChip("claude", "Claude", listOf("claude", "claude 5.5", "opus", "design"))

    @Test
    fun `chips keep a fixed order and hide what has nothing to show`() {
        val chips = visibleChips(listOf(claude), emptyKeys = setOf("live"), hasMixSeeds = false, hasWatched = true, selected = "all")

        assertThat(chips.map { it.key }).containsExactly("all", "interest:claude", "new", "recent", "watched").inOrder()
    }

    @Test
    fun `the selected chip stays visible even when it came back empty`() {
        val chips = visibleChips(emptyList(), emptyKeys = setOf("live"), hasMixSeeds = false, hasWatched = false, selected = "live")

        assertThat(chips.map { it.key }).contains("live")
    }

    @Test
    fun `live streams are searched for the top two interests`() {
        val gym = InterestChip("gym", "Gym", listOf("gym"))
        val chess = InterestChip("chess", "Chess", listOf("chess"))

        assertThat(liveQueries(listOf(claude, gym, chess))).containsExactly("claude 5.5", "gym").inOrder()
    }

    @Test
    fun `a viewer with no interests still gets live streams`() {
        assertThat(liveQueries(emptyList())).containsExactly(HomeChipParams.LIVE_FALLBACK_QUERY)
    }

    @Test
    fun `interest searches lead with the chip's phrases`() {
        assertThat(interestQueries(claude)).containsExactly("claude 5.5", "claude opus", "claude design").inOrder()
        assertThat(interestQueries(InterestChip("gym", "Gym", listOf("gym")))).containsExactly("gym")
    }

    @Test
    fun `a theme searches each of its interests in turn`() {
        val theme =
            InterestChip(
                "theme:gaming",
                "Minecraft",
                emptyList(),
                category = "gaming",
                parts =
                    listOf(
                        InterestChip("minecraft", "Minecraft", listOf("minecraft", "minecraft redstone")),
                        InterestChip("fortnite", "Fortnite", listOf("fortnite")),
                    ),
            )

        assertThat(interestQueries(theme)).containsExactly("minecraft redstone", "fortnite").inOrder()
    }

    @Test
    fun `new to you keeps one video per channel the viewer never met`() {
        val videos = listOf(video("a", "known"), video("b", "fresh"), video("c", "fresh"), video("d", "other"))

        assertThat(fromUnknownChannels(videos, setOf("known")).map { it.id }).containsExactly("b", "d")
    }

    @Test
    fun `recently uploaded keeps long-form videos from the last week only`() {
        val videos = listOf(video("new"), video("old", ageDays = 9), video("reel", isShort = true), video("live", isLive = true))

        assertThat(uploadedThisWeek(videos, now).map { it.id }).containsExactly("new")
    }

    @Test
    fun `freshness nudges a newer upload up without overriding taste`() {
        val ranked = listOf(video("a", ageDays = 6), video("b", ageDays = 0), video("c", ageDays = 6), video("d", ageDays = 6))

        assertThat(freshnessOrder(ranked, now).map { it.id }).containsExactly("a", "b", "c", "d").inOrder()
        assertThat(freshnessOrder(ranked.reversed(), now).first().id).isEqualTo("d")
    }

    @Test
    fun `mix seeds take one per cluster and never a rejected video or channel`() {
        fun seed(
            id: String,
            cluster: String,
            longTerm: Boolean,
            channel: String = "c-$id",
        ) = MixSeedCandidate(video(id, channel), cluster, strength = 1.0, at = now - (if (longTerm) 60 else 1) * day, longTerm = longTerm)

        val seeds =
            mixSeeds(
                listOf(
                    seed("r1", "gym", longTerm = false),
                    seed("r2", "gym", longTerm = false),
                    seed("l1", "claude", longTerm = true),
                    seed("disliked", "anime", longTerm = false),
                    seed("blocked", "cars", longTerm = true, channel = "bad"),
                    seed("l2", "android", longTerm = true),
                ),
                excludedVideos = setOf("disliked"),
                excludedChannels = setOf("bad"),
            )

        assertThat(seeds.map { it.video.id }).containsExactly("r1", "l1", "l2").inOrder()
    }

    private fun entry(
        id: String,
        ageDays: Double,
        percent: Int = 100,
        isShort: Boolean = false,
    ) = VideoHistoryEntry(
        videoId = id,
        position = 600_000L * percent / 100,
        duration = 600_000L,
        timestamp = now - (ageDays * day).toLong(),
        title = id,
        thumbnailUrl = "",
        isShort = isShort,
    )

    @Test
    fun `watch again ranks rewatched finished favourites and skips the last day and dislikes`() {
        val history =
            listOf(
                entry("yesterday-ish", ageDays = 0.5),
                entry("rewatched", ageDays = 20.0),
                entry("half", ageDays = 20.0, percent = 40),
                entry("disliked", ageDays = 20.0),
                entry("reel", ageDays = 20.0, isShort = true),
            )

        val ranked = watchAgain(history, rewatches = mapOf("rewatched" to 4), taste = emptyMap(), disliked = setOf("disliked"), now = now)

        assertThat(ranked.map { it.videoId }).containsExactly("rewatched", "half").inOrder()
    }

    @Test
    fun `a chip result is reused until its time to live runs out`() {
        val cache = ChipResultCache()
        val feed = ChipFeed.Videos(listOf(video("a")))
        cache.put(HomeChip.Live, feed, now)

        assertThat(cache.get(HomeChip.Live, now + HomeChip.Live.ttlMs - 1)).isEqualTo(feed)
        assertThat(cache.get(HomeChip.Live, now + HomeChip.Live.ttlMs)).isNull()
    }

    @Test
    fun `filling in a card keeps the result's original expiry`() {
        val cache = ChipResultCache()
        cache.put(HomeChip.Watched, ChipFeed.Videos(listOf(video("a"))), now)
        val filled = ChipFeed.Videos(listOf(video("a").copy(viewCount = 99)))

        cache.replace(HomeChip.Watched, filled)

        assertThat(cache.get(HomeChip.Watched, now + 1)).isEqualTo(filled)
        assertThat(cache.get(HomeChip.Watched, now + HomeChip.Watched.ttlMs)).isNull()
    }

    @Test
    fun `an empty result hides its chip until it expires`() {
        val cache = ChipResultCache()
        cache.put(HomeChip.RecentlyUploaded, ChipFeed.Empty, now)

        assertThat(cache.emptyKeys(listOf(HomeChip.RecentlyUploaded, HomeChip.Live), now)).containsExactly("recent")
        assertThat(cache.emptyKeys(listOf(HomeChip.RecentlyUploaded), now + HomeChip.HOUR)).isEmpty()
    }
}
