package io.github.aedev.flow.ui.components.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.utils.RICH_TEXT_HIGHLIGHT

/** How strongly a chip tints the surface under it; the same weight YouTube uses. */
const val RICH_TEXT_HIGHLIGHT_ALPHA = 0.12f

private const val HIGHLIGHT_INSET_PX = 1f

/** Room either side of marked text that has none, so a two-digit timestamp is not painted edge to edge. */
private val HighlightHorizontalPadding = 5.dp

/**
 * Paints the rounded tint behind every marked range of [text].
 *
 * The layout arrives as a lambda and is read in the draw phase, so a text that re-measures does not
 * drag the composition along with it. One rounded rect per line a range covers, so a link that
 * wraps keeps a chip on each of its rows rather than one box drawn around both.
 */
@Composable
fun Modifier.richTextHighlights(
    text: AnnotatedString,
    layoutResult: () -> TextLayoutResult?,
    color: Color,
    horizontalPadding: Dp = HighlightHorizontalPadding,
): Modifier {
    val ranges =
        remember(text) {
            text.getStringAnnotations(RICH_TEXT_HIGHLIGHT, 0, text.length).map { it.start to it.end }
        }
    if (ranges.isEmpty()) return this
    return drawBehind {
        val layout = layoutResult() ?: return@drawBehind
        val padding = horizontalPadding.toPx()
        ranges.forEach { (start, end) -> drawHighlight(layout, start, end, color, padding) }
    }
}

private fun DrawScope.drawHighlight(
    layout: TextLayoutResult,
    start: Int,
    end: Int,
    color: Color,
    horizontalPadding: Float,
) {
    if (start >= end || end > layout.layoutInput.text.length) return
    val firstLine = layout.getLineForOffset(start)
    val lastLine = layout.getLineForOffset((end - 1).coerceAtLeast(start))
    for (line in firstLine..lastLine) {
        if (line >= layout.lineCount) break
        val from = maxOf(start, layout.getLineStart(line))
        val to = minOf(end, layout.getLineEnd(line, visibleEnd = true))
        if (from >= to) continue
        val left = layout.getHorizontalPosition(from, usePrimaryDirection = true)
        val right = layout.getHorizontalPosition(to, usePrimaryDirection = true)
        if (right <= left) continue
        val top = layout.getLineTop(line)
        val bottom = layout.getLineBottom(line)
        // The server pads a social link's chip with its own spaces; padding those again draws the
        // chip a space wider than YouTube's on each side.
        val chars = layout.layoutInput.text
        val padLeft = if (chars[from].isWhitespace()) 0f else horizontalPadding
        val padRight = if (chars[to - 1].isWhitespace()) 0f else horizontalPadding
        val boxLeft = (left - padLeft).coerceAtLeast(0f)
        val boxRight = (right + padRight).coerceAtMost(size.width)
        val height = (bottom - top) - HIGHLIGHT_INSET_PX * 2
        drawRoundRect(
            color = color,
            topLeft = Offset(boxLeft, top + HIGHLIGHT_INSET_PX),
            size = Size(boxRight - boxLeft, height),
            cornerRadius = CornerRadius(height / 2f, height / 2f),
        )
    }
}
