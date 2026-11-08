package io.github.aedev.flow.ui.components.musicplayer.mini

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.ui.components.PlayingWaveform
import io.github.aedev.flow.ui.components.shared.MediaPlayPauseButton
import io.github.aedev.flow.ui.components.shared.titleMarquee
import io.github.aedev.flow.ui.screens.playon.LocalPlayOn
import io.github.aedev.flow.ui.screens.playon.PlayOnButton
import io.github.aedev.flow.ui.theme.ArtworkScrimContent
import io.github.aedev.flow.ui.theme.ArtworkScrimNowPlaying
import kotlinx.coroutines.flow.conflate
import kotlin.math.abs

private const val RING_GLIDE_MILLIS = 900
private const val RING_GLIDE_MIN_JUMP_MILLIS = 5_000f

private val ArtworkRingSize = 52.dp
private val ArtworkSize = 43.dp
private val ProgressRingStroke = 2.5.dp

/**
 * The collapsed player: cover in a progress ring, title and artist, then the transport. Wider
 * windows give the card room for Previous as well.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MiniPlayerContent(
    track: MusicTrack,
    modifier: Modifier = Modifier,
    // False while the expanded player covers the (alpha-0) mini bar: the waveform, marquee
    // and ring glide stop forcing frames nobody can see. Values still update.
    animationsEnabled: Boolean = true,
    showPrevious: Boolean = false,
    onOpen: (() -> Unit)? = null,
) {
    val playerState by EnhancedMusicPlayerManager.playerState.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    // While the user drives another device from here, the player follows it and its controls go there.
    val elsewhere =
        LocalPlayOn.current
            ?.elsewhere
            ?.collectAsStateWithLifecycle()
            ?.value

    val ringProgress =
        rememberRingProgress(
            fraction =
                if (playerState.duration > 0) {
                    (playerState.position.toFloat() / playerState.duration.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                },
            durationMillis = playerState.duration,
            animationsEnabled = animationsEnabled,
        )

    Row(
        modifier =
            modifier
                .fillMaxSize()
                .padding(start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // With [onOpen] the cover and titles are one target of their own, beside the transport buttons,
        // so a remote reaches each of them; a touch host opens the player from the whole bar instead.
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .then(
                        if (onOpen == null) {
                            Modifier
                        } else {
                            Modifier
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .clickable(interactionSource = null, indication = ripple(focusRingShape = CircleShape), onClick = onOpen)
                        },
                    ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val ringTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
            val ringFillColor = MaterialTheme.colorScheme.primary
            Box(
                modifier =
                    Modifier
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
                            val progress = ringProgress.value
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
                        model = track.listThumbnailUrl,
                        contentDescription = stringResource(R.string.album_art),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    if (playerState.isPlaying && animationsEnabled) {
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

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                key(track.videoId) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurface,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (animationsEnabled) Modifier.titleMarquee() else Modifier,
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = elsewhere?.let { stringResource(R.string.play_on_playing_on, it.name) } ?: track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (elsewhere != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (elsewhere != null) PlayOnButton()
            if (showPrevious) {
                IconButton(onClick = { EnhancedMusicPlayerManager.playPrevious() }) {
                    Icon(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = stringResource(R.string.previous),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            MediaPlayPauseButton(
                isPlaying = playerState.isPlaying,
                isBuffering = playerState.isBuffering && animationsEnabled,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    EnhancedMusicPlayerManager.togglePlayPause()
                },
            )
            IconButton(onClick = { EnhancedMusicPlayerManager.playNext() }) {
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = stringResource(R.string.next),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The ring's fill. A position tick moves it only a pixel or two, so it steps there: gliding would
 * draw at the display rate for most of every second. Seeks and new tracks still glide, and a glide
 * runs to its end before the next position is taken.
 */
@Composable
private fun rememberRingProgress(
    fraction: Float,
    durationMillis: Long,
    animationsEnabled: Boolean,
): Animatable<Float, AnimationVector1D> {
    val ring = remember { Animatable(fraction) }
    val latestFraction by rememberUpdatedState(fraction)
    val latestDuration by rememberUpdatedState(durationMillis)
    val mayGlide by rememberUpdatedState(animationsEnabled)
    LaunchedEffect(ring) {
        snapshotFlow { latestFraction }.conflate().collect { target ->
            if (mayGlide && ringStepGlides(ring.value, target, latestDuration)) {
                ring.animateTo(target, tween(RING_GLIDE_MILLIS, easing = LinearEasing))
            } else {
                ring.snapTo(target)
            }
        }
    }
    return ring
}

internal fun ringStepGlides(
    fromFraction: Float,
    toFraction: Float,
    durationMillis: Long,
): Boolean = durationMillis <= 0 || abs(toFraction - fromFraction) * durationMillis >= RING_GLIDE_MIN_JUMP_MILLIS
