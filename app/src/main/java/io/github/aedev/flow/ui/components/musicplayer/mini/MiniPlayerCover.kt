@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.aedev.flow.ui.components.musicplayer.mini

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import coil3.compose.AsyncImage
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.MiniCoverMotion
import io.github.aedev.flow.data.local.MiniCoverShape
import io.github.aedev.flow.ui.components.PlayingWaveform
import io.github.aedev.flow.ui.components.shared.fittedToCircle
import io.github.aedev.flow.ui.theme.ArtworkScrimContent
import io.github.aedev.flow.ui.theme.ArtworkScrimNowPlaying
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.floor

private val ArtworkRingSize = 52.dp
private val ArtworkSize = 43.dp
private val ProgressRingStroke = 2.5.dp

private const val PLAY_TURN_DEGREES = 30f
private const val TRACK_TURN_DEGREES = 72f
private const val ALWAYS_DEGREES_PER_SECOND = 36f
private const val ALWAYS_HOLD_SECONDS = 1.6f
private const val ALWAYS_MORPH_SECONDS = 0.9f
private const val ALWAYS_CYCLE_SECONDS = ALWAYS_HOLD_SECONDS + ALWAYS_MORPH_SECONDS

private val RestShape: RoundedPolygon by lazy { MaterialShapes.Circle.fittedToCircle() }

private val TrackShapes: List<RoundedPolygon> by lazy {
    listOf(
        MaterialShapes.Cookie9Sided,
        MaterialShapes.Clover4Leaf,
        MaterialShapes.Cookie6Sided,
        MaterialShapes.Flower,
        MaterialShapes.Sunny,
        MaterialShapes.SoftBurst,
    ).map { it.fittedToCircle() }
}

/** Each track shape morphing into the next one, for a cover that keeps moving. */
private val CycleMorphs: List<Morph> by lazy {
    TrackShapes.indices.map { Morph(TrackShapes[it], TrackShapes[(it + 1) % TrackShapes.size]) }
}

/** Which track shape a track gets: the same one every time it plays. */
internal fun miniCoverShapeIndex(
    trackId: String,
    count: Int,
): Int = Math.floorMod(trackId.hashCode(), count)

/** Which shape a cover that keeps moving morphs away from after [seconds] of play. */
internal fun alwaysMovingShape(
    startIndex: Int,
    seconds: Float,
    count: Int,
): Int = Math.floorMod(startIndex + floor(seconds / ALWAYS_CYCLE_SECONDS).toInt(), count)

/** How far that morph is: it holds each shape, then eases into the next. */
internal fun alwaysMovingFraction(seconds: Float): Float {
    val inCycle = seconds - floor(seconds / ALWAYS_CYCLE_SECONDS) * ALWAYS_CYCLE_SECONDS
    val linear = ((inCycle - ALWAYS_HOLD_SECONDS) / ALWAYS_MORPH_SECONDS).coerceIn(0f, 1f)
    return linear * linear * (3f - 2f * linear)
}

/**
 * The mini player's cover in its progress ring. A circle draws as it always has; a morphing cover is
 * cut out with a shape that its ring follows. Nothing here runs per frame unless [motion] is
 * [MiniCoverMotion.ALWAYS], the music plays, the bar is shown and the activity is started.
 */
@Composable
internal fun MiniPlayerCover(
    thumbnailUrl: String?,
    trackId: String,
    isPlaying: Boolean,
    shape: MiniCoverShape,
    motion: MiniCoverMotion,
    animationsEnabled: Boolean,
    ringProgress: () -> Float,
    modifier: Modifier = Modifier,
) {
    val ringTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val ringFillColor = MaterialTheme.colorScheme.primary
    if (shape == MiniCoverShape.CIRCLE) {
        CircleCover(thumbnailUrl, isPlaying, animationsEnabled, ringProgress, ringTrackColor, ringFillColor, modifier)
    } else {
        MorphingCover(thumbnailUrl, trackId, isPlaying, motion, animationsEnabled, ringProgress, ringTrackColor, ringFillColor, modifier)
    }
}

