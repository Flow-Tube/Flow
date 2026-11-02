/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/Flow-Tube/Flow).
 */

package io.github.aedev.flow.ui.screens.home

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.recommendation.FeedExclusions
import io.github.aedev.flow.data.recommendation.GraphSeedInput
import io.github.aedev.flow.data.recommendation.GraphSeedSource
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Coverage for the feed assembly pipeline that used to be inline in `loadFlowFeed`. */
class HomeFeedAssemblyTest {
    private val now = 1_000_000_000L
    private val hour = 60L * 60L * 1000L

    private fun video(
        id: String,
        channelId: String = "c-$id",
        ageMs: Long = 10 * 24 * hour,
        duration: Int = 600,
        isShort: Boolean = false,
        isLive: Boolean = false,
        isUpcoming: Boolean = false,
        uploadDate: String = "10 days ago",
        avatar: String = "",
    ) = Video(
        id = id,
        title = "title-$id",
        channelName = channelId,
        channelId = channelId,
        thumbnailUrl = "",
        duration = duration,
        viewCount = 100,
        uploadDate = uploadDate,
        timestamp = now - ageMs,
        isShort = isShort,
        isLive = isLive,
        isUpcoming = isUpcoming,
        channelThumbnailUrl = avatar,
    )

    private val taste = FeedTasteProfile(DURATION_COMFORT_DEFAULT_SEC, emptySet())

    private suspend fun lanes(
        subs: List<Video> = emptyList(),
        discovery: List<Video> = emptyList(),
        memory: List<Video> = emptyList(),
        related: List<GraphCandidate> = emptyList(),
        rss: List<Video> = emptyList(),
        watched: Set<String> = emptySet(),
        exclusions: FeedExclusions = FeedExclusions.NONE,
        shown: Set<String> = emptySet(),
        sightings: Map<String, Int> = emptyMap(),
        freshSlots: Int = 3,
        avatars: Map<String, String> = emptyMap(),
        rankRelevant: suspend (List<Video>) -> List<Video> = { it },
    ) = buildHomeFeedLanes(
        rawSubs = subs,
        rawDiscovery = discovery,
        rawMemory = memory,
        rawRelated = related,
        rssFeed = rss,
        watched = watched,
        exclusions = exclusions,
        isRecentlyShown = { it in shown },
        timesShown = { sightings[it] ?: 0 },
        taste = taste,
        now = now,
        freshSlotTarget = freshSlots,
        subAvatarMap = avatars,
        rank = { it },
        rankRelevant = rankRelevant,
    )

    @Test
    fun `shorts and zero-duration items never reach the video lanes`() =
        runTest {
            val result =
                lanes(
                    subs = listOf(video("keep"), video("short", isShort = true), video("empty", duration = 0)),
                )

            assertThat(result.subsPoolSize).isEqualTo(1)
            assertThat(result.bestSubs.map { it.id }).containsExactly("keep")
        }

    @Test
    fun `a live item survives the zero-duration filter`() =
        runTest {
            val result = lanes(subs = listOf(video("live", duration = 0, isLive = true)))

            assertThat(result.bestSubs.map { it.id }).containsExactly("live")
        }

    @Test
    fun `excluded channels cannot resurface through the fresh-subs lane`() =
        runTest {
            val blocked = video("blocked", channelId = "bad", ageMs = hour, uploadDate = "1 hour ago")
            val allowed = video("allowed", channelId = "good", ageMs = hour, uploadDate = "1 hour ago")

            val result = lanes(subs = listOf(blocked, allowed), exclusions = FeedExclusions(blockedChannelIds = setOf("bad")))

            assertThat((result.pinnedFresh + result.overflowFresh).map { it.id }).containsExactly("allowed")
        }

    @Test
    fun `a not interested video leaves the fresh lane and the subs backlog`() =
        runTest {
            val hidden = video("hidden", channelId = "sub", ageMs = hour, uploadDate = "1 hour ago")
            val kept = video("kept", channelId = "sub2", ageMs = hour, uploadDate = "1 hour ago")

            val result =
                lanes(
                    subs = listOf(hidden, kept),
                    rss = listOf(hidden),
                    exclusions = FeedExclusions(suppressedVideoIds = setOf("hidden")),
                )
            val mix = assembleHomeFeed(result, onScreenIds = emptySet(), subCount = 2, totalInteractions = 100)

            assertThat((result.pinnedFresh + result.overflowFresh).map { it.id }).containsExactly("kept")
            assertThat(result.subsByRecency.map { it.id }).doesNotContain("hidden")
            assertThat(mix.subsBacklog.map { it.id }).doesNotContain("hidden")
        }

