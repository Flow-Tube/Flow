package io.github.aedev.flow.ui.components.musicplayer.lyrics

import android.graphics.RenderEffect
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.lyrics.LyricsEntry
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/** Lines this close to the sung one draw through the karaoke canvas, so activation never re-lays out. */
private const val KARAOKE_WINDOW = 1

/** Past this distance the blur no longer changes, so far lines never re-create their effect. */
private const val MAX_BLUR_DISTANCE = 4
private const val ACTIVE_SCALE = 1.05f
private const val INACTIVE_SCALE = 0.92f

private val DotSize = 10.dp
private val DotGap = 12.dp
private val IndicatorHeight = 64.dp

/** How one lyric line is dressed: its size, spacing, colour and alignment, decided by the panel. */
internal class LyricsLineLook(
    val textSize: Float,
    val lineSpacing: Float,
    val accent: Color,
    val align: TextAlign,
    val backdrop: Color?,
) {
    /**
     * The accent at [alpha], blended onto the backdrop up front. Translucent text paints overlapping
     * glyphs twice, which shows as bright seams in joined scripts such as Bengali (#1484).
     */
    fun ink(alpha: Float): Color {
        val translucent = accent.copy(alpha = alpha)
        return backdrop?.let { translucent.compositeOver(it) } ?: translucent
    }
}

/**
 * An instrumental break: three dots that fill one after another with the break's progress and
 * breathe while music plays, then fade as the next line approaches.
 */
