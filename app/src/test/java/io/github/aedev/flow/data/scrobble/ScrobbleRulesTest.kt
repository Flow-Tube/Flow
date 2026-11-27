package io.github.aedev.flow.data.scrobble

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.music.model.MusicArtist
import io.github.aedev.flow.data.music.model.MusicTrack
import org.junit.Test

class ScrobbleRulesTest {
    private fun track(
        id: String = "abc",
        artist: String = "Artist - Topic",
        artists: List<MusicArtist> = emptyList(),
        album: String = "Album",
    ) = MusicTrack(videoId = id, title = " Song ", artist = artist, thumbnailUrl = "", duration = 200, album = album, artists = artists)

    @Test
    fun `a listen counts at half the track or four minutes`() {
        assertThat(ScrobbleRules.counts(durationMs = 200_000, playedMs = 99_000)).isFalse()
        assertThat(ScrobbleRules.counts(durationMs = 200_000, playedMs = 100_000)).isTrue()
        assertThat(ScrobbleRules.counts(durationMs = 20 * 60_000, playedMs = 4 * 60_000)).isTrue()
        assertThat(ScrobbleRules.counts(durationMs = 30_000, playedMs = 30_000)).isFalse()
    }

    @Test
    fun `the entry takes the first credited artist and drops the Topic suffix`() {
        assertThat(ScrobbleRules.entryFor(track(), 200_000, 5_000_000)?.artist).isEqualTo("Artist")
        val credited = track(artists = listOf(MusicArtist("Lead"), MusicArtist("Guest")))
        val entry = ScrobbleRules.entryFor(credited, 200_000, 5_000_000)!!
        assertThat(entry.artist).isEqualTo("Lead")
        assertThat(entry.title).isEqualTo("Song")
        assertThat(entry.timestampSec).isEqualTo(5_000)
        assertThat(entry.durationSec).isEqualTo(200)
    }

    @Test
    fun `placeholder albums are left out and device files are marked`() {
        val entry = ScrobbleRules.entryFor(track(id = "local_9", album = "Unknown Album"), 200_000, 0)!!
        assertThat(entry.album).isEmpty()
        assertThat(entry.fromYouTube).isFalse()
        assertThat(entry.thumbnailUrl).isEmpty()
    }

    @Test
    fun `the entry keeps the video id and web artwork for webhooks`() {
        val entry = ScrobbleRules.entryFor(track(), 200_000, 0)!!
        assertThat(entry.videoId).isEqualTo("abc")
        assertThat(entry.thumbnailUrl).startsWith("https://")
    }

    @Test
    fun `nothing is sent without an artist`() {
        assertThat(ScrobbleRules.entryFor(track(artist = " "), 200_000, 0)).isNull()
    }

    @Test
    fun `a love uses the same artist as the scrobble`() {
        assertThat(ScrobbleRules.loveFor(track(), loved = true)).isEqualTo(LoveEntry("Artist", "Song", loved = true, videoId = "abc"))
    }

    @Test
    fun `the threshold is half the track, capped at four minutes, and absent for short tracks`() {
        assertThat(ScrobbleRules.thresholdMs(200_000)).isEqualTo(100_000)
        assertThat(ScrobbleRules.thresholdMs(20 * 60_000)).isEqualTo(4 * 60_000)
        assertThat(ScrobbleRules.thresholdMs(30_000)).isNull()
    }
}