    @Test
    fun `the next unseen upload is pinned when the newest ones were all seen`() =
        runTest {
            val seen = (1..3).map { video("seen$it", channelId = "s$it", ageMs = it * hour, uploadDate = "$it hours ago") }
            val unseen = video("unseen", channelId = "u", ageMs = 10 * hour, uploadDate = "10 hours ago")

            val result = lanes(rss = seen + unseen, shown = seen.mapTo(HashSet()) { it.id }, freshSlots = 3)

            assertThat(result.pinnedFresh.map { it.id }).containsExactly("unseen")
            assertThat(result.shownFresh.map { it.id }).containsExactly("seen1", "seen2", "seen3").inOrder()
        }

    @Test
    fun `hidden channels are dropped from discovery and related lanes too`() =
        runTest {
            val result =
                lanes(
                    discovery = listOf(video("d-sub", channelId = "UCsub"), video("d-other")),
                    related = listOf(GraphCandidate(video("r-sub", channelId = "UCsub"), "seed", 1.0, 0, "misc", 1)),
                    exclusions = FeedExclusions().hidingChannels(setOf("UCsub")),
                )

            assertThat(result.bestDiscovery.map { it.id }).containsExactly("d-other")
            assertThat(result.bestRelated).isEmpty()
        }

    @Test
    fun `a blocked topic is dropped from the subscription lanes`() =
        runTest {
            val result =
                lanes(
                    subs = listOf(video("topic"), video("other")),
                    exclusions = FeedExclusions(blockedText = { title, _ -> title == "title-topic" }),
                )

            assertThat(result.bestSubs.map { it.id }).containsExactly("other")
        }

    @Test
    fun `an upload shown recently leaves the pinned slots but stays on Home behind the other subs`() =
        runTest {
            val seen = video("seen", channelId = "a", ageMs = hour, uploadDate = "1 hour ago")
            val fresh = video("fresh", channelId = "b", ageMs = 2 * hour, uploadDate = "2 hours ago")

            val result = lanes(rss = listOf(seen, fresh), shown = setOf("seen"))
            val mix = assembleHomeFeed(result, onScreenIds = emptySet(), subCount = 2, totalInteractions = 100)

            assertThat(result.pinnedFresh.map { it.id }).containsExactly("fresh")
            assertThat(result.shownFresh.map { it.id }).containsExactly("seen")
            assertThat(mix.videos.map { it.id }).containsExactly("fresh", "seen").inOrder()
        }

    @Test
    fun `a fresh upload passed three times leaves the fresh lane`() =
        runTest {
            val wornOut = video("worn", channelId = "a", ageMs = hour, uploadDate = "1 hour ago")
            val seenTwice = video("twice", channelId = "b", ageMs = 2 * hour, uploadDate = "2 hours ago")

            val result =
                lanes(
                    rss = listOf(wornOut, seenTwice),
                    shown = setOf("worn", "twice"),
                    sightings = mapOf("worn" to FRESH_UPLOAD_MAX_SIGHTINGS, "twice" to 2),
                )

            assertThat(result.shownFresh.map { it.id }).containsExactly("twice")
            assertThat(result.pinnedFresh).isEmpty()
        }

    @Test
    fun `rss uploads join the fresh lane and upcoming ones do not`() =
        runTest {
            val result =
                lanes(
                    rss =
                        listOf(
                            video("rss-fresh", channelId = "r1", ageMs = 2 * hour),
                            video("rss-premiere", channelId = "r2", ageMs = 2 * hour, isUpcoming = true),
                        ),
                )

            assertThat((result.pinnedFresh + result.overflowFresh).map { it.id })
                .containsExactly("rss-fresh")
        }

    @Test
    fun `one fresh slot per channel even when a channel uploaded repeatedly`() =
        runTest {
            val result =
                lanes(
                    rss =
                        listOf(
                            video("a1", channelId = "same", ageMs = 1 * hour),
                            video("a2", channelId = "same", ageMs = 2 * hour),
                            video("a3", channelId = "same", ageMs = 3 * hour),
                            video("b1", channelId = "other", ageMs = 4 * hour),
                        ),
                )

            val fresh = (result.pinnedFresh + result.overflowFresh).map { it.id }
            assertThat(fresh).containsExactly("a1", "b1").inOrder()
        }

