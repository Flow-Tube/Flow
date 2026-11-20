package io.github.aedev.flow.ui.screens.player.content

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Playlist
import io.github.aedev.flow.data.model.RelatedCollection
import org.junit.Test

class RelatedLaneSegmentsTest {
    @Test
    fun `no collections is one run of videos`() {
        assertThat(relatedLaneSegments(3, emptyList())).containsExactly(RelatedLaneSegment.Videos(0, 3))
        assertThat(relatedLaneSegments(0, emptyList())).isEmpty()
    }

    @Test
    fun `each collection cuts the videos where it sits`() {
        val mix = playlist("RD1", position = 2)
        val top = playlist("PL0", position = 0)

        assertThat(relatedLaneSegments(4, listOf(mix, top)))
            .containsExactly(
                RelatedLaneSegment.Collection(top),
                RelatedLaneSegment.Videos(0, 2),
                RelatedLaneSegment.Collection(mix),
                RelatedLaneSegment.Videos(2, 2),
            ).inOrder()
    }

    @Test
    fun `a position past the end lands after the last video`() {
        val late = playlist("PL9", position = 9)

        assertThat(relatedLaneSegments(2, listOf(late)))
            .containsExactly(RelatedLaneSegment.Videos(0, 2), RelatedLaneSegment.Collection(late))
            .inOrder()
    }

    private fun playlist(
        id: String,
        position: Int,
    ) = RelatedCollection.PlaylistCard(position, Playlist(id = id, name = id, thumbnailUrl = "", videoCount = 0, isLocal = false))
}
