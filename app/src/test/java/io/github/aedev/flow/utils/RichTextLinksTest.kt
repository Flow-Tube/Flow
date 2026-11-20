package io.github.aedev.flow.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.style.TextDecoration
import io.github.aedev.flow.data.model.RichText
import io.github.aedev.flow.data.model.RichTextHighlight
import io.github.aedev.flow.data.model.RichTextSpan
import io.github.aedev.flow.data.model.RichTextTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RichTextLinksTest {
    private val richText =
        RichText(
            text = "seek video channel site #tag plain",
            spans =
                listOf(
                    RichTextSpan(0, 4, RichTextTarget.Timestamp(90)),
                    RichTextSpan(5, 5, RichTextTarget.Video("dQw4w9WgXcQ")),
                    RichTextSpan(11, 7, RichTextTarget.Channel("UCXuqSBlHAE6Xw-yeJA0Tunw")),
                    RichTextSpan(19, 4, RichTextTarget.Url("https://example.com")),
                    RichTextSpan(24, 4, RichTextTarget.Hashtag("tag")),
                ),
        )

    private val events = mutableListOf<String>()

    private val allActions =
        RichTextLinkActions(
            onSeekMs = { events += "seek:$it" },
            onOpenUrl = { events += "open:$it" },
            onChannel = { events += "channel:$it" },
            onHashtag = { events += "hashtag:$it" },
        )

    private fun AnnotatedString.links() = getLinkAnnotations(0, length)

    private fun AnnotatedString.tapAll() =
        links().forEach { range ->
            val link = range.item
            requireNotNull(link.linkInteractionListener).onClick(link)
        }

    @Test
    fun `each link goes where it points`() {
        richText.toAnnotatedString(Color.Blue, Color.Black, allActions).tapAll()

        assertEquals(
            listOf(
                "seek:90000",
                "open:https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                "channel:UCXuqSBlHAE6Xw-yeJA0Tunw",
                "open:https://example.com",
                "hashtag:tag",
            ),
            events,
        )
    }

    @Test
    fun `every link is a clickable link over its own range`() {
        val links = richText.toAnnotatedString(Color.Blue, Color.Black, allActions).links()

        assertEquals(listOf(0 to 4, 5 to 10, 11 to 18, 19 to 23, 24 to 28), links.map { it.start to it.end })
        assertTrue(links.all { it.item is LinkAnnotation.Clickable })
    }

    @Test
    fun `a kind with no action stays styled text and is not offered as a link`() {
        val comments = RichTextLinkActions(onSeekMs = { events += "seek:$it" }, onOpenUrl = { events += "open:$it" })
        val rendered = richText.toAnnotatedString(Color.Blue, Color.Black, comments)

        assertEquals(listOf(0 to 4, 5 to 10, 19 to 23), rendered.links().map { it.start to it.end })
        assertTrue(rendered.spanStyles.any { it.start == 24 && it.end == 28 && it.item.color == Color.Blue })
    }

    @Test
    fun `a link inside a chip is not underlined`() {
        val chipped =
            richText.copy(highlights = listOf(RichTextHighlight(19, 4))).toAnnotatedString(Color.Blue, Color.Black, allActions)
        val styles =
            chipped.links().associate {
                it.start to
                    it.item.styles
                        ?.style
                        ?.textDecoration
            }

        assertEquals(TextDecoration.None, styles[19])
        assertEquals(TextDecoration.Underline, styles[5])
    }

    @Test
    fun `a video link keeps its start time`() {
        RichText(
            text = "watch",
            spans = listOf(RichTextSpan(0, 5, RichTextTarget.Video("dQw4w9WgXcQ", startSeconds = 42))),
        ).toAnnotatedString(Color.Blue, Color.Black, allActions).tapAll()

        assertEquals(listOf("open:https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s"), events)
    }
}
