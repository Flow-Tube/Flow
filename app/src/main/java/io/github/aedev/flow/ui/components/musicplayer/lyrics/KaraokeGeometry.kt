package io.github.aedev.flow.ui.components.musicplayer.lyrics

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import io.github.aedev.flow.data.lyrics.WordTimestamp
import java.text.BreakIterator

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
    val letters: Array<KaraokeLetter>? = null,
) {
    var clipLeft = 0f
    var clipRight = 0f
}

/**
 * One letter of a cell, for words that rise letter by letter. [from] and [to] are where its ink
 * starts and ends in the word's reading order, as shares of the word, like the fill front's progress.
 * While letters rise at different heights each is drawn from its own [layout], placed at [dx] and
 * [dy] in its row: a cut through the row would leave a kerned neighbour's overhang behind.
 */
internal class KaraokeLetter(
    val inkLeft: Float,
    val from: Float,
    val to: Float,
    val layout: TextLayoutResult,
    val dx: Float,
    val dy: Float,
)

/**
 * A timed word. A syllable of a hyphenated run sung apart ("so-o-o") knows its place [group] in the
 * run, the run's [groupSize] and when the run ends; any other word has a [group] of -1.
 */
internal class KaraokeWord(
    val startMs: Long,
    val endMs: Long,
) {
    var group = -1
    var groupSize = 0
    var groupEndMs = 0L
    var inkWidth = 1f
}

/**
 * Where every word of a laid-out line sits; built once per layout, read every frame. [rowOrder]
 * lists each row's cells from left to right. [cellDx], [cellScale] and [wordScale] are scratch
 * space a Cadence frame fills in, so drawing a frame allocates nothing.
 */
internal class KaraokeGeometry(
    val rows: List<KaraokeRow>,
    val words: List<KaraokeWord>,
    val cells: List<KaraokeCell>,
    val rowOrder: List<IntArray> = emptyList(),
) {
    val cellDx = FloatArray(cells.size)
    val cellScale = FloatArray(cells.size)
    val wordScale = FloatArray(words.size)
}

internal fun buildKaraokeGeometry(
    measurer: TextMeasurer,
    full: TextLayoutResult,
    style: TextStyle,
    words: List<WordTimestamp>,
    ranges: List<IntRange?>,
    splitLetters: Boolean = false,
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
        placedWords[wordIndex].inkWidth = total
        var cursor = 0f
        parts.forEachIndexed { i, (row, first, last) ->
            val fraction = (inks[i][1] - inks[i][0]) / total
            val rtl = rows[row].layout.getBidiRunDirection(first) == ResolvedTextDirection.Rtl
            val rowText =
                rows[row]
                    .layout.layoutInput.text.text
            val letters =
                if (splitLetters && isLetterSplittable(rowText.substring(first, last + 1))) {
                    buildLetters(measurer, glowStyle, rows[row].layout, first, last, inks[i][0], inks[i][1], rtl, cursor, fraction)
                } else {
                    null
                }
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
                    rtl = rtl,
                    startFraction = cursor,
                    widthFraction = fraction,
                    glow = glow,
                    letters = letters,
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
    markHyphenRuns(placed.map { words[it].text.trim() }, placed.map { ranges[it]!! }, placedWords)
    val rowOrder =
        rows.indices.map { row ->
            cells.indices
                .filter { cells[it].row == row }
                .sortedBy { cells[it].inkLeft }
                .toIntArray()
        }
    return KaraokeGeometry(rows, placedWords, cells, rowOrder)
}

/**
 * Syllables of one hyphenated word sung apart ("so-", "o-", "o" written "so-o-o") follow each other
 * with no space between them; each learns its place in the run so it can grow a step on the last.
 */
internal fun markHyphenRuns(
    texts: List<String>,
    ranges: List<IntRange>,
    words: List<KaraokeWord>,
) {
    var start = 0
    while (start < words.size) {
        var end = start
        while (end + 1 < words.size && texts[end].endsWith('-') && ranges[end + 1].first == ranges[end].last + 1) end++
        if (end > start) {
            for (k in start..end) {
                words[k].group = k - start
                words[k].groupSize = end - start + 1
                words[k].groupEndMs = words[end].endMs
            }
        }
        start = end + 1
    }
}

/**
 * Scripts whose letters can be lifted one at a time: each one is a glyph of its own. Joined and
 * shaped scripts (Arabic, the Indic family, Thai and the like) are left out, because a cut between
 * two of their letters would show as a break in the line that joins them.
 */
private val LetterScripts =
    setOf(
        Character.UnicodeScript.LATIN,
        Character.UnicodeScript.GREEK,
        Character.UnicodeScript.CYRILLIC,
        Character.UnicodeScript.ARMENIAN,
        Character.UnicodeScript.GEORGIAN,
        Character.UnicodeScript.HEBREW,
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL,
        Character.UnicodeScript.BOPOMOFO,
        Character.UnicodeScript.COMMON,
        Character.UnicodeScript.INHERITED,
    )

/** Whether [word] can rise letter by letter: more than one letter, all in scripts that don't join. */
internal fun isLetterSplittable(word: String): Boolean =
    letterStarts(word).size > 1 && word.codePoints().allMatch { Character.UnicodeScript.of(it) in LetterScripts }

/** Ligatures fonts draw for an "f" and what follows it, which a cut between the two would tear. */
private const val F_LIGATURE_FOLLOWERS = "fijlt"

/**
 * Where each letter of [word] starts: whole grapheme clusters, so an accent stays with its letter,
 * with an "f" kept together with a letter it may form a ligature with.
 */
internal fun letterStarts(word: String): IntArray {
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(word)
    val starts = mutableListOf<Int>()
    var start = iterator.first()
    var next = iterator.next()
    while (next != BreakIterator.DONE) {
        val previous = starts.lastOrNull()
        val joinsLigature = previous != null && word[start - 1] == 'f' && word[start] in F_LIGATURE_FOLLOWERS
        if (!joinsLigature) starts += start
        start = next
        next = iterator.next()
    }
    return starts.toIntArray()
}

private fun buildLetters(
    measurer: TextMeasurer,
    style: TextStyle,
    layout: TextLayoutResult,
    first: Int,
    last: Int,
    inkLeft: Float,
    inkRight: Float,
    rtl: Boolean,
    startFraction: Float,
    widthFraction: Float,
): Array<KaraokeLetter> {
    val starts =
        letterStarts(
            layout.layoutInput.text.text
                .substring(first, last + 1),
        )
    val width = (inkRight - inkLeft).coerceAtLeast(1f)
    val letters =
        starts.indices.map { k ->
            val from = first + starts[k]
            val to = if (k == starts.lastIndex) last else first + starts[k + 1] - 1
            val ink = inkOf(layout, from, to)
            val near = if (rtl) (inkRight - ink[1]) / width else (ink[0] - inkLeft) / width
            val far = if (rtl) (inkRight - ink[0]) / width else (ink[1] - inkLeft) / width
            val glyph =
                measurer.measure(
                    layout.layoutInput.text.text
                        .substring(from, to + 1),
                    style,
                )
            KaraokeLetter(
                inkLeft = ink[0],
                from = startFraction + widthFraction * near,
                to = startFraction + widthFraction * far,
                layout = glyph,
                dx = ink[0] - inkOf(glyph, 0, to - from)[0],
                dy = layout.firstBaseline - glyph.firstBaseline,
            )
        }
    return letters.sortedBy { it.inkLeft }.toTypedArray()
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
