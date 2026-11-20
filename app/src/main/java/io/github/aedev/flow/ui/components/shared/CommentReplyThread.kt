package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Comment

/** How far a label row's button reaches back so its text, not its edge, lines up with the column. */
private val ThreadLabelInset = 8.dp

/**
 * The replies under one comment, drawn as the conversation they are.
 *
 * Every reply hangs off the line that drops from the avatar it answered, one avatar column further
 * in. YouTube sends a single flat level, so who answered whom comes from the leading `@handle`
 * (see [buildCommentReplyTree]); the tree is rebuilt whenever another page of replies arrives.
 */
@Composable
internal fun ReplyThread(
    replies: List<Comment>,
    hasMore: Boolean,
    loadingMore: Boolean,
    isUnfolded: (String) -> Boolean,
    onUnfold: (String) -> Unit,
    tint: MediaArtworkTint?,
    onSeekMs: (Long) -> Unit,
    onAuthorClick: (String) -> Unit,
    onAvatarClick: (String) -> Unit,
    onLoadMore: () -> Unit,
) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val tree = remember(replies) { buildCommentReplyTree(replies) }
    // Laid out in composition so that unfolding a sub-thread, a state read, lays the rows out again.
    val rows = commentThreadRows(tree, hasMore, isUnfolded)
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        rows.forEach { row ->
            key(row.key) {
                val lines = row.lines
                when (row) {
                    is CommentThreadRow.Reply -> {
                        FlowReplyItem(
                            reply = row.comment,
                            onSeekMs = onSeekMs,
                            onAuthorClick = onAuthorClick,
                            onAvatarClick = onAvatarClick,
                            tint = tint,
                            modifier =
                                Modifier
                                    .commentThreadLines(lines, lineColor)
                                    .padding(start = threadContentStart(lines.column), end = ThreadHorizontalPadding),
                        )
                    }

                    is CommentThreadRow.Folded -> {
                        ThreadLabelRow(
                            lines = lines,
                            lineColor = lineColor,
                            text = pluralStringResource(R.plurals.comment_folded_replies, row.count, row.count),
                            icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            onClick = { onUnfold(row.parentId) },
                        )
                    }

                    is CommentThreadRow.MoreReplies -> {
                        ThreadLabelRow(
                            lines = lines,
                            lineColor = lineColor,
                            text = stringResource(R.string.load_more_replies),
                            icon = Icons.Rounded.KeyboardArrowDown,
                            loading = loadingMore,
                            onClick = onLoadMore,
                        )
                    }
                }
            }
        }
    }
}

/** "3 replies >" or "Load more replies", hanging off the thread line where a reply would. */
@Composable
private fun ThreadLabelRow(
    lines: CommentThreadLines,
    lineColor: Color,
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    loading: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .commentThreadLines(lines, lineColor, elbowAtCentre = true)
                .padding(start = threadContentStart(lines.column) - ThreadLabelInset, end = ThreadHorizontalPadding),
    ) {
        TextButton(
            onClick = onClick,
            contentPadding = PaddingValues(horizontal = ThreadLabelInset),
        ) {
            Text(text = text, style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.width(4.dp))
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** "12 replies" with the chevron that says it opens. */
@Composable
internal fun RepliesToggle(
    replyCount: Int,
    expanded: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .padding(top = 4.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Icon(
            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text =
                if (expanded) {
                    stringResource(R.string.hide_replies)
                } else {
                    pluralStringResource(R.plurals.view_replies_template, replyCount, replyCount)
                },
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        if (loading) {
            Spacer(modifier = Modifier.width(8.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
