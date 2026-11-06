package io.github.aedev.flow.data.lyrics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LrcLibLyricsProviderTest {
    private fun result(
        track: String,
        artist: String,
        seconds: Double?,
        synced: Boolean = true,
    ) = LrcLibLyricsProvider.SearchResult(
        trackName = track,
        artistName = artist,
        duration = seconds,
        syncedLyrics = "[00:01.00]line".takeIf { synced },
        plainLyrics = "line",
    )

    @Test
    fun `a tribute version much shorter than the song is refused`() {
        val request = LyricsMatching.Song("Teri Mitti", "Arko", 314)

        assertThat(LrcLibLyricsProvider.pick(listOf(result("Teri Mitti - Tribute", "B Praak & Arko", 242.0)), request)).isNull()
    }

    @Test
    fun `another song is refused whatever its length`() {
        val request = LyricsMatching.Song("Teri Mitti", "Arko", 314)

        assertThat(LrcLibLyricsProvider.pick(listOf(result("Malang", "Arko", 314.0)), request)).isNull()
    }

    @Test
    fun `another artist's song of the same name is refused`() {
        val request = LyricsMatching.Song("Teri Mitti", "Arko", 314)

        assertThat(LrcLibLyricsProvider.pick(listOf(result("Teri Mitti", "Someone Else", 314.0)), request)).isNull()
    }

    @Test
    fun `a synced match beats a closer plain one`() {
        val request = LyricsMatching.Song("Teri Mitti", "Arko", 314)
        val plain = result("Teri Mitti", "Arko", 314.0, synced = false)
        val synced = result("Teri Mitti", "Arko feat. B Praak", 318.0)

        assertThat(LrcLibLyricsProvider.pick(listOf(plain, synced), request)).isEqualTo(synced)
    }

    @Test
    fun `a plain match is kept when no synced match exists`() {
        val request = LyricsMatching.Song("Teri Mitti", "Arko", 314)
        val plain = result("Teri Mitti", "Arko feat. B Praak, Akshay Kumar & Parineeti Chopra", 314.0, synced = false)

        assertThat(LrcLibLyricsProvider.pick(listOf(result("Teri Mitti - Tribute", "B Praak & Arko", 242.0), plain), request))
            .isEqualTo(plain)
    }

    @Test
    fun `a row with no length can still match on its names`() {
        val request = LyricsMatching.Song("Teri Mitti", "Arko", 314)
        val noLength = result("Teri Mitti", "Arko", null)

        assertThat(LrcLibLyricsProvider.pick(listOf(noLength), request)).isEqualTo(noLength)
    }
}
