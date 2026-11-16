package io.github.aedev.flow.data.lyrics.kugou

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.lyrics.LyricsMatching
import io.github.aedev.flow.data.lyrics.kugou.models.SearchLyricsResponse
import io.github.aedev.flow.data.lyrics.kugou.models.SearchSongResponse
import org.junit.Test

class KuGouMatchingTest {
    private fun row(
        name: String,
        singer: String,
        seconds: Int,
    ) = SearchSongResponse.Data.Info(duration = seconds, hash = name, songname = name, singername = singer)

    private fun lyric(
        name: String,
        singer: String,
        millis: Long,
    ) = SearchLyricsResponse.Candidate(song = name, singer = singer, duration = millis)

    @Test
    fun `a row for another song with a close length is refused`() {
        val request = LyricsMatching.Song("Crazy Kiya Re", "Sunidhi Chauhan", 295)

        assertThat(KuGou.matchingRows(listOf(row("Mere Haath Mein", "Sunidhi Chauhan", 287)), request)).isEmpty()
    }

    @Test
    fun `a row in another language with a close length is refused`() {
        val request = LyricsMatching.Song("Yeh Mera Husn", "Sunidhi Chauhan", 240)

        assertThat(KuGou.matchingRows(listOf(row("让一切随风", "钟镇涛", 244)), request)).isEmpty()
    }

    @Test
    fun `the right row whose first lyric is another song gets the right lyric`() {
        val request = LyricsMatching.Song("Kamli", "Sunidhi Chauhan", 235)
        val right = row("Kamli", "Sunidhi Chauhan", 234)
        val candidates = listOf(lyric("Amali Dhumali", "Sunidhi Chauhan", 236_511), lyric("Kamli", "Sunidhi Chauhan", 234_788))

        assertThat(KuGou.matchingRows(listOf(right), request)).containsExactly(right)
        assertThat(KuGou.bestLyric(candidates, request)).isEqualTo(candidates[1])
    }

    @Test
    fun `only a lyric for another song means no lyric`() {
        val request = LyricsMatching.Song("Kamli", "Sunidhi Chauhan", 235)

        assertThat(KuGou.bestLyric(listOf(lyric("Amali Dhumali", "Sunidhi Chauhan", 236_511)), request)).isNull()
    }

    @Test
    fun `a keyword lyric with its song and singer swapped is refused`() {
        val request = LyricsMatching.Song("Kamli", "Sunidhi Chauhan", 235)

        assertThat(KuGou.bestLyric(listOf(lyric("Sunidhi Chauhan", "Kamli", 236)), request)).isNull()
    }

    @Test
    fun `a singer among several joined with the chinese comma matches`() {
        val request = LyricsMatching.Song("Aa Tayar Hoja", "Anu Malik", 371)
        val joined = row("Aa Tayar Hoja", "Sunidhi Chauhan、Anu Malik", 371)

        assertThat(KuGou.matchingRows(listOf(joined), request)).containsExactly(joined)
    }

    @Test
    fun `a row or lyric too far from the song's length is refused`() {
        val request = LyricsMatching.Song("Kamli", "Sunidhi Chauhan", 235)

        assertThat(KuGou.matchingRows(listOf(row("Kamli", "Sunidhi Chauhan", 280)), request)).isEmpty()
        assertThat(KuGou.bestLyric(listOf(lyric("Kamli", "Sunidhi Chauhan", 280_000)), request)).isNull()
    }

    @Test
    fun `a lyric duration in seconds reads the same as one in milliseconds`() {
        val request = LyricsMatching.Song("Kamli", "Sunidhi Chauhan", 235)

        assertThat(KuGou.bestLyric(listOf(lyric("Kamli", "Sunidhi Chauhan", 236)), request)).isNotNull()
    }

    @Test
    fun `with no known length the names alone decide`() {
        val request = LyricsMatching.Song("Kamli", "Sunidhi Chauhan", 0)
        val right = row("Kamli", "Sunidhi Chauhan", 234)

        assertThat(KuGou.matchingRows(listOf(right, row("Malang", "Sunidhi Chauhan", 234)), request)).containsExactly(right)
    }
}
