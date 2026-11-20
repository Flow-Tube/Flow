package io.github.aedev.flow.utils

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import io.github.aedev.flow.data.model.RichText
import io.github.aedev.flow.data.model.RichTextStyle
import io.github.aedev.flow.data.model.RichTextTarget

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
    actions: RichTextLinkActions = RichTextLinkActions(),
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
            val highlighted = highlightRanges.any { start >= it.first && end <= it.last + 1 }
            val style = span.target.linkStyle(linkColor, highlighted)
            val onClick = span.target.clickAction(actions)
            if (onClick == null) {
                addStyle(style, start, end)
            } else {
                addLink(
                    LinkAnnotation.Clickable(
                        tag = span.target.toString(),
                        styles = linkStyles(style, linkColor),
                        linkInteractionListener = { onClick() },
                    ),
                    start,
                    end,
                )
            }
        }
    }
}

/**
 * What a tap on each kind of link does. A null leaves that kind as styled text with no link, so
 * TalkBack is never offered a link that does nothing. A video goes through [onOpenUrl] as its watch
 * link, so it lands wherever a typed link to that video would.
 */
@Stable
class RichTextLinkActions(
    val onSeekMs: ((Long) -> Unit)? = null,
    val onOpenUrl: ((String) -> Unit)? = null,
    val onChannel: ((String) -> Unit)? = null,
    val onHashtag: ((String) -> Unit)? = null,
)

internal fun RichTextTarget.clickAction(actions: RichTextLinkActions): (() -> Unit)? =
    when (this) {
        is RichTextTarget.Timestamp -> actions.onSeekMs?.let { seek -> { seek(seconds * 1_000L) } }
        is RichTextTarget.Video -> actions.onOpenUrl?.let { open -> { open(youtubeWatchUrl(videoId, startSeconds)) } }
        is RichTextTarget.Channel -> actions.onChannel?.let { open -> { open(browseId) } }
        is RichTextTarget.Hashtag -> actions.onHashtag?.let { open -> { open(tag.removePrefix("#")) } }
        is RichTextTarget.Url -> actions.onOpenUrl?.let { open -> { open(url) } }
    }

private fun RichTextTarget.linkStyle(
    linkColor: Color,
    highlighted: Boolean,
): SpanStyle =
    when {
        // The chip behind it already marks the link, so it reads in the text's own colour, as
        // YouTube draws it; an underline as well made a column of social handles read as a column
        // of corrections.
        highlighted -> {
            SpanStyle(textDecoration = TextDecoration.None)
        }

        // Colour and weight alone: a timestamp sits mid-sentence, and a chip around three digits
        // reads as a button dropped into the middle of a paragraph.
        this is RichTextTarget.Timestamp || this is RichTextTarget.Hashtag -> {
            SpanStyle(color = linkColor, fontWeight = FontWeight.Bold)
        }

        this is RichTextTarget.Channel -> {
            SpanStyle(color = linkColor, fontWeight = FontWeight.Medium)
        }

        else -> {
            SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Medium)
        }
    }

/** The link's own look, plus the M3 state-layer tint while it is focused or pressed. */
internal fun linkStyles(
    style: SpanStyle,
    linkColor: Color,
): TextLinkStyles {
    val state = SpanStyle(background = linkColor.copy(alpha = LINK_STATE_LAYER_ALPHA))
    return TextLinkStyles(style = style, focusedStyle = state, pressedStyle = state)
}

private const val LINK_STATE_LAYER_ALPHA = 0.1f

private fun RichTextStyle.toSpanStyle() =
    SpanStyle(
        fontWeight = if (bold) FontWeight.Bold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        textDecoration = if (strikethrough) TextDecoration.LineThrough else null,
    )
