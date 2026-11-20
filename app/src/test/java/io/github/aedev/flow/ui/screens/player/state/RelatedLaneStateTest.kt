package io.github.aedev.flow.ui.screens.player.state

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.CollectionLabels
import io.github.aedev.flow.data.model.Playlist
import io.github.aedev.flow.data.model.RelatedCollection
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.recommendation.FeedExclusions
import org.junit.Test

class RelatedLaneStateTest {
    @Test
    fun `a collection stays behind the videos listed before it that are still shown`() {
        val listed = listOf(video("a"), video("hidden"), video("b"), video("pick"))
        val shown = listOf(video("pick"), video("a"), video("b"))

        val placed =
            RelatedLanePlacement.place(
                listed = listed,
                shown = shown,
                collections = listOf(playlist("PL", position = 3)),
                upNextVideoId = "pick",
            )

        assertThat(placed.single().position).isEqualTo(3)
    }

    @Test
    fun `the Shorts shelf follows the hide-Shorts switch and hidden videos`() {
        val shelf = RelatedCollection.ShortsShelf(0, "Shorts", listOf(video("s1", isShort = true), video("s2", isShort = true)))
        val mix = playlist("RD", position = 1)

        assertThat(RelatedLanePlacement.visible("v", listOf(shelf, mix), shortsEnabled = false, FeedExclusions.NONE))
            .containsExactly(mix)
        val kept = RelatedLanePlacement.visible("v", listOf(shelf), shortsEnabled = true, FeedExclusions(suppressedVideoIds = setOf("s1")))
        assertThat((kept.single() as RelatedCollection.ShortsShelf).shorts.map { it.id }).containsExactly("s2")
    }

    @Test
    fun `a playlist or Mix whose name or artists hit a blocked word is dropped`() {
        val mix =
            RelatedCollection.PlaylistCard(
                0,
                Playlist(id = "RD", name = "Mix - Rick", thumbnailUrl = "", videoCount = 0, isLocal = false),
                CollectionLabels(byline = "Toto, Cyndi Lauper"),
            )
        val other = playlist("PL", position = 1)
        val blocked = FeedExclusions(blockedText = { title, byline -> "Lauper" in title || "Lauper" in byline })

        assertThat(RelatedLanePlacement.visible("v", listOf(mix, other), shortsEnabled = true, blocked)).containsExactly(other)
    }

    @Test
    fun `a later page lands behind the lane with its collections offset and repeats dropped`() {
        val state =
            VideoPlayerUiState(
                cachedVideo = video("v"),
                relatedVideos = listOf(video("a"), video("b")),
                relatedCollections = listOf(playlist("PL", position = 1)),
                relatedContinuation = "t1",
                relatedAppend = RelatedAppend.LOADING,
            )
        val listed = listOf(video("b"), video("c"), video("d"))

        val next =
            state.appendRelatedPage(
                listed = listed,
                kept = listed,
                collections = listOf(playlist("PL", position = 0), playlist("RD", position = 2)),
                continuation = "t2",
            )

        assertThat(next.relatedVideos.map { it.id }).containsExactly("a", "b", "c", "d").inOrder()
        assertThat(next.relatedCollections.map { it.key to it.position })
            .containsExactly("related_playlist:PL" to 1, "related_playlist:RD" to 3)
            .inOrder()
        assertThat(next.relatedContinuation).isEqualTo("t2")
        assertThat(next.relatedAppend).isEqualTo(RelatedAppend.IDLE)
    }

    private fun playlist(
        id: String,
        position: Int,
    ) = RelatedCollection.PlaylistCard(position, Playlist(id = id, name = id, thumbnailUrl = "", videoCount = 0, isLocal = false))

    private fun video(
        id: String,
        isShort: Boolean = false,
    ) = Video(
        id = id,
        title = id,
        channelName = "Channel",
        channelId = "channel",
        thumbnailUrl = "",
        duration = 60,
        viewCount = 1L,
        uploadDate = "",
        isShort = isShort,
    )
}
