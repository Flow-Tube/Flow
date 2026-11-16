package io.github.aedev.flow.ui.screens.music

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.music.model.MusicArtist
import io.github.aedev.flow.innertube.models.Artist
import io.github.aedev.flow.innertube.models.SongItem
import org.junit.Test

class SearchSongArtistsTest {
    @Test
    fun `a searched song keeps every credited artist with its id`() {
        val track =
            convertSongToMusicTrack(
                SongItem(
                    id = "straight-up",
                    title = "Straight Up",
                    artists = listOf(Artist("Karan Aujla", "UCka"), Artist("Mxrci", "UCmxrci")),
                    thumbnail = "",
                ),
            )

        assertThat(track.artist).isEqualTo("Karan Aujla, Mxrci")
        assertThat(track.channelId).isEqualTo("UCka")
        assertThat(track.artists).containsExactly(MusicArtist("Karan Aujla", "UCka"), MusicArtist("Mxrci", "UCmxrci")).inOrder()
    }
}
