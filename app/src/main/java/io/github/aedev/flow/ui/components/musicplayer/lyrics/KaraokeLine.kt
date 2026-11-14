package io.github.aedev.flow.ui.components.musicplayer.lyrics

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.data.local.LyricsAnimationStyle
import io.github.aedev.flow.data.local.LyricsGlow
import io.github.aedev.flow.data.lyrics.WordTimestamp
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

private val WordLift = 3.dp
private const val FEATHER_EM = 0.9f
private const val SOFT_GLOW_ALPHA = 0.3f
private const val NORMAL_GLOW_ALPHA = 0.5f
private const val BRIGHT_GLOW_ALPHA = 0.8f
private val SoftGlowRadius = 8.dp
private val NormalGlowRadius = 10.dp
private val BrightGlowRadius = 14.dp

private fun glowPeakAlpha(glow: LyricsGlow): Float =
    when (glow) {
        LyricsGlow.OFF -> 0f
        LyricsGlow.SOFT -> SOFT_GLOW_ALPHA
        LyricsGlow.NORMAL -> NORMAL_GLOW_ALPHA
        LyricsGlow.BRIGHT -> BRIGHT_GLOW_ALPHA
    }

private fun glowRadius(glow: LyricsGlow) =
    when (glow) {
        LyricsGlow.SOFT -> SoftGlowRadius
        LyricsGlow.BRIGHT -> BrightGlowRadius
        else -> NormalGlowRadius
    }

/** A gradient shader made once and slid into place for each frame through its local matrix. */
internal class FrontBrush(
    private val shader: android.graphics.LinearGradient,
) : ShaderBrush() {
    private val matrix = android.graphics.Matrix()

    /** Where the front was last moved to. */
    var x = 0f
        private set

    fun moveTo(x: Float) {
        this.x = x
        matrix.setTranslate(x, 0f)
        shader.setLocalMatrix(matrix)
    }

    override fun createShader(size: Size): Shader = shader
}

internal fun frontBrush(
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

internal inline fun DrawScope.withAlphaLayer(
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
 * as it starts and settles, and long held notes glow while they last. [animationStyle] Cadence
 * moves letters instead (see [drawCadenceLine]); Calm keeps only the fill. Nothing is allocated per
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
    animationStyle: LyricsAnimationStyle = LyricsAnimationStyle.CLASSIC,
    glow: LyricsGlow = LyricsGlow.NORMAL,
) {
    val moving = motionEnabled && animationStyle != LyricsAnimationStyle.CALM
    val cadence = motionEnabled && animationStyle == LyricsAnimationStyle.CADENCE
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val latestOffset by rememberUpdatedState(syncOffsetMs)
    val smoothPosition = remember { mutableLongStateOf(Long.MIN_VALUE) }
    // Plain holder, written from draw: the last position the line was drawn at while current.
    val lastSungPosition = remember { LongArray(1) { Long.MIN_VALUE } }
    val lineLift = remember { Animatable(0f) }
    // 1 when the line is not letting go: Cadence runs it from 0 once the next line starts.
    val exit = remember { Animatable(1f) }
    val wasCurrent = remember { BooleanArray(1) }

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
    LaunchedEffect(isCurrent, cadence) {
        val letGo = cadence && !isCurrent && wasCurrent[0]
        wasCurrent[0] = isCurrent
        if (!letGo) {
            exit.snapTo(1f)
            return@LaunchedEffect
        }
        exit.snapTo(0f)
        exit.animateTo(1f, tween(CADENCE_EXIT_MS, easing = LinearEasing))
    }
    LaunchedEffect(isCurrent, moving) {
        lineLift.animateTo(
            targetValue = if (isCurrent && moving) 1f else 0f,
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
        val splitLetters = cadence && !isBackground
        val geometry =
            remember(layout, words, ranges, splitLetters) {
                buildKaraokeGeometry(measurer, layout, style, words, ranges, splitLetters)
            }
        val fontPx = with(density) { style.fontSize.toPx() }
        val feather = fontPx * FEATHER_EM
        val liftPx = with(density) { WordLift.toPx() }
        val glowPx = with(density) { glowRadius(glow).toPx() }
        val glowAlpha = glowPeakAlpha(glow)
        val layered = needsUnsungLayer(unsungColor)
        val solidUnsung = unsungColor.copy(alpha = 1f)
        // Layered, the unsung text is already under the front, so the front fades the sung colour out.
        val frontEnd = if (layered) sungColor.copy(alpha = 0f) else unsungColor
        val forward = remember(sungColor, frontEnd, feather) { frontBrush(feather, sungColor, frontEnd) }
        val backward = remember(sungColor, frontEnd, feather) { frontBrush(feather, frontEnd, sungColor) }
        val glows =
            remember(sungColor, glowPx, glowAlpha) {
                // Only the strength moves. A changing blur radius is what made the glow visibly step.
                Array(GLOW_LEVELS) { level ->
                    Shadow(color = sungColor.copy(alpha = glowAlpha * (level + 1f) / GLOW_LEVELS), blurRadius = glowPx)
                }
            }
        val cadenceInk =
            remember(sungColor, unsungColor, feather, glows, cadence) {
                if (!cadence) {
                    null
                } else {
                    val clear = sungColor.copy(alpha = 0f)
                    CadenceInk(
                        sung = sungColor,
                        unsung = unsungColor,
                        forward = forward,
                        backward = backward,
                        fadeForward = frontBrush(feather, sungColor, clear),
                        fadeBackward = frontBrush(feather, clear, sungColor),
                        glows = glows,
                    )
                }
            }
        val frame = remember { CadenceFrame() }
        val anchor =
            remember(layout, style) {
                reflowAnchor(style.textAlign, layout.getParagraphDirection(0) == ResolvedTextDirection.Rtl)
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
            if (cadenceInk != null && (isCurrent || lift != 0f || exit.value < 1f)) {
                frame.position = if (isCurrent) position else lastSungPosition[0]
                frame.liftClock = frame.position
                frame.exit = if (isCurrent) -1f else exit.value
                frame.lineLift = lift
                frame.peakPx = fontPx * CADENCE_PEAK_EM
                frame.featherPx = feather
                frame.anchor = anchor
                // Grown words reach above the row as well as the lift does.
                frame.overscan = liftPx * 2f + fontPx * 0.25f
                frame.glow = glow != LyricsGlow.OFF
                drawCadenceLine(geometry, cadenceInk, frame)
                return@Canvas
            }
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
                if (moving && glow != LyricsGlow.OFF && glowLayout != null && glowLevel >= 0) {
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
