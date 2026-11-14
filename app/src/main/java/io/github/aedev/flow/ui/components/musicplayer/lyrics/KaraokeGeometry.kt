package io.github.aedev.flow.ui.components.musicplayer.lyrics

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import io.github.aedev.flow.data.lyrics.WordTimestamp

private const val HELD_NOTE_MS = 1_100L

/** One visual row of the line, laid out alone so a cell can be drawn without its neighbours' ink. */
internal class KaraokeRow(
    val layout: TextLayoutResult,
    val dy: Float,
)

/**
 * A horizontal slice of one row: a word's part on that row, or punctuation no word covers. The
 * slices of a row meet halfway between their ink and never overlap, so every glyph pixel is drawn
 * exactly once. [glow] is the word laid out alone, for held notes that glow while sung.
 */
internal class KaraokeCell(
    val row: Int,
    val word: Int,
    val wordBefore: Int,
    val inkLeft: Float,
    val inkRight: Float,
    val rtl: Boolean,
    val startFraction: Float,
    val widthFraction: Float,
    val glow: TextLayoutResult?,
) {
    var clipLeft = 0f
    var clipRight = 0f
}

internal class KaraokeWord(
    val startMs: Long,
    val endMs: Long,
)

/** Where every word of a laid-out line sits; built once per layout, read every frame. */
internal class KaraokeGeometry(
    val rows: List<KaraokeRow>,
    val words: List<KaraokeWord>,
    val cells: List<KaraokeCell>,
)

internal fun buildKaraokeGeometry(
    measurer: TextMeasurer,
    full: TextLayoutResult,
    style: TextStyle,
    words: List<WordTimestamp>,
    ranges: List<IntRange?>,
): KaraokeGeometry {
    val text = full.layoutInput.text.text
    val width = full.layoutInput.constraints.maxWidth
    val rowSpans = (0 until full.lineCount).map { full.getLineStart(it) until full.getLineEnd(it, visibleEnd = true) }
    val rows =
        rowSpans.mapIndexed { index, span ->
            val layout =
                measurer.measure(
                    text = if (span.isEmpty()) " " else text.substring(span.first, span.last + 1),
                    style = style,
                    maxLines = 1,
                    constraints = Constraints(minWidth = width, maxWidth = width),
                )
            KaraokeRow(layout, full.getLineBaseline(index) - layout.firstBaseline)
        }

    val placed = words.indices.filter { ranges.getOrNull(it) != null }
    val placedWords = placed.map { KaraokeWord(words[it].startTime, words[it].endTime) }
    val glowStyle = style.copy(textAlign = TextAlign.Start)
    val cells = mutableListOf<KaraokeCell>()
    placed.forEachIndexed { wordIndex, original ->
        val range = ranges[original]!!
        val held = words[original].endTime - words[original].startTime >= HELD_NOTE_MS
        val parts =
            rowSpans.indices.mapNotNull { row ->
                val span = rowSpans[row]
                val first = maxOf(range.first, span.first)
                val last = minOf(range.last, span.last)
                if (first > last) null else Triple(row, first - span.first, last - span.first)
            }
        val inks = parts.map { (row, first, last) -> inkOf(rows[row].layout, first, last) }
        val total = inks.sumOf { (it[1] - it[0]).toDouble() }.toFloat().coerceAtLeast(1f)
        var cursor = 0f
        parts.forEachIndexed { i, (row, first, last) ->
            val fraction = (inks[i][1] - inks[i][0]) / total
            val glow =
                if (held) {
                    measurer.measure(
                        text =
                            rows[row]
                                .layout.layoutInput.text.text
                                .substring(first, last + 1),
                        style = glowStyle,
                    )
                } else {
                    null
                }
            cells +=
                KaraokeCell(
                    row = row,
                    word = wordIndex,
                    wordBefore = wordIndex,
                    inkLeft = inks[i][0],
                    inkRight = inks[i][1],
                    rtl = rows[row].layout.getBidiRunDirection(first) == ResolvedTextDirection.Rtl,
                    startFraction = cursor,
                    widthFraction = fraction,
                    glow = glow,
                )
            cursor += fraction
        }
    }

    val covered = BooleanArray(text.length)
    placed.forEach { original -> ranges[original]!!.forEach { if (it in covered.indices) covered[it] = true } }
    val placedStarts = placed.map { ranges[it]!!.first }
    rowSpans.forEachIndexed { row, span ->
        var i = span.first
        while (i <= span.last) {
            if (covered[i] || text[i].isWhitespace()) {
                i++
                continue
            }
            var end = i
            while (end + 1 <= span.last && !covered[end + 1] && !text[end + 1].isWhitespace()) end++
            val ink = inkOf(rows[row].layout, i - span.first, end - span.first)
            val before = placedStarts.count { it <= i } - 1
            cells += KaraokeCell(row, -1, before, ink[0], ink[1], false, 0f, 1f, null)
            i = end + 1
        }
    }

    cells.groupBy { it.row }.values.forEach { rowCells ->
        val sorted = rowCells.sortedBy { it.inkLeft }
        val bounds = rowCellBounds(sorted.map { it.inkLeft }, sorted.map { it.inkRight }, width.toFloat())
        sorted.forEachIndexed { i, cell ->
            cell.clipLeft = bounds[i * 2]
            cell.clipRight = bounds[i * 2 + 1]
        }
    }
    return KaraokeGeometry(rows, placedWords, cells)
}

/**
 * Clip bounds for a row's cells, sorted by their left ink edge, as left and right pairs: each
 * boundary sits halfway between neighbours' ink, and the outer cells reach well past the row.
 */
internal fun rowCellBounds(
    inkLefts: List<Float>,
    inkRights: List<Float>,
    width: Float,
): FloatArray {
    val bounds = FloatArray(inkLefts.size * 2)
    for (i in inkLefts.indices) {
        bounds[i * 2] = if (i == 0) -width else (inkRights[i - 1] + inkLefts[i]) / 2f
        bounds[i * 2 + 1] = if (i == inkLefts.lastIndex) width * 2f else (inkRights[i] + inkLefts[i + 1]) / 2f
    }
    return bounds
}

private fun inkOf(
    layout: TextLayoutResult,
    first: Int,
    last: Int,
): FloatArray {
    val a = layout.getBoundingBox(first)
    val b = layout.getBoundingBox(last)
    return floatArrayOf(minOf(a.left, b.left), maxOf(a.right, b.right))
}
