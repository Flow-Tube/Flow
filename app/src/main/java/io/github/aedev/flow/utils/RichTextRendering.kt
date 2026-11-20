package io.github.aedev.flow.utils

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import io.github.aedev.flow.data.model.RichText
import io.github.aedev.flow.data.model.RichTextStyle
import io.github.aedev.flow.data.model.RichTextTarget

const val RICH_TEXT_SEEK = "SEEK_SECONDS"
const val RICH_TEXT_URL = "URL"
const val RICH_TEXT_CHANNEL = "CHANNEL"

/** Carries the video's watch link, so a tap on it goes wherever a typed link to that video would. */
const val RICH_TEXT_VIDEO = "VIDEO"
const val RICH_TEXT_HASHTAG = "HASHTAG"
const val RICH_TEXT_HIGHLIGHT = "HIGHLIGHT"
const val RICH_TEXT_EMOJI_PREFIX = "emoji:"

/** The placeholder an inserted image occupies; it carries no meaning of its own. */
private const val INSERTED_IMAGE_PLACEHOLDER = "￼"

/**
 * Renders text the server already described, so nothing here has to be recovered with a regex.
 *
 * Images arrive in two shapes and both move what follows them: a custom emoji replaces a range, a
 * platform icon is inserted with no range at all. Every later index is therefore remapped rather
 * than trusted, which is what keeps a link's styling on the link and not on the words after it.
 */
fun RichText.toAnnotatedString(
    linkColor: Color,
    textColor: Color,
): AnnotatedString {
    val images = emojis.sortedWith(compareBy({ it.start }, { it.length }))
    val offsets = IntArray(text.length + 1)

    val rendered =
        buildAnnotatedString {
            var index = 0
            var out = 0
            var imageIndex = 0
            while (index < text.length) {
                while (imageIndex < images.size && images[imageIndex].start == index) {
                    val image = images[imageIndex]
                    val replaced = image.length.coerceAtMost(text.length - index)
                    val placeholder =
                        if (replaced > 0) text.substring(index, index + replaced) else INSERTED_IMAGE_PLACEHOLDER
                    appendInlineContent(
                        id = RICH_TEXT_EMOJI_PREFIX + image.imageUrl,
                        alternateText = placeholder,
                    )
                    for (consumed in index until index + replaced) offsets[consumed] = out
                    out += placeholder.length
                    index += replaced
                    imageIndex++
                }
                if (index >= text.length) break
                offsets[index] = out
                append(text[index])
                out++
                index++
            }
            offsets[text.length] = out
        }

    fun map(position: Int): Int = offsets[position.coerceIn(0, text.length)]

    val highlightRanges = highlights.map { map(it.start) until map(it.end.coerceAtMost(text.length)) }

    return buildAnnotatedString {
        append(rendered)
        if (length > 0) addStyle(SpanStyle(color = textColor), 0, length)

        styles.forEach { style ->
            val start = map(style.start)
            val end = map(style.end.coerceAtMost(text.length))
            if (start < end) addStyle(style.toSpanStyle(), start, end)
        }

        highlightRanges.forEach { range ->
            if (!range.isEmpty()) addStringAnnotation(RICH_TEXT_HIGHLIGHT, "", range.first, range.last + 1)
        }

        spans.forEach { span ->
            val start = map(span.start)
            val end = map(span.end.coerceAtMost(text.length))
            if (start >= end) return@forEach
            // A link inside a highlight is already marked by the chip behind it; underlining it too
            // is what made a column of social handles read as a column of corrections.
            val highlighted = highlightRanges.any { start >= it.first && end <= it.last + 1 }
            when (val target = span.target) {
                is RichTextTarget.Timestamp -> {
                    // Colour and weight alone: a timestamp sits mid-sentence, and a chip around
                    // three digits reads as a button dropped into the middle of a paragraph.
                    addStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.Bold), start, end)
                    addStringAnnotation(RICH_TEXT_SEEK, target.seconds.toString(), start, end)
                }

                is RichTextTarget.Video -> {
                    addStyle(linkStyle(linkColor, highlighted), start, end)
                    addStringAnnotation(RICH_TEXT_VIDEO, youtubeWatchUrl(target.videoId, target.startSeconds), start, end)
                }

                is RichTextTarget.Channel -> {
                    addStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.Medium), start, end)
                    addStringAnnotation(RICH_TEXT_CHANNEL, target.browseId, start, end)
                }

                is RichTextTarget.Hashtag -> {
                    addStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.Bold), start, end)
                    addStringAnnotation(RICH_TEXT_HASHTAG, target.tag, start, end)
                }

                is RichTextTarget.Url -> {
                    addStyle(linkStyle(linkColor, highlighted), start, end)
                    addStringAnnotation(RICH_TEXT_URL, target.url, start, end)
                }
            }
        }
    }
}

private fun linkStyle(
    linkColor: Color,
    highlighted: Boolean,
) = SpanStyle(
    color = linkColor,
    textDecoration = if (highlighted) TextDecoration.None else TextDecoration.Underline,
    fontWeight = FontWeight.Medium,
)

private fun RichTextStyle.toSpanStyle() =
    SpanStyle(
        fontWeight = if (bold) FontWeight.Bold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        textDecoration = if (strikethrough) TextDecoration.LineThrough else null,
    )
