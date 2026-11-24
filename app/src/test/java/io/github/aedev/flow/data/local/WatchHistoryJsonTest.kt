package io.github.aedev.flow.data.local

import com.google.common.truth.Truth.assertThat
import com.google.gson.GsonBuilder
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader
import java.io.StringWriter

class WatchHistoryJsonTest {
    private val json = WatchHistoryJson(GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create())

    private fun read(text: String) = json.read(BufferedReader(StringReader(text)))

    private fun entry(
        videoId: String,
        timestamp: Long,
    ) = VideoHistoryEntry(
        videoId = videoId,
        position = 0L,
        duration = 0L,
        timestamp = timestamp,
        title = "Title $videoId",
        thumbnailUrl = "",
        channelName = "Channel",
        channelId = "UC0123456789012345678901",
    )

    @Test
    fun `an exported Takeout list reads back the same videos`() {
        val history = listOf(entry("aaaaaaaaaaa", 1_700_000_000_000), entry("bbbbbbbbbbb", 1_700_000_100_000))
        val out = StringWriter()

        json.writeTakeout(history, out)
        val back = read(out.toString())

        assertThat(back.map { it.videoId }).containsExactly("aaaaaaaaaaa", "bbbbbbbbbbb").inOrder()
        assertThat(back.map { it.title }).containsExactly("Title aaaaaaaaaaa", "Title bbbbbbbbbbb").inOrder()
        assertThat(back.map { it.timestamp }).containsExactly(1_700_000_000_000, 1_700_000_100_000).inOrder()
        assertThat(back.first().channelId).isEqualTo("UC0123456789012345678901")
    }

    @Test
    fun `Takeout entries with details are ads and are skipped`() {
        val takeout =
            """
            [ {"title":"Watched Ad","titleUrl":"https://www.youtube.com/watch?v=ccccccccccc","details":[{"name":"From Google Ads"}]},
              {"title":"Watched Real","titleUrl":"https://www.youtube.com/watch?v=ddddddddddd","time":"2026-01-02T03:04:05Z"} ]
            """.trimIndent()

        assertThat(read(takeout).map { it.videoId }).containsExactly("ddddddddddd")
    }

    @Test
    fun `a FreeTube export is read line by line`() {
        val freeTube =
            """
            {"videoId":"eeeeeeeeeee","title":"One","author":"A","timeWatched":5,"lengthSeconds":100,"watchProgress":0.5}

            not json
            {"videoId":"fffffffffff","title":"Two","timeWatched":6}
            """.trimIndent()

        val entries = read(freeTube)

        assertThat(entries.map { it.videoId }).containsExactly("eeeeeeeeeee", "fffffffffff").inOrder()
        assertThat(entries.first().position).isEqualTo(50_000L)
    }

    @Test
    fun `anything else reads as nothing`() {
        assertThat(read("   ")).isEmpty()
        assertThat(read("<html>")).isEmpty()
        assertThat(read("[ {\"title\": ")).isEmpty()
    }
}
