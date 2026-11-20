package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.em
import coil3.compose.AsyncImage
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.data.model.RichText
import io.github.aedev.flow.data.model.plainRichText
import io.github.aedev.flow.utils.RICH_TEXT_EMOJI_PREFIX
import io.github.aedev.flow.utils.RichTextLinkActions
import io.github.aedev.flow.utils.toAnnotatedString

private val EmojiSize = 1.2.em

/** A comment's text and its inline emoji; its links carry their own tap actions. */
@Immutable
internal data class CommentTextContent(
    val annotated: AnnotatedString,
    val inlineContent: Map<String, InlineTextContent>,
)

/**
 * The comment's text as the server described it. A comment from the older renderer arrives as
 * plain text, so its links, timestamps and hashtags are marked the same way an original
 * description's are.
 */
@Composable
internal fun rememberCommentText(
    comment: Comment,
    actions: RichTextLinkActions,
    accentColor: Color = MaterialTheme.colorScheme.primary,
): CommentTextContent {
    val linkColor = accentColor
    val textColor = MaterialTheme.colorScheme.onSurface
    val richText = remember(comment.richText, comment.text) { comment.richText ?: plainRichText(comment.text) }
    val annotated =
        remember(richText, linkColor, textColor, actions) {
            richText.toAnnotatedString(linkColor = linkColor, textColor = textColor, actions = actions)
        }
    return CommentTextContent(annotated = annotated, inlineContent = rememberRichTextInlineContent(richText))
}

/**
 * The inline images a piece of rich text carries, keyed the way [toAnnotatedString] references them.
 *
 * A custom emoji is drawn a little larger than the text. A site icon inserted before a link sits in
 * a one-em box, YouTube's 14 pt beside its 14 pt text, and fits inside it whatever its proportions.
 */
@Composable
fun rememberRichTextInlineContent(richText: RichText?): Map<String, InlineTextContent> {
    val images = richText?.emojis.orEmpty()
    return remember(images) {
        images.associate { image ->
            RICH_TEXT_EMOJI_PREFIX + image.imageUrl to
                InlineTextContent(
                    placeholder =
                        Placeholder(
                            width = if (image.length == 0) InsertedImageSize else EmojiSize,
                            height = if (image.length == 0) InsertedImageSize else EmojiSize,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                        ),
                ) {
                    AsyncImage(
                        model = image.imageUrl,
                        contentDescription = image.label.takeIf { it.isNotBlank() },
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
        }
    }
}

private val InsertedImageSize = 1.em
