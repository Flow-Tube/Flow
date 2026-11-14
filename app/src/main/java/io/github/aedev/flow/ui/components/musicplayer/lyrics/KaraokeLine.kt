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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.data.lyrics.WordTimestamp
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

private val WordLift = 3.dp
private const val FEATHER_EM = 0.9f
private const val GLOW_PEAK_ALPHA = 0.5f
private val GlowRadius = 10.dp

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
 * A translucent unsung colour can't be drawn glyph by glyph: where glyphs overlap, as Indic letters
 * do along their headline, the overlap takes the alpha twice and shows as light seams (#1484, #2028).
 * Such a line draws its unsung text opaque in one layer that carries the alpha instead.
 */
internal fun needsUnsungLayer(unsungColor: Color): Boolean = unsungColor.alpha < 1f

private inline fun DrawScope.withAlphaLayer(
    alpha: Float,
    overscan: Float,
    block: DrawScope.() -> Unit,
) {
    val canvas = drawContext.canvas.nativeCanvas
    val saved = canvas.saveLayerAlpha(0f, -overscan, size.width, size.height + overscan, (alpha * 255f).roundToInt())
    try {
        block()
    } finally {
        canvas.restoreToCount(saved)
    }
}

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
        val layered = needsUnsungLayer(unsungColor)
        val solidUnsung = unsungColor.copy(alpha = 1f)
        // Layered, the unsung text is already under the front, so the front fades the sung colour out.
        val frontEnd = if (layered) sungColor.copy(alpha = 0f) else unsungColor
        val forward = remember(sungColor, frontEnd, feather) { frontBrush(feather, sungColor, frontEnd) }
        val backward = remember(sungColor, frontEnd, feather) { frontBrush(feather, frontEnd, sungColor) }
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
            // The lift's spring overshoots, so the clip has to reach past its resting height.
            val overscan = liftPx * 2f
            if (!isCurrent && lift == 0f) {
                if (layered) {
                    withAlphaLayer(unsungColor.alpha, overscan) { drawText(layout, color = solidUnsung) }
                } else {
                    drawText(layout, color = unsungColor)
                }
                return@Canvas
            }
            // Words keep their lift while an ended line settles back, but their fill turns unsung.
            val liftClock = if (isCurrent) position else lastSungPosition[0]
            if (layered) {
                withAlphaLayer(unsungColor.alpha, overscan) {
                    geometry.cells.forEach { cell ->
                        val word = geometry.words.getOrNull(cell.word)
                        val progress = wordFillProgress(geometry, cell, word, position, isCurrent)
                        if (cellFillProgress(cell, progress) >= 1f) return@forEach
                        val wordLift = wordLiftPx(word, liftClock, liftPx, lift)
                        clipRect(left = cell.clipLeft, top = -overscan, right = cell.clipRight, bottom = size.height + overscan) {
                            translate(top = geometry.rows[cell.row].dy - wordLift) {
                                drawText(geometry.rows[cell.row].layout, color = solidUnsung)
                            }
                        }
                    }
                }
            }
            geometry.cells.forEach { cell ->
                val row = geometry.rows[cell.row]
                val word = geometry.words.getOrNull(cell.word)
                val progress = wordFillProgress(geometry, cell, word, position, isCurrent)
                val cellProgress = cellFillProgress(cell, progress)
                val wordLift = wordLiftPx(word, liftClock, liftPx, lift)
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
                                if (!layered) drawText(row.layout, color = unsungColor)
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
