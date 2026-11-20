package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aedev.flow.data.model.Comment

@Composable
fun FlowReplyItem(
    reply: Comment,
    onSeekMs: (Long) -> Unit,
    onAuthorClick: (String) -> Unit = {},
    onAvatarClick: (String) -> Unit = {},
    tint: MediaArtworkTint? = null,
) {
    val uriHandler = LocalUriHandler.current
    val accent = tint?.accent ?: MaterialTheme.colorScheme.primary
    val chipColor = tint?.container ?: MaterialTheme.colorScheme.surfaceContainerHighest
    val translation = rememberCommentTranslation(reply)
    val linkActions =
        rememberRichTextLinkActions(
            onSeekMs = onSeekMs,
            onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } },
            onChannel = onAuthorClick,
        )
    val replyText = translation.shown(rememberCommentText(reply, linkActions, accent))
    var replyTextLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var showFullSizeImage by remember { mutableStateOf(false) }

    if (showFullSizeImage) {
        FullSizeImageDialog(
            imageUrl = toHighQualityAvatarUrl(reply.authorThumbnail),
            onDismiss = { showFullSizeImage = false },
        )
    }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
    ) {
        ChannelAvatarImage(
            url = reply.authorThumbnail,
            contentDescription = null,
            modifier =
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable {
                        onAvatarClick(reply.authorThumbnail)
                        showFullSizeImage = true
                    },
        )

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            // Header: Author + Time
            CommentAuthorRow(
                comment = reply,
                onAuthorClick = onAuthorClick,
            )

            Spacer(modifier = Modifier.height(2.dp))

            // Reply Body
            SelectionContainer {
                BasicText(
                    text = replyText.annotated,
                    inlineContent = replyText.inlineContent,
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 16.sp,
                        ),
                    onTextLayout = { replyTextLayoutResult = it },
                    modifier =
                        Modifier
                            .richTextHighlights(
                                text = replyText.annotated,
                                layoutResult = { replyTextLayoutResult },
                                color = chipColor,
                            ),
                )
            }

            CommentTranslateLink(comment = reply, color = accent, state = translation)

            reply.image?.let { FlowCommentImage(image = it, modifier = Modifier.padding(top = 6.dp)) }

            Spacer(modifier = Modifier.height(4.dp))

            // Action Bar (Minimal for replies)
            CommentEngagementRow(comment = reply)
        }
    }
}
