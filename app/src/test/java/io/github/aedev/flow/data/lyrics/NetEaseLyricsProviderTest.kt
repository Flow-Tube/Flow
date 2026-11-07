package io.github.aedev.flow.data.lyrics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NetEaseLyricsProviderTest {
    private val song = (1..6).joinToString("\n") { "[00:${10 + it}.50]Line $it" }

    @Test
    fun `a song's lines come through with their times`() {
        val entries = NetEaseLyricsProvider.parseLyric(song)

        assertThat(entries).hasSize(6)
        assertThat(entries.first().time).isEqualTo(11_500L)
        assertThat(entries.first().text).isEqualTo("Line 1")
    }

    @Test
    fun `credit lines are not lyrics`() {
        val entries = NetEaseLyricsProvider.parseLyric("[00:00.00-1] 作词 : Someone\n[00:00.00-1] 作曲 : Someone\n[00:01.00]Composer: A\n$song")

        assertThat(entries.map { it.text }).doesNotContain("作词 : Someone")
        assertThat(entries.map { it.text }).doesNotContain("Composer: A")
        assertThat(entries).hasSize(6)
    }

    @Test
    fun `an answer holding only credits counts as nothing`() {
        assertThat(NetEaseLyricsProvider.parseLyric("[00:00.00-1] 作词 : Someone\n[00:00.00-1] 作曲 : Someone")).isEmpty()
    }

    @Test
    fun `no lyric placeholder counts as nothing`() {
        assertThat(NetEaseLyricsProvider.parseLyric("[00:00.00]暂无歌词")).isEmpty()
    }

    @Test
    fun `a stamp with a suffix keeps its time`() {
        val entries = NetEaseLyricsProvider.parseLyric(song + "\n[01:00.00-1]Line 7")

        assertThat(entries.last().time).isEqualTo(60_000L)
        assertThat(entries.last().text).isEqualTo("Line 7")
    }
}
