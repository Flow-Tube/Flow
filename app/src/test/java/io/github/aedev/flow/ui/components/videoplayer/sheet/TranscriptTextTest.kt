package io.github.aedev.flow.ui.components.videoplayer.sheet

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.transcript.TranscriptCue
import org.junit.Test

class TranscriptTextTest {
    private val cues =
        listOf(
            TranscriptCue(startMs = 0L, text = "Welcome back to the channel."),
            TranscriptCue(startMs = 8_000L, text = "Today we're going to discuss lighting."),
            TranscriptCue(startMs = 72_000L, text = "Start with the key light."),
        )

    @Test
    fun `without timestamps it is just the lines`() {
        assertThat(transcriptText(cues, emptyList(), withTimestamps = false))
            .isEqualTo("Welcome back to the channel.\nToday we're going to discuss lighting.\nStart with the key light.")
    }

    @Test
    fun `with timestamps each line starts with when it was said`() {
        assertThat(transcriptText(cues, emptyList(), withTimestamps = true))
            .isEqualTo(
                "[0:00] Welcome back to the channel.\n[0:08] Today we're going to discuss lighting.\n[1:12] Start with the key light.",
            )
    }

    @Test
    fun `chapters become headings with a blank line before each but the first`() {
        val chapters = listOf(TranscriptChapter(0L, "Intro"), TranscriptChapter(60_000L, "Key light"))

        assertThat(transcriptText(cues, chapters, withTimestamps = false))
            .isEqualTo(
                "Intro\nWelcome back to the channel.\nToday we're going to discuss lighting.\n\nKey light\nStart with the key light.",
            )
    }

    @Test
    fun `past an hour the timestamp shows hours`() {
        val late = listOf(TranscriptCue(startMs = 3_735_000L, text = "Thanks for watching."))

        assertThat(transcriptText(late, emptyList(), withTimestamps = true)).isEqualTo("[1:02:15] Thanks for watching.")
    }

    @Test
    fun `no cues means no text`() {
        assertThat(transcriptText(emptyList(), listOf(TranscriptChapter(0L, "Intro")), withTimestamps = true)).isEmpty()
    }
}
