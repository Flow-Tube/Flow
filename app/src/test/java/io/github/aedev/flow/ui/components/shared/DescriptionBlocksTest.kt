package io.github.aedev.flow.ui.components.shared

import io.github.aedev.flow.data.model.RichText
import io.github.aedev.flow.data.model.RichTextEmoji
import io.github.aedev.flow.data.model.RichTextHighlight
import io.github.aedev.flow.data.model.RichTextSpan
import io.github.aedev.flow.data.model.RichTextTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class DescriptionBlocksTest {
    private val nbsp = ' '

    /** A labelled video link the way the server prints it: label, then the padded chip. */
    private fun videoLine(
        label: String,
        title: String,
    ): String = "$label $nbsp$nbsp$nbsp•$nbsp${title.replace(' ', nbsp)}...$nbsp$nbsp"

    private fun RichText.withVideoLink(
        line: String,
        label: String,
        videoId: String,
    ): RichText {
        val start = text.indexOf(line) + label.length + 1
        val length = line.length - label.length - 1
        return copy(
            spans = spans + RichTextSpan(start, length, RichTextTarget.Video(videoId, startSeconds = 0)),
            emojis = emojis + RichTextEmoji(start + 2, 0, "https://example.com/yt.png", ""),
            highlights = highlights + RichTextHighlight(start, length),
        )
    }

    @Test
    fun `a video link that ends its line becomes a card below its label`() {
        val first = videoLine("Must watch:", "First video title")
        val second = videoLine("Also:", "Second one")
        val text = "Intro text\n$first\n$second\n\nOutro"
        val rich =
            RichText(text = text)
                .withVideoLink(first, "Must watch:", "aaaaaaaaaaa")
                .withVideoLink(second, "Also:", "bbbbbbbbbbb")

        val blocks = rich.descriptionBlocks(ownVideoId = "ownVideo123")

        assertEquals(
            listOf(
                DescriptionBlock.Text(RichText("Intro text\nMust watch:")),
                DescriptionBlock.VideoCard("aaaaaaaaaaa", 0, "First video title"),
                DescriptionBlock.Text(RichText("Also:")),
                DescriptionBlock.VideoCard("bbbbbbbbbbb", 0, "Second one"),
                DescriptionBlock.Text(RichText("Outro")),
            ),
            blocks,
        )
    }

    @Test
    fun `a link mid-sentence, a timestamp and a link back to this video stay inline`() {
        val text = "See this video for more.\nIntro 0:00\nThis one again"
        val rich =
            RichText(
                text = text,
                spans =
                    listOf(
                        RichTextSpan(4, 4, RichTextTarget.Video("aaaaaaaaaaa")),
                        RichTextSpan(31, 4, RichTextTarget.Timestamp(0)),
                        RichTextSpan(36, 14, RichTextTarget.Video("ownVideo123")),
                    ),
            )

        assertEquals(listOf(DescriptionBlock.Text(rich)), rich.descriptionBlocks(ownVideoId = "ownVideo123"))
    }

    @Test
    fun `text around a card keeps its own links at shifted positions`() {
        val line = videoLine("Watch:", "Next")
        val text = "Go to https://a.example now\n$line\nthen #tag"
        val rich =
            RichText(
                text = text,
                spans =
                    listOf(
                        RichTextSpan(6, 17, RichTextTarget.Url("https://a.example")),
                        RichTextSpan(text.indexOf("#tag"), 4, RichTextTarget.Hashtag("tag")),
                    ),
            ).withVideoLink(line, "Watch:", "aaaaaaaaaaa")

        val texts = rich.descriptionBlocks(ownVideoId = null).filterIsInstance<DescriptionBlock.Text<RichText>>().map { it.text }

        assertEquals(listOf(RichTextSpan(6, 17, RichTextTarget.Url("https://a.example"))), texts[0].spans)
        assertEquals(listOf(RichTextSpan(5, 4, RichTextTarget.Hashtag("tag"))), texts[1].spans)
        assertEquals("then #tag", texts[1].text)
    }

    @Test
    fun `the card title drops the bullet, padding and the server's ellipsis`() {
        assertEquals(
            "GPT news: big day",
            videoCardTitle("$nbsp$nbsp$nbsp•${nbsp}GPT${nbsp}news:${nbsp}big${nbsp}day$nbsp...$nbsp$nbsp"),
        )
        assertEquals("https://youtu.be/abc", videoCardTitle("https://youtu.be/abc"))
    }
}
