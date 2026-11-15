package io.github.aedev.flow.ui.screens.music

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.contentfilter.aislist.AiListMatcher
import io.github.aedev.flow.data.contentfilter.aislist.AiListSets
import io.github.aedev.flow.innertube.models.AlbumItem
import io.github.aedev.flow.innertube.models.Artist
import io.github.aedev.flow.innertube.models.ArtistItem
import io.github.aedev.flow.innertube.models.PlaylistItem
import io.github.aedev.flow.innertube.models.SongItem
import io.github.aedev.flow.innertube.pages.SearchSummary
import io.github.aedev.flow.innertube.pages.SearchSummaryKind
import io.github.aedev.flow.innertube.pages.SearchSummaryPage
import org.junit.Test

class MusicSearchAiFilterTest {
    private val aiId = "UCaiaiaiaiaiaiaiaiaiaiai"
    private val realId = "UCrealrealrealrealrealre"

    private val ai =
        AiListMatcher(
            blockEnabled = false,
            warnEnabled = false,
            block = AiListSets.EMPTY,
            warn = AiListSets.EMPTY,
            exemptChannelIds = emptySet(),
            exemptHandles = emptySet(),
            index = null,
            musicEnabled = true,
            music = AiListSets(handles = emptySet(), ids = setOf(aiId)),
        )

    private fun song(
        id: String,
        vararg artistIds: String,
    ) = SongItem(id = id, title = id, artists = artistIds.map { Artist(name = it, id = it) }, thumbnail = "")

    private val aiAlbum =
        AlbumItem(
            browseId = "MPREai",
            playlistId = "OLAKai",
            title = "a",
            artists = listOf(Artist("Bot", aiId)),
            thumbnail = "",
        )
    private val aiPlaylist =
        PlaylistItem(
            id = "PLai",
            title = "p",
            author = Artist("Bot", aiId),
            songCountText = null,
            thumbnail = null,
            playEndpoint = null,
            shuffleEndpoint = null,
            radioEndpoint = null,
        )
    private val aiArtistCard = ArtistItem(id = aiId, title = "Bot", thumbnail = null, shuffleEndpoint = null, radioEndpoint = null)

    @Test
    fun `songs, collaborations, albums and playlists by a listed artist go, artist cards stay`() {
        val items = listOf(song("a", aiId), song("b", realId, aiId), song("c", realId), aiAlbum, aiPlaylist, aiArtistCard)

        assertThat(items.withoutAiMusic(ai).map { it.id }).containsExactly("c", aiId).inOrder()
    }

    @Test
    fun `a summary section left empty is dropped and an untouched one is kept as is`() {
        val clean = SearchSummary(title = "Artists", items = listOf(aiArtistCard), kind = SearchSummaryKind.ARTISTS)
        val state =
            MusicSearchUiState(
                searchSummary =
                    SearchSummaryPage(
                        summaries =
                            listOf(
                                SearchSummary(title = "Songs", items = listOf(song("a", aiId)), kind = SearchSummaryKind.SONGS),
                                clean,
                            ),
                    ),
                filteredResults = listOf(song("a", aiId), song("c", realId)),
            )

        val result = state.withoutAiMusic(ai)

        assertThat(result.searchSummary!!.summaries).containsExactly(clean)
        assertThat(result.filteredResults.map { it.id }).containsExactly("c")
        assertThat(state.withoutAiMusic(AiListMatcher.NONE)).isSameInstanceAs(state)
    }
}
