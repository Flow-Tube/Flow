package io.github.aedev.flow.ui.components.musicplayer.lyrics

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.data.lyrics.WordTimestamp
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

private val WordLift = 3.dp
private const val FEATHER_EM = 0.9f
private const val HELD_NOTE_MS = 1_100L
private const val GLOW_LEVELS = 64
private const val GLOW_PEAK_ALPHA = 0.5f
private val GlowRadius = 10.dp

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

/**
 * Which prepared glow strength a held word shows at [progress] through it, or -1 for none. It rises
 * and falls with the word on one smooth curve, in steps too small to see.
 */
internal fun heldNoteGlowLevel(progress: Float): Int {
    if (progress <= 0f || progress >= 1f) return -1
    return (sin(progress * PI).toFloat() * GLOW_LEVELS).toInt().coerceIn(0, GLOW_LEVELS - 1)
}

/** How far the lift has travelled [elapsedMs] after a word starts: an underdamped spring's step response. */
internal fun wordLiftProgress(elapsedMs: Long): Float {
    if (elapsedMs <= 0) return 0f
    val t = elapsedMs / 1000.0
    val zeta = 0.55
    val omega = 2 * PI / 0.42
    val damped = omega * sqrt(1 - zeta * zeta)
    val decay = exp(-zeta * omega * t)
    return (1 - decay * (cos(damped * t) + zeta / sqrt(1 - zeta * zeta) * sin(damped * t))).toFloat()
}

/** A gradient shader made once and slid into place for each frame through its local matrix. */
private class FrontBrush(
    private val shader: android.graphics.LinearGradient,
) : ShaderBrush() {
    private val matrix = android.graphics.Matrix()

    fun moveTo(x: Float) {
        matrix.setTranslate(x, 0f)
        shader.setLocalMatrix(matrix)
    }

    override fun createShader(size: Size): Shader = shader
}

private fun frontBrush(
    feather: Float,
    from: Color,
    to: Color,
) = FrontBrush(
    android.graphics.LinearGradient(
        -feather / 2f,
        0f,
        feather / 2f,
        0f,
        from.toArgb(),
        to.toArgb(),
        android.graphics.Shader.TileMode.CLAMP,
    ),
)

/**
 * One lyric line sung word by word. Every layer is drawn from a single text layout, so the lit and
 * unlit parts always match: a word fills behind a soft gradient front, lifts a few dp on a spring
 * as it starts and settles, and long held notes glow while they last. Nothing is allocated per
 * frame; only the active line runs a frame loop, and only while music plays.
 */
