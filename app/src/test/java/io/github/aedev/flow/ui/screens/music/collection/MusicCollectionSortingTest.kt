package io.github.aedev.flow.ui.screens.music.collection

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.music.model.MusicTrack
import org.junit.Test

class MusicCollectionSortingTest {
    private fun track(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String = "",
    ) = MusicTrack(videoId = id, title = title, artist = artist, thumbnailUrl = "", duration = duration, album = album)

    private val tracks =
        listOf(
            track("a", "perth", "Bon Iver", 262),
            track("b", "Holocene", "Bon Iver", 0),
            track("c", "Beth/Rest", "Adele", 315, album = "25"),
        )
    private val added = mapOf("a" to 30L, "b" to 10L, "c" to 20L)

    @Test
    fun `the collection order is left alone`() {
        assertThat(tracks.sortedForCollection(MusicSortOrder.COLLECTION, added)).isEqualTo(tracks)
    }

    @Test
    fun `title and artist ignore case`() {
        assertThat(tracks.sortedForCollection(MusicSortOrder.TITLE, added).map { it.videoId }).containsExactly("c", "b", "a").inOrder()
        assertThat(tracks.sortedForCollection(MusicSortOrder.ARTIST, added).map { it.videoId }).containsExactly("c", "a", "b").inOrder()
    }

    @Test
    fun `songs of unknown length go last when shortest first`() {
        assertThat(tracks.sortedForCollection(MusicSortOrder.SHORTEST, added).map { it.videoId }).containsExactly("a", "c", "b").inOrder()
        assertThat(tracks.sortedForCollection(MusicSortOrder.LONGEST, added).first().videoId).isEqualTo("c")
    }

    @Test
    fun `date added orders use when each song was added`() {
        assertThat(
            tracks.sortedForCollection(MusicSortOrder.NEWEST_ADDED, added).map { it.videoId },
        ).containsExactly("a", "c", "b").inOrder()
        assertThat(
            tracks.sortedForCollection(MusicSortOrder.OLDEST_ADDED, added).map { it.videoId },
        ).containsExactly("b", "c", "a").inOrder()
    }

    @Test
    fun `songs added together come out reversed when newest first`() {
        val imported = tracks + track("d", "Re: Stacks", "Bon Iver", 401)
        val sameImport = mapOf("a" to 10L, "b" to 10L, "c" to 10L, "d" to 20L)

        assertThat(
            imported.sortedForCollection(MusicSortOrder.NEWEST_ADDED, sameImport).map { it.videoId },
        ).containsExactly("d", "c", "b", "a").inOrder()
        assertThat(
            imported.sortedForCollection(MusicSortOrder.OLDEST_ADDED, sameImport).map { it.videoId },
        ).containsExactly("a", "b", "c", "d").inOrder()
    }

    @Test
    fun `songs without a date go last in both date orders`() {
        val partly = mapOf("b" to 10L)

        assertThat(tracks.sortedForCollection(MusicSortOrder.NEWEST_ADDED, partly).first().videoId).isEqualTo("b")
        assertThat(tracks.sortedForCollection(MusicSortOrder.OLDEST_ADDED, partly).first().videoId).isEqualTo("b")
    }

    @Test
    fun `songs without an album sort after those with one`() {
        assertThat(tracks.sortedForCollection(MusicSortOrder.ALBUM, added).first().videoId).isEqualTo("c")
    }

    @Test
    fun `each kind offers only the orders it has data for`() {
        val album = MusicSortOrder.availableFor(MusicCollectionKind.ALBUM, hasAddedDates = false, hasAlbums = true)
        assertThat(album).containsExactly(MusicSortOrder.COLLECTION, MusicSortOrder.TITLE, MusicSortOrder.LONGEST, MusicSortOrder.SHORTEST)

        val remote = MusicSortOrder.availableFor(MusicCollectionKind.PLAYLIST, hasAddedDates = false, hasAlbums = false)
        assertThat(remote).doesNotContain(MusicSortOrder.NEWEST_ADDED)
        assertThat(remote).doesNotContain(MusicSortOrder.ALBUM)

        val liked = MusicSortOrder.availableFor(MusicCollectionKind.LIKED, hasAddedDates = true, hasAlbums = false)
        assertThat(liked).doesNotContain(MusicSortOrder.NEWEST_ADDED)
        assertThat(liked).contains(MusicSortOrder.OLDEST_ADDED)
    }
}