@Composable
internal fun IntervalIndicator(
    gapStartMs: Long,
    gapEndMs: Long,
    positionProvider: () -> Long,
    visible: Boolean,
    animate: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(visible) { shown.animateTo(if (visible) 1f else 0f, tween(240)) }
    val breathe =
        if (animate && visible) {
            val transition = rememberInfiniteTransition(label = "instrumentalBreath")
            transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Restart),
                label = "instrumentalBreathPhase",
            )
        } else {
            null
        }
    val description = stringResource(R.string.ui_instrumental)

    Box(
        modifier =
            modifier
                .layout { measurable, constraints ->
                    val height = (IndicatorHeight.roundToPx() * shown.value).roundToInt()
                    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = IndicatorHeight.roundToPx()))
                    layout(placeable.width, height) { placeable.place(0, (height - placeable.height) / 2) }
                }.graphicsLayer { alpha = shown.value }
                .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.width(DotSize * 3 + DotGap * 2).height(DotSize * 2)) {
            val span = (gapEndMs - gapStartMs).coerceAtLeast(1)
            val progress = ((positionProvider() - gapStartMs).toFloat() / span).coerceIn(0f, 1f)
            val handOff = ((1f - progress) / 0.12f).coerceIn(0f, 1f)
            val breath = breathe?.value?.let { 1f + 0.08f * sin(it * 2f * PI.toFloat()) } ?: 1f
            val radius = DotSize.toPx() / 2f
            val step = DotSize.toPx() + DotGap.toPx()
            repeat(3) { i ->
                val fill = (progress * 3f - i).coerceIn(0f, 1f)
                drawCircle(
                    color = color.copy(alpha = (0.22f + 0.78f * fill) * handOff),
                    radius = radius * (0.8f + 0.25f * fill) * breath,
                    center = Offset(radius + step * i, size.height / 2f),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LyricsLine(
    index: Int,
    item: LyricsEntry,
    isSynced: Boolean,
    isActiveLine: Boolean,
    isPlaying: Boolean,
    syncOffsetMs: Long,
    bgVisible: Boolean,
    positionProvider: () -> Long,
    nextLineTimeMs: Long?,
    look: LyricsLineLook,
    isAutoScrollEnabled: Boolean,
    displayedCurrentLineIndex: Int,
    showTranslation: Boolean,
    romanization: String?,
    romanizationOnly: Boolean,
    motionEnabled: Boolean,
    blurOtherLines: Boolean,
    onSizeChanged: (Int) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val motion = MaterialTheme.motionScheme
    val lineDistance = abs(index - displayedCurrentLineIndex)
    val dofBlurRadius by animateFloatAsState(
        targetValue =
            if (!blurOtherLines || !isSynced || isActiveLine || item.isBackground) {
                0f
            } else {
                with(density) { (lineDistance.coerceAtMost(MAX_BLUR_DISTANCE) * 4.dp.toPx()) }
            },
        animationSpec = if (motionEnabled) motion.slowEffectsSpec() else snap(),
        label = "lyricsDofBlur",
    )
    val depthScale by animateFloatAsState(
        targetValue = if (isActiveLine) ACTIVE_SCALE else INACTIVE_SCALE,
        animationSpec = if (motionEnabled) motion.defaultSpatialSpec() else snap(),
        label = "lyricsDepthScale",
    )
    // Radii are whole pixels, so an animation re-uses a handful of effects instead of one per frame.
    val blurEffects = remember { HashMap<Int, androidx.compose.ui.graphics.RenderEffect>() }
    val playFromHere = stringResource(R.string.lyrics_play_from_line)
    // Lines grow from their own edge, so a start or end aligned line (a duet side) never scales past the panel.
    val (scaleOrigin, contentAlignment) =
        when (look.align) {
            TextAlign.Start, TextAlign.Left -> TransformOrigin(0f, 0.5f) to Alignment.CenterStart
            TextAlign.End, TextAlign.Right -> TransformOrigin(1f, 0.5f) to Alignment.CenterEnd
            else -> TransformOrigin.Center to Alignment.Center
        }

    val itemModifier =
        modifier
            .fillMaxWidth()
            .onSizeChanged { onSizeChanged(it.height) }
            .graphicsLayer {
                transformOrigin = scaleOrigin
                scaleX = depthScale
                scaleY = depthScale
                val radius = dofBlurRadius.roundToInt()
                renderEffect =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && radius > 0) {
                        blurEffects.getOrPut(radius) {
                            RenderEffect
                                .createBlurEffect(radius.toFloat(), radius.toFloat(), android.graphics.Shader.TileMode.DECAL)
                                .asComposeRenderEffect()
                        }
                    } else {
                        null
                    }
            }.clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClickLabel = if (isSynced) playFromHere else null, onClick = onClick)
            .padding(
                start = 24.dp,
                end = 24.dp,
                top = if (item.isBackground) 0.dp else 12.dp,
                bottom = if (item.isBackground) 2.dp else 12.dp,
            )

    Box(modifier = itemModifier, contentAlignment = contentAlignment) {
        @Composable
        fun LyricContent() {
            LyricBody(
                index = index,
                item = item,
                isSynced = isSynced,
                isActiveLine = isActiveLine,
                isPlaying = isPlaying,
                syncOffsetMs = syncOffsetMs,
                positionProvider = positionProvider,
                nextLineTimeMs = nextLineTimeMs,
                look = look,
                isAutoScrollEnabled = isAutoScrollEnabled,
                displayedCurrentLineIndex = displayedCurrentLineIndex,
                showTranslation = showTranslation,
                romanization = romanization,
                romanizationOnly = romanizationOnly,
                motionEnabled = motionEnabled,
            )
        }

        if (item.isBackground) {
            AnimatedVisibility(
                visible = bgVisible,
                enter = fadeIn(tween(durationMillis = 250, delayMillis = 100)),
                exit = fadeOut(tween(250)),
            ) {
                LyricContent()
            }
        } else {
            LyricContent()
        }
    }
}

@Composable
private fun LyricBody(
    index: Int,
    item: LyricsEntry,
    isSynced: Boolean,
    isActiveLine: Boolean,
    isPlaying: Boolean,
    syncOffsetMs: Long,
    positionProvider: () -> Long,
    nextLineTimeMs: Long?,
    look: LyricsLineLook,
    isAutoScrollEnabled: Boolean,
    displayedCurrentLineIndex: Int,
    showTranslation: Boolean,
    romanization: String?,
    romanizationOnly: Boolean,
    motionEnabled: Boolean,
) {
    val lineDistance = abs(index - displayedCurrentLineIndex)
    val alignment =
        when (look.align) {
            TextAlign.Start, TextAlign.Left -> Alignment.Start
            TextAlign.End, TextAlign.Right -> Alignment.End
            else -> Alignment.CenterHorizontally
        }
    // Narrow enough that the active line's scale still ends inside the row.
    Column(modifier = Modifier.fillMaxWidth(1f / ACTIVE_SCALE), horizontalAlignment = alignment) {
        val inactiveAlpha = if (item.isBackground) 0.2f else 0.45f
        val focusedAlpha = if (item.isBackground) 0.6f else 0.45f
        val targetAlpha =
            when {
                !isSynced || item.isBackground || isActiveLine -> {
                    1f
                }

                isAutoScrollEnabled && displayedCurrentLineIndex >= 0 -> {
                    when (lineDistance) {
                        0 -> focusedAlpha
                        1 -> 0.4f
                        2 -> 0.35f
                        3 -> 0.3f
                        else -> inactiveAlpha
                    }
                }

                else -> {
                    inactiveAlpha
                }
            }
        val animatedAlpha by animateFloatAsState(
            targetValue = targetAlpha,
            animationSpec = if (motionEnabled) MaterialTheme.motionScheme.defaultEffectsSpec() else snap(),
            label = "lyricsLineAlpha",
        )
        val accent = look.accent
        val lineAlpha = if (item.isBackground) focusedAlpha else animatedAlpha
        val lineColor = look.ink(lineAlpha)
        val romanizedMain = romanization?.takeIf { romanizationOnly }
        val shownText = romanizedMain ?: item.text
        val mainText = if (item.isBackground) shownText.removePrefix("(").removeSuffix(")") else shownText
        val translation = item.translation?.takeIf { showTranslation && it.isNotBlank() }
        val textSize =
            remember(
                mainText,
                look.textSize,
                item.isBackground,
            ) { adaptiveLyricsTextSize(look.textSize, mainText.length, item.isBackground) }
        val lyricStyle =
            TextStyle(
                fontSize = textSize.sp,
                fontWeight = FontWeight.Bold,
                fontStyle = if (item.isBackground) FontStyle.Italic else FontStyle.Normal,
                lineHeight = (textSize * look.lineSpacing).sp,
                letterSpacing = 0.sp,
                textAlign = look.align,
                fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.Both),
            )
        val words =
            remember(item, mainText, nextLineTimeMs) {
                val source = item.words?.takeIf { it.isNotEmpty() }
                val given = if (romanizedMain != null) source?.let { romanizedWordTimings(mainText, it) } else source
                if (given != null) {
                    splitTrailingHyphenWord(sanitizeWordTimestamps(given)).first
                } else {
                    synthesizeWordTimings(mainText, item.time, nextLineTimeMs)
                }
            }

        if (isSynced && words.isNotEmpty() && (isActiveLine || lineDistance <= KARAOKE_WINDOW)) {
            KaraokeLine(
                text = mainText,
                words = words,
                style = lyricStyle,
                sungColor = accent,
                unsungColor = if (isActiveLine) look.ink(focusedAlpha) else lineColor,
                isCurrent = isActiveLine,
                isPlaying = isPlaying,
                isBackground = item.isBackground,
                syncOffsetMs = syncOffsetMs,
                positionProvider = positionProvider,
                motionEnabled = motionEnabled,
            )
        } else {
            Text(
                text = mainText,
                style = lyricStyle.copy(color = if (isActiveLine) accent else lineColor),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val secondaryColor =
            look.ink(if (isActiveLine) 0.72f else (lineAlpha * 0.78f).coerceIn(0.08f, 0.6f))
        val secondaryStyle =
            lyricStyle.copy(
                fontSize = (textSize * 0.52f).coerceAtLeast(15f).sp,
                lineHeight = (textSize * 0.68f).coerceAtLeast(19f).sp,
                fontWeight = FontWeight.SemiBold,
                fontStyle = FontStyle.Normal,
            )
        if (romanization != null && romanizedMain == null) {
            Text(
                text = romanization,
                color = secondaryColor,
                style = secondaryStyle.copy(fontStyle = FontStyle.Italic),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
        if (translation != null) {
            Text(
                text = translation,
                color = secondaryColor,
                style = secondaryStyle,
                modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
            )
        }
    }
}
