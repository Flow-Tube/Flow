package io.github.aedev.flow.ui.components.shared

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

internal val ThreadHorizontalPadding = 16.dp
internal val CommentAvatarSize = 40.dp
internal val CommentAvatarGap = 12.dp
internal val CommentRowTopPadding = 12.dp
internal val ReplyAvatarSize = 24.dp
internal val ReplyAvatarGap = 8.dp
internal val ReplyRowVerticalPadding = 8.dp

private val CommentThreadLineWidth = 1.5.dp
private val ThreadElbowRadius = 8.dp

/** The space the elbow leaves before the avatar or label it turns into. */
private val ThreadElbowGap = 4.dp

private val ReplyElbowY = ReplyRowVerticalPadding + ReplyAvatarSize / 2
private val ReplyAvatarBottom = ReplyRowVerticalPadding + ReplyAvatarSize
private val CommentAvatarBottom = CommentRowTopPadding + CommentAvatarSize

/** Where a row in [column] starts its avatar or label: one reply avatar and its gap per step. */
internal fun threadContentStart(column: Int): Dp =
    if (column == 0) {
        ThreadHorizontalPadding
    } else {
        ThreadHorizontalPadding + CommentAvatarSize + CommentAvatarGap + (ReplyAvatarSize + ReplyAvatarGap) * (column - 1)
    }

/** The line of [column] runs down the centre of that column's avatar. */
private fun threadLineX(column: Int): Dp = threadContentStart(column) + if (column == 0) CommentAvatarSize / 2 else ReplyAvatarSize / 2

/**
 * Draws one row's share of the thread lines behind it. Apply it before any padding: the columns are
 * measured from the comment's own start edge.
 *
 * [elbowAtCentre] turns the elbow at the row's middle, for a label row, instead of at the centre of
 * a reply avatar.
 */
internal fun Modifier.commentThreadLines(
    lines: CommentThreadLines,
    color: Color,
    elbowAtCentre: Boolean = false,
): Modifier =
    drawWithCache {
        val radius = ThreadElbowRadius.toPx()
        val elbowY = if (elbowAtCentre) size.height / 2f else ReplyElbowY.toPx()
        val fromX = threadLineX(lines.elbowFrom).toPx()
        val path =
            Path().apply {
                lines.through.forEach { column ->
                    val x = threadLineX(column).toPx()
                    moveTo(x, 0f)
                    lineTo(x, size.height)
                }
                moveTo(fromX, 0f)
                lineTo(fromX, elbowY - radius)
                quadraticTo(fromX, elbowY, fromX + radius, elbowY)
                lineTo((threadContentStart(lines.column) - ThreadElbowGap).toPx(), elbowY)
                lines.ownLine?.let { column ->
                    val x = threadLineX(column).toPx()
                    moveTo(x, ReplyAvatarBottom.toPx())
                    lineTo(x, size.height)
                }
            }
        val stroke = Stroke(width = CommentThreadLineWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val mirror = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
        onDrawBehind {
            scale(scaleX = mirror, scaleY = 1f) { drawPath(path, color, style = stroke) }
        }
    }

/** The line from a top-level comment's avatar down into its open replies. */
internal fun Modifier.commentThreadStem(color: Color): Modifier =
    drawWithCache {
        val x = threadLineX(0).toPx()
        val top = CommentAvatarBottom.toPx()
        val width = CommentThreadLineWidth.toPx()
        val mirror = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
        onDrawBehind {
            scale(scaleX = mirror, scaleY = 1f) {
                drawLine(color, start = Offset(x, top), end = Offset(x, size.height), strokeWidth = width, cap = StrokeCap.Round)
            }
        }
    }
