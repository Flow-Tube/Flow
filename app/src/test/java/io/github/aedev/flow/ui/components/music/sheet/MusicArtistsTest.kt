package io.github.aedev.flow.ui.components.music.sheet

import io.github.aedev.flow.data.music.model.MusicArtist
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.newmusic.MusicArtistLookup
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicArtistsTest {
    private val fonsi = MusicArtist("Luis Fonsi", "UCk91oFc2hY2CdHirU93baLg")
    private val yankee = MusicArtist("Daddy Yankee", "UCXRloA7YFgCfd6sU2_Y12cA")

    private fun track(
        artist: String,
        channelId: String = fonsi.id!!,
        artists: List<MusicArtist> = emptyList(),
        videoId: String = "kJQP7kiw5Fk",
    ) = MusicTrack(
        videoId = videoId,
        title = "Despacito",
        artist = artist,
        thumbnailUrl = "",
        duration = 282,
        channelId = channelId,
        artists = artists,
    )

    @Test
    fun `a history song naming two artists with one channel needs a lookup`() {
        assertTrue(track("Luis Fonsi, Daddy Yankee").namesMoreArtistsThanIds())
        assertTrue(track("Luis Fonsi & Daddy Yankee").namesMoreArtistsThanIds())
    }

    @Test
    fun `a song with every artist's id or a single artist needs none`() {
        assertFalse(track("Luis Fonsi, Daddy Yankee", artists = listOf(fonsi, yankee)).namesMoreArtistsThanIds())
        assertFalse(track("Luis Fonsi").namesMoreArtistsThanIds())
    }

    @Test
    fun `a looked-up song opens every credited artist`() =
        runTest {
            val lookup = MusicArtistLookup { listOf(fonsi, yankee) }

            assertEquals(listOf(fonsi, yankee), resolveTrackArtists(track("Luis Fonsi, Daddy Yankee", videoId = "lookup-1"), lookup))
        }

    @Test
    fun `a name with a comma in it keeps its one artist when the lookup finds no more`() =
        runTest {
            val creator = MusicArtist("Tyler, The Creator", "UCHmy")
            val lookup = MusicArtistLookup { listOf(creator) }

            assertEquals(
                listOf(creator),
                resolveTrackArtists(track("Tyler, The Creator", channelId = "UCHmy", videoId = "lookup-2"), lookup),
            )
        }

    @Test
    fun `a song that needs no lookup never asks`() =
        runTest {
            var asked = false
            val lookup =
                MusicArtistLookup {
                    asked = true
                    emptyList()
                }

            resolveTrackArtists(track("Luis Fonsi"), lookup)

            assertFalse(asked)
        }
}
