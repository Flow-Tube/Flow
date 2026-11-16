package io.github.aedev.flow.ui.screens.music

import io.github.aedev.flow.data.music.model.MusicPlaylist
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.recommendation.MusicSection
import io.github.aedev.flow.innertube.pages.HomePage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MusicUiStateDeduplicationTest {
    @Test
    fun `a chip page's shelves keep each song once`() {
        val page =
            MusicHomeChipPage(
                chip = HomePage.Chip("Energize", null, null),
                sections = listOf(MusicSection(title = "Quick picks", tracks = listOf(track("song"), track("song")))),
            )

        val result = MusicUiState(homeChipPage = page).withUniqueLazyContent().homeChipPage

        assertEquals(
            listOf("song"),
            result
                ?.sections
                ?.single()
                ?.tracks
                ?.map(MusicTrack::videoId),
        )
    }

    @Test
    fun `music state normalizes keyed tracks collections and nested sections`() {
        val state =
            MusicUiState(
                trendingSongs = listOf(track("song"), track("song"), track("")),
                featuredPlaylists = listOf(playlist("playlist"), playlist("playlist")),
                dynamicSections =
                    listOf(
                        MusicSection(
                            title = "Section",
                            tracks = listOf(track("nested"), track("nested")),
                        ),
                    ),
            )

        val result = state.withUniqueLazyContent()

        assertEquals(listOf("song"), result.trendingSongs.map(MusicTrack::videoId))
        assertEquals(listOf("playlist"), result.featuredPlaylists.map(MusicPlaylist::id))
        assertEquals(
            listOf("nested"),
            result.dynamicSections
                .single()
                .tracks
                .map(MusicTrack::videoId),
        )
    }

    @Test
    fun `already valid music state keeps its identity`() {
        val state = MusicUiState(trendingSongs = listOf(track("song")))

        assertSame(state, state.withUniqueLazyContent())
    }

    private fun track(id: String) =
        MusicTrack(
            videoId = id,
            title = id,
            artist = "Artist",
            thumbnailUrl = "thumbnail",
            duration = 60,
        )

    private fun playlist(id: String) =
        MusicPlaylist(
            id = id,
            title = id,
            thumbnailUrl = "thumbnail",
        )
}