@Composable
internal fun KaraokeLine(
    text: String,
    words: List<WordTimestamp>,
    style: TextStyle,
    sungColor: Color,
    unsungColor: Color,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isBackground: Boolean,
    syncOffsetMs: Long,
    positionProvider: () -> Long,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val latestOffset by rememberUpdatedState(syncOffsetMs)
    val smoothPosition = remember { mutableLongStateOf(Long.MIN_VALUE) }
    // Plain holder, written from draw: the last position the line was drawn at while current.
    val lastSungPosition = remember { LongArray(1) { Long.MIN_VALUE } }
    val lineLift = remember { Animatable(0f) }

    LaunchedEffect(isCurrent, isPlaying) {
        if (!isCurrent || !isPlaying) {
            smoothPosition.longValue = Long.MIN_VALUE
            return@LaunchedEffect
        }
        val clock = LyricsFillClock()
        while (coroutineContext.isActive) {
            withFrameMillis { frameMs ->
                val position =
                    clock.next(
                        playerPositionMs = EnhancedMusicPlayerManager.getCurrentPosition(),
                        frameTimeMs = frameMs,
                        speed = EnhancedMusicPlayerManager.playbackSpeed.value,
                    )
                smoothPosition.longValue = position + latestOffset
            }
        }
    }
    LaunchedEffect(isCurrent, motionEnabled) {
        lineLift.animateTo(
            targetValue = if (isCurrent && motionEnabled) 1f else 0f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        )
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val widthPx = constraints.maxWidth
        val layout =
            remember(text, widthPx, style) {
                measurer.measure(
                    text = text,
                    style = style,
                    constraints = Constraints(minWidth = widthPx, maxWidth = widthPx),
                )
            }
        val ranges = remember(text, words, isBackground) { wordCharRanges(text, words, isBackground) }
        val geometry = remember(layout, words, ranges) { buildKaraokeGeometry(measurer, layout, style, words, ranges) }
        val fontPx = with(density) { style.fontSize.toPx() }
        val feather = fontPx * FEATHER_EM
        val liftPx = with(density) { WordLift.toPx() }
        val glowPx = with(density) { GlowRadius.toPx() }
        val forward = remember(sungColor, unsungColor, feather) { frontBrush(feather, sungColor, unsungColor) }
        val backward = remember(sungColor, unsungColor, feather) { frontBrush(feather, unsungColor, sungColor) }
        val glows =
            remember(sungColor, glowPx) {
                // Only the strength moves. A changing blur radius is what made the glow visibly step.
                Array(GLOW_LEVELS) { level ->
                    Shadow(color = sungColor.copy(alpha = GLOW_PEAK_ALPHA * (level + 1f) / GLOW_LEVELS), blurRadius = glowPx)
                }
            }

        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(with(density) { layout.size.height.toDp() })
                    .semantics { this.text = AnnotatedString(text) },
        ) {
            val position =
                when {
                    !isCurrent -> Long.MIN_VALUE
                    smoothPosition.longValue != Long.MIN_VALUE -> smoothPosition.longValue
                    else -> positionProvider()
                }
            if (position != Long.MIN_VALUE) lastSungPosition[0] = position
            val lift = lineLift.value
            if (!isCurrent && lift == 0f) {
                drawText(layout, color = unsungColor)
                return@Canvas
            }
            // Words keep their lift while an ended line settles back, but their fill turns unsung.
            val liftClock = if (isCurrent) position else lastSungPosition[0]
            // The lift's spring overshoots, so the clip has to reach past its resting height.
            val overscan = liftPx * 2f
            geometry.cells.forEach { cell ->
                val row = geometry.rows[cell.row]
                val word = geometry.words.getOrNull(cell.word)
                val progress =
                    when {
                        !isCurrent -> 0f
                        word == null -> if (position >= (geometry.words.getOrNull(cell.wordBefore)?.endMs ?: Long.MAX_VALUE)) 1f else 0f
                        position < word.startMs -> 0f
                        position >= word.endMs -> 1f
                        else -> (position - word.startMs).toFloat() / (word.endMs - word.startMs).coerceAtLeast(1)
                    }
                val cellProgress = ((progress - cell.startFraction) / cell.widthFraction).coerceIn(0f, 1f)
                val wordLift =
                    if (word == null || liftClock == Long.MIN_VALUE) 0f else liftPx * lift * wordLiftProgress(liftClock - word.startMs)
                val front =
                    if (cell.rtl) {
                        cell.inkRight - (cell.inkRight - cell.inkLeft) * cellProgress
                    } else {
                        cell.inkLeft + (cell.inkRight - cell.inkLeft) * cellProgress
                    }
                val glowLayout = cell.glow
                val glowLevel = heldNoteGlowLevel(progress)
                if (motionEnabled && glowLayout != null && glowLevel >= 0) {
                    translate(
                        left = cell.inkLeft - glowLayout.getBoundingBox(0).left,
                        top = row.dy + row.layout.firstBaseline - glowLayout.firstBaseline - wordLift,
                    ) {
                        // The word alone can shape differently from its row, so only its blur is drawn.
                        drawText(glowLayout, color = Color.Transparent, shadow = glows[glowLevel])
                    }
                }
                clipRect(left = cell.clipLeft, top = -overscan, right = cell.clipRight, bottom = size.height + overscan) {
                    translate(top = row.dy - wordLift) {
                        when {
                            cellProgress >= 1f -> {
                                drawText(row.layout, color = sungColor)
                            }

                            cellProgress <= 0f -> {
                                drawText(row.layout, color = unsungColor)
                            }

                            else -> {
                                val brush = if (cell.rtl) backward else forward
                                brush.moveTo(front)
                                // The layout's paint keeps the alpha of the last colour drawn with it.
                                drawText(row.layout, brush = brush, alpha = 1f)
                            }
                        }
                    }
                }
            }
        }
    }
}
