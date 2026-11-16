package io.github.aedev.flow.data.lyrics

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.lyrics.LyricsMatching.Song
import org.junit.Test

class LyricsMatchingTest {
    @Test
    fun `the search keeps version tags and drops credits and packaging`() {
        assertThat(LyricsMatching.cleanTitle("Song (Live)")).isEqualTo("Song (Live)")
        assertThat(LyricsMatching.cleanTitle("Song (Acoustic Version) [Official Video]")).isEqualTo("Song (Acoustic Version)")
        assertThat(LyricsMatching.cleanTitle("Song (feat. Someone)")).isEqualTo("Song")
        assertThat(LyricsMatching.cleanTitle("Song feat. Someone")).isEqualTo("Song")
        assertThat(LyricsMatching.cleanTitle("Song - Official Music Video")).isEqualTo("Song")
        assertThat(LyricsMatching.cleanTitle("Video Killed the Radio Star")).isEqualTo("Video Killed the Radio Star")
    }

    @Test
    fun `the first credited artist is searched`() {
        assertThat(LyricsMatching.cleanArtist("The Weeknd - Topic")).isEqualTo("The Weeknd")
        assertThat(LyricsMatching.cleanArtist("Calvin Harris feat. Dua Lipa")).isEqualTo("Calvin Harris")
        assertThat(LyricsMatching.cleanArtist("Simon & Garfunkel")).isEqualTo("Simon")
    }

    @Test
    fun `version tags come from brackets and a trailing dash part, as whole words`() {
        assertThat(LyricsMatching.versionTags("Song (Live at Wembley)")).containsExactly("live")
        assertThat(LyricsMatching.versionTags("Song - Acoustic")).containsExactly("acoustic")
        assertThat(LyricsMatching.versionTags("Song (Sped Up)")).containsExactly("sped")
        assertThat(LyricsMatching.versionTags("Song (2011 Remaster)")).isEmpty()
        assertThat(LyricsMatching.versionTags("Stayin' Alive")).isEmpty()
        assertThat(LyricsMatching.versionTags("Song (Alive Edit)")).isEmpty()
    }

    @Test
    fun `a live request never takes the studio cut and the other way round`() {
        assertThat(LyricsMatching.sameVersion("Song (Live)", "Song")).isFalse()
        assertThat(LyricsMatching.sameVersion("Song", "Song (Remix)")).isFalse()
        assertThat(LyricsMatching.sameVersion("Song (Live)", "Song (Live at Wembley)")).isTrue()
        assertThat(LyricsMatching.sameVersion("Song", "Song (2011 Remaster)")).isTrue()
    }

    @Test
    fun `exact title and artist pass the floor, a fuzzy title needs a close duration too`() {
        val request = Song("Blinding Lights", "The Weeknd", 200)

        assertThat(LyricsMatching.score(request, Song("Blinding Lights", "The Weeknd", 0))).isAtLeast(LyricsMatching.MIN_SCORE)
        assertThat(LyricsMatching.score(request, Song("Blinding Lights Tonight", "The Weeknd", 0))).isLessThan(LyricsMatching.MIN_SCORE)
        assertThat(LyricsMatching.score(request, Song("Blinding Lights", "Someone Else", 260))).isLessThan(LyricsMatching.MIN_SCORE)
    }

    @Test
    fun `a featured artist still counts as the artist`() {
        val request = Song("Starboy", "The Weeknd", 230)

        assertThat(LyricsMatching.score(request, Song("Starboy", "The Weeknd & Daft Punk", 230))).isAtLeast(LyricsMatching.MIN_SCORE)
    }

    @Test
    fun `the best candidate is the same version, then highest score, then preferred, then closest duration`() {
        val request = Song("Song (Live)", "Band", 240)
        val candidates =
            listOf(
                Song("Song", "Band", 240),
                Song("Song (Live)", "Band", 250),
                Song("Song (Live)", "Band", 241),
                Song("Song (Live)", "Other", 240),
            )

        assertThat(LyricsMatching.best(candidates, request, { it })).isEqualTo(candidates[2])
    }

    @Test
    fun `a preferred candidate wins a tie`() {
        val request = Song("Song", "Band", 0)
        val candidates = listOf(Song("Song", "Band", 200) to false, Song("Song", "Band", 201) to true)

        assertThat(LyricsMatching.best(candidates, request, { it.first }, { it.second })).isEqualTo(candidates[1])
    }

    @Test
    fun `an artist among several joined with the chinese comma matches`() {
        val request = Song("Song", "Second Band", 200)

        assertThat(LyricsMatching.score(request, Song("Song", "First Band、Second Band", 200))).isAtLeast(LyricsMatching.MIN_SCORE)
    }

    @Test
    fun `a full credit list matches a source that names only one of them`() {
        val request = Song("Song", "Vishal-Shekhar, Arijit Singh, Shilpa Rao", 200)

        assertThat(LyricsMatching.score(request, Song("Song", "Arijit Singh", 200))).isAtLeast(LyricsMatching.MIN_SCORE)
    }

    @Test
    fun `any requested credit can match any credit on the candidate`() {
        val request = Song("Song", "Vishal & Shekhar, Arijit Singh, Shilpa Rao", 200)

        assertThat(LyricsMatching.score(request, Song("Song", "Arijit Singh, Shilpa Rao", 200))).isAtLeast(LyricsMatching.MIN_SCORE)
    }

    @Test
    fun `a different artist still fails against a full credit list`() {
        val request = Song("Song", "Vishal-Shekhar, Arijit Singh, Shilpa Rao", 200)

        assertThat(LyricsMatching.score(request, Song("Song", "Sunidhi Chauhan", 200))).isEqualTo(0)
    }

    @Test
    fun `a matching credit does not excuse another title`() {
        val request = Song("Song", "Vishal-Shekhar, Arijit Singh", 200)

        assertThat(LyricsMatching.score(request, Song("Other", "Arijit Singh", 200))).isEqualTo(0)
    }

    @Test
    fun `a matching credit does not excuse another version`() {
        val request = Song("Song", "Vishal-Shekhar, Arijit Singh", 200)
        val live = Song("Song (Live)", "Arijit Singh", 200)

        assertThat(LyricsMatching.best(listOf(live), request, { it })).isNull()
    }

    @Test
    fun `a name with a comma in it still matches whole`() {
        val request = Song("Song", "Tyler, The Creator", 200)

        assertThat(LyricsMatching.score(request, Song("Song", "Tyler, The Creator", 200))).isAtLeast(LyricsMatching.MIN_SCORE)
    }

    @Test
    fun `ranked lists every accepted candidate best first`() {
        val request = Song("Song", "Band", 200)
        val close = Song("Song", "Band", 200)
        val near = Song("Song", "Band", 209)
        val other = Song("Other", "Band", 200)

        assertThat(LyricsMatching.ranked(listOf(near, other, close), request, { it })).containsExactly(close, near).inOrder()
    }

    @Test
    fun `nothing is picked when no candidate clears the floor`() {
        assertThat(LyricsMatching.best(listOf(Song("Other", "Band", 200)), Song("Song", "Band", 200), { it })).isNull()
    }
}
