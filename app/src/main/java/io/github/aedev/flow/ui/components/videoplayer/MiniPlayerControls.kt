package io.github.aedev.flow.ui.components.videoplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.player.state.EnhancedPlayerState
import io.github.aedev.flow.ui.components.videoplayer.motion.lerpClamped
import io.github.aedev.flow.ui.theme.PlayerScrimContent
import io.github.aedev.flow.ui.theme.PlayerScrimMiniButton
import io.github.aedev.flow.ui.theme.PlayerScrimMiniTopButton
import io.github.aedev.flow.ui.utils.LocalWindowSizeClass
import io.github.aedev.flow.ui.utils.isMediumWidth

/**
 * The controls and progress bar that sit on the floating mini player. Sized at the settled mini
 * size and counter-scaled by the constant `expandedVideoWidth / miniWidth`, so the parent's morph
 * scale renders them 1:1 in the mini window with no per-frame measure; they pop and fade in over
 * the last stretch of the collapse.
 */
@Composable
internal fun BoxScope.MiniPlayerControlsLayer(
    state: PlayerDraggableState,
    miniWidth: Float,
    miniHeight: Float,
    expandedVideoWidth: Float,
    progress: () -> Float,
    miniControls: @Composable (() -> Float) -> Unit,
) {
    val miniControlsVisible by remember(state) {
        derivedStateOf { state.expandFraction.value > 0.6f && state.openOrigin == null }
    }
    if (!miniControlsVisible) return
    val density = LocalDensity.current
    val controlsScale = expandedVideoWidth / miniWidth.coerceAtLeast(1f)
    val fractionProvider = remember(state) { { state.expandFraction.value } }
    Box(
        modifier =
            Modifier
                .size(with(density) { miniWidth.toDp() }, with(density) { miniHeight.toDp() })
                .graphicsLayer {
                    val controlsProgress = ((state.expandFraction.value - 0.6f) / 0.25f).coerceIn(0f, 1f)
                    transformOrigin = TransformOrigin(0f, 0f)
                    val pop = lerpClamped(0.96f, 1f, controlsProgress)
                    scaleX = controlsScale * pop
                    scaleY = controlsScale * pop
                    alpha = controlsProgress
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                    shape = RoundedCornerShape(MINI_PLAYER_CORNER_RADIUS_DP.dp)
                    clip = true
                },
    ) {
        miniControls(fractionProvider)

        LinearProgressIndicator(
            progress = progress,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(2.dp)
                    .graphicsLayer {
                        alpha = ((state.expandFraction.value - 0.72f) / 0.18f).coerceIn(0f, 1f)
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
            color = MaterialTheme.colorScheme.primary,
            trackColor = Color.Transparent,
        )
    }
}

/**
 * Mini Player Controls - Dynamically arranges Play/Pause, Rewind/FastForward, and Next/Previous.
 */
@Composable
internal fun MiniPlayerControls(
    playerState: EnhancedPlayerState,
    showSkipControls: Boolean,
    showNextPrevControls: Boolean,
    sizeScale: Float = 1f,
    onPlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    onSkipBack: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClose: () -> Unit,
) {
    val isWideWindow = LocalWindowSizeClass.current.isMediumWidth

    val scaleMult = sizeScale.coerceIn(1f, 1.6f)

    val baseTouchSize = if (isWideWindow) 44.dp else 36.dp
    val baseBgSize = if (isWideWindow) 34.dp else 24.dp
    val baseIconSize = if (isWideWindow) 30.dp else 24.dp
    val finalTouchSize = baseTouchSize * scaleMult
    val finalBgSize = baseBgSize * scaleMult
    val finalIconSize = baseIconSize * scaleMult
    val topTouchSize = if (isWideWindow) 50.dp else 42.dp
    val topBgSize = if (isWideWindow) 42.dp else 34.dp

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        IconButton(
            onClick = onPlayPause,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size(topTouchSize),
        ) {
            MiniPlayerButtonBackground(
                backgroundSize = topBgSize,
                backgroundColor = PlayerScrimMiniTopButton,
            ) {
                if (playerState.isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(if (isWideWindow) 30.dp else 24.dp),
                        strokeWidth = 2.dp,
                        color = PlayerScrimContent,
                    )
                } else {
                    Icon(
                        imageVector =
                            when {
                                playerState.hasEnded -> Icons.Rounded.Replay
                                playerState.playWhenReady -> Icons.Rounded.Pause
                                else -> Icons.Rounded.PlayArrow
                            },
                        contentDescription =
                            when {
                                playerState.hasEnded -> stringResource(R.string.ui_replay)
                                playerState.playWhenReady -> stringResource(R.string.pause)
                                else -> stringResource(R.string.play)
                            },
                        tint = PlayerScrimContent,
                        modifier = Modifier.size(if (isWideWindow) 42.dp else 34.dp),
                    )
                }
            }
        }

        IconButton(
            onClick = {
                EnhancedPlayerManager.getInstance().stop()
                GlobalPlayerState.hideMiniPlayer()
                onClose()
            },
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(topTouchSize),
        ) {
            MiniPlayerButtonBackground(
                backgroundSize = topBgSize,
                backgroundColor = PlayerScrimMiniTopButton,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = PlayerScrimContent,
                    modifier = Modifier.size(if (isWideWindow) 34.dp else 30.dp),
                )
            }
        }

        if (showSkipControls || showNextPrevControls) {
            Row(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showNextPrevControls) {
                    MiniPlayerIconButton(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = stringResource(R.string.previous),
                        touchSize = finalTouchSize,
                        backgroundSize = finalBgSize,
                        iconSize = finalIconSize,
                        onClick = onPrevious,
                    )
                }

                if (showSkipControls) {
                    MiniPlayerIconButton(
                        imageVector = Icons.Rounded.Replay10,
                        contentDescription = stringResource(R.string.ui_skip_back_10_seconds),
                        touchSize = finalTouchSize,
                        backgroundSize = finalBgSize,
                        iconSize = finalIconSize,
                        onClick = onSkipBack,
                    )
                }

                if (showSkipControls) {
                    MiniPlayerIconButton(
                        imageVector = Icons.Rounded.Forward10,
                        contentDescription = stringResource(R.string.ui_skip_forward_10_seconds),
                        touchSize = finalTouchSize,
                        backgroundSize = finalBgSize,
                        iconSize = finalIconSize,
                        onClick = onSkipForward,
                    )
                }

                if (showNextPrevControls) {
                    MiniPlayerIconButton(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = stringResource(R.string.next),
                        touchSize = finalTouchSize,
                        backgroundSize = finalBgSize,
                        iconSize = finalIconSize,
                        onClick = onNext,
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniPlayerIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    touchSize: Dp,
    backgroundSize: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(touchSize),
    ) {
        MiniPlayerButtonBackground(backgroundSize = backgroundSize) {
            Icon(
                imageVector = imageVector,
                contentDescription = contentDescription,
                tint = PlayerScrimContent,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
internal fun MiniPlayerButtonBackground(
    backgroundSize: Dp,
    backgroundColor: Color = PlayerScrimMiniButton,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(backgroundSize)
                .background(backgroundColor, CircleShape),
        contentAlignment = Alignment.Center,
        content = content,
    )
}
