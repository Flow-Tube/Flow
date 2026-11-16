package io.github.aedev.flow.ui.components.musicplayer.mini

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.MiniBarButton
import io.github.aedev.flow.data.local.MiniCoverTap
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.ui.components.musicplayer.lyrics.SYNCED_LINE_UNSUNG_ALPHA
import io.github.aedev.flow.ui.components.musicplayer.lyrics.SyncedLines
import io.github.aedev.flow.ui.components.musicplayer.lyrics.SyncedLyricLine
import io.github.aedev.flow.ui.components.shared.MediaPlayPauseButton
import io.github.aedev.flow.ui.components.shared.titleMarquee
import io.github.aedev.flow.ui.screens.playon.LocalPlayOn
import io.github.aedev.flow.ui.screens.playon.PlayOnButton
import kotlinx.coroutines.flow.conflate
import kotlin.math.abs

private const val RING_GLIDE_MILLIS = 900
private const val RING_GLIDE_MIN_JUMP_MILLIS = 5_000f

/**
 * The collapsed player: cover in a progress ring, title and artist, then the transport. Wider
 * windows give the card room for Previous as well. With [lyrics] the line being sung takes the
 * artist's place while a line is up. [look] is everything the user chose about the bar.
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
    lyrics: SyncedLines? = null,
    look: MiniPlayerLook = MiniPlayerLook.Plain,
    actions: MiniBarActions? = null,
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

    val progressColor = MaterialTheme.colorScheme.primary
    val progressTrack = MaterialTheme.colorScheme.onSurface
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .miniBarProgress(look.barProgress, { ringProgress.value }, progressColor, progressTrack),
    ) {
        Row(
            modifier =
                Modifier
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
                                    .clickable(
                                        interactionSource = null,
                                        indication = ripple(focusRingShape = CircleShape),
                                        onClick = onOpen,
                                    )
                            },
                        ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MiniPlayerCover(
                    modifier =
                        if (look.coverTap == MiniCoverTap.PLAY_PAUSE) {
                            Modifier.clickable(
                                interactionSource = null,
                                indication = ripple(bounded = false),
                                onClickLabel = stringResource(R.string.mini_button_play_pause),
                            ) {
                                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                EnhancedMusicPlayerManager.togglePlayPause()
                            }
                        } else {
                            Modifier
                        },
                    thumbnailUrl = track.listThumbnailUrl,
                    trackId = track.videoId,
                    isPlaying = playerState.isPlaying,
                    look = look,
                    animationsEnabled = animationsEnabled,
                    ringProgress = { ringProgress.value },
                )

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
                    val artistColor =
                        if (elsewhere !=
                            null
                        ) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    val artistLine: @Composable () -> Unit = {
                        Text(
                            text = elsewhere?.let { stringResource(R.string.play_on_playing_on, it.name) } ?: track.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = artistColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (lyrics != null && elsewhere == null) {
                        val style = MaterialTheme.typography.labelLarge
                        val sung = MaterialTheme.colorScheme.onSurface
                        SyncedLyricLine(
                            lyrics = lyrics,
                            isPlaying = playerState.isPlaying,
                            positionKey = playerState.position / 1000L,
                            style = style,
                            sungColor = sung,
                            unsungColor = sung.copy(alpha = SYNCED_LINE_UNSUNG_ALPHA),
                            // As tall as a lyric line either way, so the title doesn't move when the artist comes back.
                            modifier = Modifier.fillMaxWidth().heightIn(min = with(LocalDensity.current) { style.lineHeight.toDp() }),
                            active = animationsEnabled,
                            clearInGaps = true,
                            singleLine = true,
                            contentAlignment = Alignment.CenterStart,
                            empty = artistLine,
                        )
                    } else {
                        artistLine()
                    }
                    if (look.barProgress.isLine()) {
                        MiniBarProgressLine(
                            style = look.barProgress,
                            progress = { ringProgress.value },
                            isPlaying = playerState.isPlaying,
                            animationsEnabled = animationsEnabled,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (elsewhere != null) PlayOnButton()
                MiniBarButtons(
                    buttons = barButtons(look.buttons, showPrevious),
                    isPlaying = playerState.isPlaying,
                    isBuffering = playerState.isBuffering && animationsEnabled,
                    actions = actions,
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

/** The buttons to show: the user's, with Previous put first on a window wide enough to have shown it before. */
internal fun barButtons(
    chosen: List<MiniBarButton>,
    showPrevious: Boolean,
): List<MiniBarButton> = if (showPrevious && MiniBarButton.PREVIOUS !in chosen) listOf(MiniBarButton.PREVIOUS) + chosen else chosen
