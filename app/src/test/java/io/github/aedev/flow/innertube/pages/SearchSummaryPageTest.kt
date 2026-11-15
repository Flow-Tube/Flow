package io.github.aedev.flow.innertube.pages

import io.github.aedev.flow.innertube.models.Artist
import io.github.aedev.flow.innertube.models.ArtistItem
import io.github.aedev.flow.innertube.models.SongItem
import io.github.aedev.flow.innertube.pages.InnerTubeJson.ALBUM
import io.github.aedev.flow.innertube.pages.InnerTubeJson.ARTIST
import io.github.aedev.flow.innertube.pages.InnerTubeJson.ATV
import io.github.aedev.flow.innertube.pages.InnerTubeJson.OMV
import io.github.aedev.flow.innertube.pages.InnerTubeJson.PLAYLIST
import io.github.aedev.flow.innertube.pages.InnerTubeJson.browseEndpoint
import io.github.aedev.flow.innertube.pages.InnerTubeJson.browseRow
import io.github.aedev.flow.innertube.pages.InnerTubeJson.itemSection
import io.github.aedev.flow.innertube.pages.InnerTubeJson.musicShelf
import io.github.aedev.flow.innertube.pages.InnerTubeJson.run
import io.github.aedev.flow.innertube.pages.InnerTubeJson.separator
import io.github.aedev.flow.innertube.pages.InnerTubeJson.songRow
import io.github.aedev.flow.innertube.pages.InnerTubeJson.topResultArtistCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSummaryPageTest {
    private val acdc = "UCVm4YdI3hobkwsHTTOMVJKg"
    private val byAcdc = run("AC/DC", browseEndpoint(acdc, ARTIST))

    private fun song(
        id: String,
        type: String = ATV,
    ) = songRow(id, "Song $id", type, listOf(run("Song"), separator(), byAcdc))

    private fun album(id: String) = browseRow(id, ALBUM, "Album $id", listOf(run("Album"), separator(), byAcdc, separator(), run("1980")))

    private fun artist(id: String) = browseRow(id, ARTIST, "Artist $id", listOf(run("Artist"), separator(), run("40M monthly audience")))

    private fun playlist(id: String) =
        browseRow(
            "VL$id",
            PLAYLIST,
            "Playlist $id",
            listOf(run("Playlist"), separator(), run("YouTube Music"), separator(), run("76 songs")),
        )

    @Test
    fun `item sections are grouped by kind in the order each kind first appears`() {
        val page =
            SearchSummaryPage.fromSearchResponse(
                InnerTubeJson.search(
                    listOf(
                        topResultArtistCard("Top result", acdc, "AC/DC"),
                        itemSection(song("s1")),
                        itemSection(artist("UCa1")),
                        itemSection(song("v1", OMV)),
                        itemSection(song("s2")),
                        itemSection(album("MPREb_a1")),
                        itemSection(playlist("PL1")),
                        itemSection(artist("UCa2")),
                    ),
                ),
            )

        assertEquals(
            listOf(
                SearchSummaryKind.TOP_RESULT,
                SearchSummaryKind.SONGS,
                SearchSummaryKind.ARTISTS,
                SearchSummaryKind.VIDEOS,
                SearchSummaryKind.ALBUMS,
                SearchSummaryKind.PLAYLISTS,
            ),
            page.summaries.map { it.kind },
        )
        assertEquals(listOf("s1", "s2"), page.summaries[1].items.map { it.id })
        assertEquals(listOf("UCa1", "UCa2"), page.summaries[2].items.map { it.id })
        assertEquals(listOf("v1"), page.summaries[3].items.map { it.id })
        assertEquals(listOf("MPREb_a1"), page.summaries[4].items.map { it.id })
        assertEquals(listOf("PL1"), page.summaries[5].items.map { it.id })
        assertNull(page.continuation)
    }

    @Test
    fun `songs under an artist's top result card are credited to that artist, not to their length`() {
        val cardSong = songRow("c1", "Card song", ATV, listOf(run("Song"), separator(), run("4:38")))
        val page =
            SearchSummaryPage.fromSearchResponse(
                InnerTubeJson.search(listOf(topResultArtistCard("Top result", acdc, "AC/DC", contents = listOf(cardSong, song("s1"))))),
            )

        val songs =
            page.summaries
                .single()
                .items
                .filterIsInstance<SongItem>()
        assertEquals(listOf(Artist("AC/DC", acdc)), songs.single { it.id == "c1" }.artists)
        assertEquals(listOf(Artist("AC/DC", acdc)), songs.single { it.id == "s1" }.artists)
    }

    @Test
    fun `the top result card keeps its own section`() {
        val page = SearchSummaryPage.fromSearchResponse(InnerTubeJson.search(listOf(topResultArtistCard("Mejor resultado", acdc, "AC/DC"))))

        val top = page.summaries.single()
        assertEquals(SearchSummaryKind.TOP_RESULT, top.kind)
        assertEquals("Mejor resultado", top.title)
        assertTrue(top.items.single() is ArtistItem)
    }

    @Test
    fun `titled shelves keep the server title and the last shelf continuation`() {
        val page =
            SearchSummaryPage.fromSearchResponse(
                InnerTubeJson.search(
                    listOf(
                        musicShelf("Songs", listOf(song("s1"), song("s2"))),
                        musicShelf("Albums", listOf(album("MPREb_a1")), continuation = "next"),
                    ),
                ),
            )

        assertEquals(listOf("Songs", "Albums"), page.summaries.map { it.title })
        assertTrue(page.summaries.all { it.kind == SearchSummaryKind.SHELF })
        assertEquals("next", page.continuation)
    }

    @Test
    fun `an empty or unknown response is an empty page rather than a crash`() {
        val empty = SearchSummaryPage.fromSearchResponse(InnerTubeJson.search(emptyList()))
        val unparseable = SearchSummaryPage.fromSearchResponse(InnerTubeJson.search(listOf(itemSection("""{"messageRenderer":{}}"""))))

        assertTrue(empty.summaries.isEmpty())
        assertTrue(unparseable.summaries.isEmpty())
    }

    @Test
    fun `a repeated row is listed once`() {
        val page = SearchSummaryPage.fromSearchResponse(InnerTubeJson.search(listOf(itemSection(song("s1")), itemSection(song("s1")))))

        assertEquals(
            listOf("s1"),
            page.summaries
                .single()
                .items
                .map { it.id },
        )
    }
}
