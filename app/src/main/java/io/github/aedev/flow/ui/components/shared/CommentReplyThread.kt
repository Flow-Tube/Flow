package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R

internal val ThreadHorizontalPadding = 16.dp
private val ThreadLineWidth = 2.dp

/** Width of the elbow column: the turn, plus the gap before the reply's avatar. */
private val ThreadConnectorWidth = 20.dp
private val ThreadElbowRadius = 8.dp

/** Where the line turns: the vertical centre of a reply's 24 dp avatar under its 8 dp top padding. */
private val ThreadElbowY = 20.dp

/** A reply to a reply starts its own branch just inside its parent's text. */
private val NestedThreadStart = 12.dp
private val ThreadLineGap = 15.dp

/**
 * The replies under one comment, and the replies to those.
 *
 * Each reply hangs off the thread line on an elbow, the way a conversation branches: the line runs
 * down from the parent's avatar, turns into each reply, and carries on past it to the next one. A
 * reply that answered another reply sits one branch deeper, which is the only thing that tells the
 * two apart — YouTube stores a single flat level and marks the nesting with an `@handle` alone.
 */
@Composable
internal fun ReplyThread(
    nodes: List<CommentReplyNode>,
    hasMore: Boolean,
    startPadding: Dp,
    tint: MediaArtworkTint?,
    onSeekMs: (Long) -> Unit,
    onAuthorClick: (String) -> Unit,
    onAvatarClick: (String) -> Unit,
    onLoadMore: () -> Unit,
) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = startPadding, end = ThreadHorizontalPadding, bottom = 4.dp),
    ) {
        nodes.forEachIndexed { index, node ->
            val continues = index != nodes.lastIndex || hasMore
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                ThreadConnector(
                    color = lineColor,
                    continuesBelow = continues,
                    modifier = Modifier.fillMaxHeight(),
                )
                Column(modifier = Modifier.weight(1f)) {
                    FlowReplyItem(
                        reply = node.comment,
                        onSeekMs = onSeekMs,
                        onAuthorClick = onAuthorClick,
                        onAvatarClick = onAvatarClick,
                        tint = tint,
                    )
                    if (node.children.isNotEmpty()) {
                        ReplyThread(
                            nodes = node.children,
                            hasMore = false,
                            startPadding = NestedThreadStart,
                            tint = tint,
                            onSeekMs = onSeekMs,
                            onAuthorClick = onAuthorClick,
                            onAvatarClick = onAvatarClick,
                            onLoadMore = onLoadMore,
                        )
                    }
                }
            }
        }

        if (hasMore) {
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                ThreadConnector(
                    color = lineColor,
                    continuesBelow = false,
                    modifier = Modifier.fillMaxHeight(),
                )
                Text(
                    text = stringResource(R.string.load_more_replies),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier =
                        Modifier
                            .padding(start = ThreadLineGap)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(onClick = onLoadMore)
                            .padding(vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * The line into one reply: down from above, a quarter turn towards the text, and on down to the
 * next reply when there is one.
 */
@Composable
private fun ThreadConnector(
    color: Color,
    continuesBelow: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.width(ThreadConnectorWidth)) {
        val stroke = ThreadLineWidth.toPx()
        val elbowY = ThreadElbowY.toPx()
        val radius = ThreadElbowRadius.toPx()
        val x = stroke / 2f

        drawLine(
            color = color,
            start = Offset(x, 0f),
            end = Offset(x, (elbowY - radius).coerceAtLeast(0f)),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = -90f,
            useCenter = false,
            topLeft = Offset(x, elbowY - 2 * radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawLine(
            color = color,
            start = Offset(x + radius, elbowY),
            end = Offset(size.width, elbowY),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        if (continuesBelow) {
            drawLine(
                color = color,
                start = Offset(x, (elbowY - radius).coerceAtLeast(0f)),
                end = Offset(x, size.height),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** "12 replies" with the chevron that says it opens, in place of the rule that used to sit here. */
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
