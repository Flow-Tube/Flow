package io.github.aedev.flow.data.lyrics

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.Test

class SimpMusicLyricsTest {
    private val simpMusic = SimpMusicLyrics(mockk())

    private fun row(
        seconds: Int,
        vote: Int,
        line: String,
    ) = """{"durationSeconds":$seconds,"vote":$vote,"syncedLyrics":"[00:01.00]$line\n[00:30.00]$line again"}"""

    private fun response(vararg rows: String) = """{"type":"success","data":[${rows.joinToString(",")}]}"""

    @Test
    fun `a song with no row near its length has no lyrics`() {
        val kesariya = response(row(62, 3, "short"))

        assertThat(simpMusic.parseResponse(kesariya, duration = 269)).isNull()
    }

    @Test
    fun `the row closest to the song's length wins`() {
        val entries = simpMusic.parseResponse(response(row(275, 9, "far"), row(270, 0, "close")), duration = 269)

        assertThat(entries?.first()?.text).isEqualTo("close")
    }

    @Test
    fun `votes break a tie on length`() {
        val entries = simpMusic.parseResponse(response(row(270, 1, "fewer"), row(268, 5, "more")), duration = 269)

        assertThat(entries?.first()?.text).isEqualTo("more")
    }

    @Test
    fun `a row just inside ten seconds is kept and one just outside is refused`() {
        assertThat(simpMusic.parseResponse(response(row(279, 0, "in")), duration = 269)).isNotNull()
        assertThat(simpMusic.parseResponse(response(row(280, 0, "out")), duration = 269)).isNull()
    }

    @Test
    fun `with no known length the most voted row wins`() {
        val entries = simpMusic.parseResponse(response(row(100, 1, "fewer"), row(200, 4, "more")), duration = 0)

        assertThat(entries?.first()?.text).isEqualTo("more")
    }

    @Test
    fun `a row with no vote counts as none`() {
        val noVote = """{"durationSeconds":270,"syncedLyrics":"[00:01.00]none\n[00:30.00]none again"}"""

        assertThat(simpMusic.parseResponse(response(noVote), duration = 269)?.first()?.text).isEqualTo("none")
    }

    @Test
    fun `a null field counts as absent`() {
        val row = """{"durationSeconds":270,"vote":null,"richSyncLyrics":null,"syncedLyrics":"[00:01.00]one\n[00:30.00]two"}"""

        assertThat(simpMusic.parseResponse(response(row), duration = 269)?.first()?.text).isEqualTo("one")
    }
}