@Composable
private fun CircleCover(
    thumbnailUrl: String?,
    isPlaying: Boolean,
    animationsEnabled: Boolean,
    ringProgress: () -> Float,
    ringTrackColor: Color,
    ringFillColor: Color,
    modifier: Modifier,
) {
    Box(
        modifier =
            modifier
                .size(ArtworkRingSize)
                .drawBehind {
                    val stroke = ProgressRingStroke.toPx()
                    val inset = stroke / 2f
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawArc(
                        color = ringTrackColor,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    val progress = ringProgress()
                    if (progress > 0f) {
                        drawArc(
                            color = ringFillColor,
                            startAngle = -90f,
                            sweepAngle = 360f * progress,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(ArtworkSize)
                    .clip(CircleShape),
        ) {
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = stringResource(R.string.album_art),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            if (isPlaying && animationsEnabled) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(ArtworkScrimNowPlaying),
                    contentAlignment = Alignment.Center,
                ) {
                    PlayingWaveform(
                        color = ArtworkScrimContent,
                        barCount = 3,
                        barWidth = 2.5.dp,
                        barSpacing = 1.5.dp,
                        staggerMillis = 120,
                    )
                }
            }
        }
    }
}

/** The shape a morphing cover is in: a morph between two shapes, how far along it is, and its turn. */
@Stable
private class CoverPose {
    var morph by mutableStateOf(Morph(RestShape, RestShape))
    val progress = Animatable(1f)
    val turn = Animatable(0f)
    var from: RoundedPolygon = RestShape
    var settled: RoundedPolygon = RestShape
    var trackId: String? = null
    var alwaysSeconds by mutableFloatStateOf(0f)
    var alwaysDegrees by mutableFloatStateOf(0f)
}

/** The shape a cover that moves on play and pause rests in: the track's own while it plays, a circle otherwise. */
private fun restingShape(
    motion: MiniCoverMotion,
    isPlaying: Boolean,
    trackShape: RoundedPolygon,
): RoundedPolygon = if (motion == MiniCoverMotion.ON_PLAY && !isPlaying) RestShape else trackShape

@Composable
private fun MorphingCover(
    thumbnailUrl: String?,
    trackId: String,
    isPlaying: Boolean,
    motion: MiniCoverMotion,
    animationsEnabled: Boolean,
    ringProgress: () -> Float,
    ringTrackColor: Color,
    ringFillColor: Color,
    modifier: Modifier,
) {
    val pose = remember { CoverPose() }
    val shapeIndex = remember(trackId) { miniCoverShapeIndex(trackId, TrackShapes.size) }
    val target = restingShape(motion, isPlaying, TrackShapes[shapeIndex])
    val spatial = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val shown = animationsEnabled && lifecycle.isAtLeast(Lifecycle.State.STARTED)

    LaunchedEffect(target, trackId, motion) {
        if (motion == MiniCoverMotion.ALWAYS) return@LaunchedEffect
        val newTrack = pose.trackId != null && pose.trackId != trackId
        val firstShow = pose.trackId == null
        pose.trackId = trackId
        if (target === pose.settled && !newTrack) return@LaunchedEffect
        // A morph cut short starts again from whichever end it was nearer.
        pose.from = if (pose.progress.value >= 0.5f) pose.settled else pose.from
        pose.morph = Morph(pose.from, target)
        pose.settled = target
        val turn =
            when {
                motion == MiniCoverMotion.STILL -> 0f
                newTrack -> TRACK_TURN_DEGREES
                target === RestShape -> -PLAY_TURN_DEGREES
                else -> PLAY_TURN_DEGREES
            }
        if (firstShow || !shown || motion == MiniCoverMotion.STILL) {
            pose.progress.snapTo(1f)
            pose.turn.snapTo(if (motion == MiniCoverMotion.STILL) 0f else pose.turn.value + turn)
            return@LaunchedEffect
        }
        coroutineScope {
            launch {
                pose.progress.snapTo(0f)
                pose.progress.animateTo(1f, spatial)
            }
            launch { pose.turn.animateTo(pose.turn.value + turn, spatial) }
        }
    }

    LaunchedEffect(motion, isPlaying, shown) {
        if (motion != MiniCoverMotion.ALWAYS || !isPlaying || !shown) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (isActive) {
            withFrameNanos { now ->
                val seconds = (now - last) / 1_000_000_000f
                last = now
                pose.alwaysSeconds += seconds
                pose.alwaysDegrees = (pose.alwaysDegrees + ALWAYS_DEGREES_PER_SECOND * seconds) % 360f
            }
        }
    }

    // Read from draw only, so the shape and turn change without recomposing the bar. Only the
    // outline turns: the artwork inside it stays upright.
    fun shapeAt(
        into: Path,
        matrix: android.graphics.Matrix,
        extent: Float,
        inset: Float,
        pivot: Offset,
    ) {
        val degrees =
            if (motion == MiniCoverMotion.ALWAYS) {
                val seconds = pose.alwaysSeconds
                CycleMorphs[alwaysMovingShape(shapeIndex, seconds, TrackShapes.size)].toPath(alwaysMovingFraction(seconds), into)
                pose.alwaysDegrees
            } else {
                pose.morph.toPath(pose.progress.value.coerceIn(0f, 1f), into)
                pose.turn.value
            }
        matrix.setScale(extent, extent)
        matrix.postTranslate(inset, inset)
        matrix.postRotate(degrees, pivot.x, pivot.y)
        into.asAndroidPath().transform(matrix)
    }

    Box(
        modifier =
            modifier
                .size(ArtworkRingSize)
                .drawWithCache {
                    val stroke = ProgressRingStroke.toPx()
                    val outline = Path()
                    val wedge = Path()
                    val matrix = android.graphics.Matrix()
                    val style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    // The fill is revealed by a wedge from 12 o'clock: a path's start point moves between shapes.
                    val wedgeBounds = Rect(size.center, size.maxDimension)
                    onDrawBehind {
                        shapeAt(outline, matrix, size.minDimension - stroke, stroke / 2f, center)
                        drawPath(outline, ringTrackColor, style = style)
                        val progress = ringProgress()
                        if (progress > 0f) {
                            wedge.reset()
                            wedge.moveTo(center.x, center.y)
                            wedge.arcTo(wedgeBounds, -90f, 360f * progress, forceMoveTo = false)
                            wedge.close()
                            clipPath(wedge) { drawPath(outline, ringFillColor, style = style) }
                        }
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(ArtworkSize)
                    .drawWithCache {
                        val clip = Path()
                        val matrix = android.graphics.Matrix()
                        onDrawWithContent {
                            shapeAt(clip, matrix, size.minDimension, 0f, center)
                            clipPath(clip) { this@onDrawWithContent.drawContent() }
                        }
                    },
        ) {
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = stringResource(R.string.album_art),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
