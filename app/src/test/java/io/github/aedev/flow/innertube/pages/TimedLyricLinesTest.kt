package io.github.aedev.flow.innertube.pages

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class TimedLyricLinesTest {
    private fun response(lines: String): JsonObject =
        Json
            .parseToJsonElement(
                """
                {"contents":{"elementRenderer":{"newElement":{"type":{"componentType":{"model":{"timedLyricsModel":
                {"lyricsData":{"sourceMessage":"Source: Test","timedLyricsData":[$lines]}}}}}}}}}
                """.trimIndent(),
            ).jsonObject

    private fun cue(
        text: String,
        start: String,
    ) = """{"lyricLine":"$text","cueRange":{"startTimeMilliseconds":"$start","endTimeMilliseconds":"99000"}}"""

    @Test
    fun `reads each line with its start time`() {
        val lines = timedLyricLines(response(cue("First", "33740") + "," + cue("Second", "38910")))

        assertThat(lines).containsExactly(TimedLyricLine(33_740, "First"), TimedLyricLine(38_910, "Second")).inOrder()
    }

    @Test
    fun `drops blank lines and the music note placeholder`() {
        val lines =
            timedLyricLines(response(listOf(cue("♪", "0"), cue(" ", "100"), cue("♪ ♪", "200"), cue("Sung", "300")).joinToString(",")))

        assertThat(lines).containsExactly(TimedLyricLine(300, "Sung"))
    }

    @Test
    fun `a music video row with lines but no times has no timed lines`() {
        val lines = timedLyricLines(response("""{"lyricLine":"First"},{"lyricLine":"Second"}"""))

        assertThat(lines).isEmpty()
    }

    @Test
    fun `any other response shape has no timed lines`() {
        val other = Json.parseToJsonElement("""{"contents":{"sectionListRenderer":{}}}""").jsonObject

        assertThat(timedLyricLines(other)).isEmpty()
    }
}