    @Test
    fun `fresh lane is capped at the dynamic slot target`() =
        runTest {
            val uploads = (1..6).map { video("f$it", channelId = "ch$it", ageMs = it * hour) }

            val result = lanes(rss = uploads, freshSlots = 2)

            assertThat(result.pinnedFresh + result.overflowFresh).hasSize(2)
        }

    @Test
    fun `only the first two fresh items are pinned and the rest overflow`() =
        runTest {
            val uploads = (1..4).map { video("f$it", channelId = "ch$it", ageMs = it * hour) }

            val result = lanes(rss = uploads, freshSlots = 4)

            assertThat(result.pinnedFresh.map { it.id }).containsExactly("f1", "f2").inOrder()
            assertThat(result.overflowFresh.map { it.id }).containsExactly("f3", "f4").inOrder()
        }

    @Test
    fun `a video already in the fresh lane is not repeated in the subs lane`() =
        runTest {
            val fresh = video("dup", channelId = "c1", ageMs = hour, uploadDate = "1 hour ago")

            val result = lanes(subs = listOf(fresh, video("older", channelId = "c2")))

            assertThat((result.pinnedFresh + result.overflowFresh).map { it.id }).contains("dup")
            assertThat(result.bestSubs.map { it.id }).doesNotContain("dup")
        }

    @Test
    fun `subscription avatars backfill only videos missing one`() =
        runTest {
            val result =
                lanes(
                    subs =
                        listOf(
                            video("bare", channelId = "c1"),
                            video("has-avatar", channelId = "c2", avatar = "own.jpg"),
                        ),
                    avatars = mapOf("c1" to "sub.jpg", "c2" to "sub2.jpg"),
                )

            val byId = result.bestSubs.associateBy { it.id }
            assertThat(byId.getValue("bare").channelThumbnailUrl).isEqualTo("sub.jpg")
            assertThat(byId.getValue("has-avatar").channelThumbnailUrl).isEqualTo("own.jpg")
        }

    @Test
    fun `watched videos are dropped from every lane`() =
        runTest {
            val result =
                lanes(
                    subs = listOf(video("s1"), video("s2")),
                    discovery = listOf(video("d1")),
                    memory = listOf(video("v1")),
                    watched = setOf("s1", "d1", "v1"),
                )

            assertThat(result.bestSubs.map { it.id }).containsExactly("s2")
            assertThat(result.bestDiscovery).isEmpty()
            assertThat(result.bestMemory).isEmpty()
        }

    @Test
    fun `pinned fresh items lead the assembled feed`() =
        runTest {
            val built =
                lanes(
                    rss = listOf(video("fresh", channelId = "f", ageMs = hour)),
                    discovery = (1..5).map { video("d$it", channelId = "d$it") },
                )

            val mix = assembleHomeFeed(built, onScreenIds = emptySet(), subCount = 10, totalInteractions = 0)

            assertThat(mix.videos.first().id).isEqualTo("fresh")
            assertThat(mix.freshAdded).isEqualTo(1)
        }

    @Test
    fun `fresh lane additions are credited to the subs source count`() =
        runTest {
            val built = lanes(rss = listOf(video("fresh", channelId = "f", ageMs = hour)))

            val mix = assembleHomeFeed(built, onScreenIds = emptySet(), subCount = 10, totalInteractions = 0)

            assertThat(mix.selectedSourceCounts[FeedSource.SUBS]).isEqualTo(1)
        }

    @Test
    fun `a deep feed excludes what is already on screen so a refresh looks new`() =
        runTest {
            // Needs more than half a page of unseen candidates, and the per-lane caps mean one
            // lane alone can never supply that.
            val built =
                lanes(
                    subs = (1..20).map { video("s$it", channelId = "sc$it") },
                    discovery = (1..20).map { video("d$it", channelId = "dc$it") },
                )
            val onScreen = setOf("d1", "d2", "d3")

            val mix = assembleHomeFeed(built, onScreenIds = onScreen, subCount = 20, totalInteractions = 0)

            assertThat(mix.videos.map { it.id }).containsNoneIn(onScreen)
        }

    @Test
    fun `a thin feed reuses on-screen items rather than rendering half a page`() =
        runTest {
            val built = lanes(discovery = (1..3).map { video("d$it", channelId = "ch$it") })
            val onScreen = setOf("d1", "d2", "d3")

            val mix = assembleHomeFeed(built, onScreenIds = onScreen, subCount = 0, totalInteractions = 0)

            assertThat(mix.videos).isNotEmpty()
        }

    @Test
    fun `the assembled feed never exceeds the target size`() =
        runTest {
            val built =
                lanes(
                    subs = (1..30).map { video("s$it", channelId = "sc$it") },
                    discovery = (1..30).map { video("d$it", channelId = "dc$it") },
                    memory = (1..30).map { video("v$it", channelId = "vc$it") },
                )

            val mix = assembleHomeFeed(built, onScreenIds = emptySet(), subCount = 50, totalInteractions = 100)

            assertThat(mix.videos.size).isAtMost(HOME_TARGET_SIZE)
            assertThat(mix.videos.map { it.id }).containsNoDuplicates()
        }

    @Test
    fun `the subs backlog keeps what the mix did not use`() =
        runTest {
            val built = lanes(subs = (1..20).map { video("s$it", channelId = "sc$it") })

            val mix = assembleHomeFeed(built, onScreenIds = emptySet(), subCount = 20, totalInteractions = 0)

            val used = mix.videos.mapTo(mutableSetOf()) { it.id }
            assertThat(mix.subsBacklog.map { it.id }).containsNoneIn(used)
        }

    @Test
    fun `related metadata is keyed by video id for the graph boost`() =
        runTest {
            val candidate =
                GraphCandidate(
                    video = video("r1"),
                    seedId = "seed",
                    seedScore = 1.0,
                    graphRank = 0,
                    seedCluster = "misc",
                    seedResultCount = 3,
                )

            val result = lanes(related = listOf(candidate))

            assertThat(result.relatedMetadata.keys).containsExactly("r1")
            assertThat(result.bestRelated.map { it.id }).containsExactly("r1")
        }

    @Test
    fun `seen fresh uploads come after a few ranked subs instead of past the lane quota`() =
        runTest {
            val seen = video("seen", channelId = "s", ageMs = hour, uploadDate = "1 hour ago")
            val ranked = (1..20).map { video("sub$it", channelId = "c$it") }

            val result = lanes(subs = ranked, rss = listOf(seen), shown = setOf("seen"))
            val subsLane =
                assembleHomeFeed(result, onScreenIds = emptySet(), subCount = 20, totalInteractions = 100)
                    .sourceMix.items
                    .filter { it.source == FeedSource.SUBS }
                    .map { it.video.id }

            assertThat(subsLane.indexOf("seen")).isEqualTo(3)
        }

    @Test
    fun `only discovery and related candidates face the relevance floor`() =
        runTest {
            val result =
                lanes(
                    subs = listOf(video("sub")),
                    discovery = listOf(video("found")),
                    memory = listOf(video("remembered")),
                    related = listOf(GraphCandidate(video("neighbour"), "seed", 1.0, 0, "misc", 1)),
                    rankRelevant = { pool -> pool.filterNot { it.id in setOf("sub", "found", "remembered", "neighbour") } },
                )

            assertThat(result.bestSubs.map { it.id }).containsExactly("sub")
            assertThat(result.bestMemory.map { it.id }).containsExactly("remembered")
            assertThat(result.bestDiscovery).isEmpty()
            assertThat(result.bestRelated).isEmpty()
        }

    @Test
    fun `a related card names the video it was found from`() {
        val seeds =
            listOf(
                GraphSeedInput(
                    id = "seed",
                    title = "Wonderwall guitar playalong",
                    channelId = "",
                    source = GraphSeedSource.WATCH_HISTORY,
                    engagementWeight = 1.0,
                    timestamp = now,
                    durationSec = 240,
                    percentWatched = 100.0,
                ),
            )
        val candidates =
            listOf(
                GraphCandidate(video("shown"), "seed", 1.0, 0, "misc", 1),
                GraphCandidate(video("hidden"), "seed", 1.0, 1, "misc", 1),
                GraphCandidate(video("orphan"), "gone", 1.0, 2, "misc", 1),
            )

        assertThat(relatedSeedTitles(candidates, seeds, setOf("shown", "orphan")))
            .containsExactly("shown", "Wonderwall guitar playalong")
    }
}
