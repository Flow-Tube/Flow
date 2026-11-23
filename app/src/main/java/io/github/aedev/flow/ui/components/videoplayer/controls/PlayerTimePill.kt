package io.github.aedev.flow.ui.components.videoplayer.controls

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aedev.flow.R
import io.github.aedev.flow.player.live.LiveEdgePolicy
import io.github.aedev.flow.ui.theme.PlayerLiveIndicator
import io.github.aedev.flow.ui.theme.PlayerScrimAffordance
import io.github.aedev.flow.ui.theme.PlayerScrimContent
import io.github.aedev.flow.ui.theme.PlayerScrimContentSecondary
import io.github.aedev.flow.utils.formatDuration

private val LiveDotSize = 8.dp

@Composable
fun PlayerTimePill(
    /**
     * Position provider rather than a value, so the playhead tick recomposes this pill instead of
     * the whole player-controls overlay that hosts it. It is polled at 250ms while the expanded
     * controls are up and 1s otherwise, so the readable second is derived rather than read: a
     * sub-second tick must not rebuild the strings.
     */
    positionProvider: () -> Long,
    duration: Long,
    isLive: Boolean,
    showRemainingTime: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /**
     * Transparent in portrait fullscreen, where the read-out sits on the bottom edge gradient and a
     * second container over it reads as a stray chip.
     */
    containerColor: Color = PlayerScrimAffordance,
    /**
     * Whether the layer holding this pill is on screen. The live badge pulses forever, and the
     * controls overlay is kept composed behind the video while hidden, so the pulse has to stop
     * with its layer rather than with its composition.
     */
    isLayerVisible: () -> Boolean = { true },
    isPlaying: Boolean = true,
) {
    // Within 15 seconds of the end of the window the badge is red and a tap has nothing to do;
    // behind it, the badge greys, shows how far behind, and a tap goes back to live.
    val isAtLiveEdge by remember(positionProvider, duration) {
        derivedStateOf { LiveEdgePolicy.isAtEdge(duration, positionProvider()) }
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            enabled = !(isLive && isAtLiveEdge),
            color = containerColor,
            shape = CircleShape,
            modifier = modifier,
        ) {
            PillRow(positionProvider, duration, isLive, showRemainingTime, isLayerVisible, isAtLiveEdge, isPlaying)
        }
    } else {
        Surface(
            color = containerColor,
            shape = CircleShape,
            modifier = modifier,
        ) {
            PillRow(positionProvider, duration, isLive, showRemainingTime, isLayerVisible, isAtLiveEdge, isPlaying)
        }
    }
}

@Composable
private fun PillRow(
    positionProvider: () -> Long,
    duration: Long,
    isLive: Boolean,
    showRemainingTime: Boolean,
    isLayerVisible: () -> Boolean,
    isAtLiveEdge: Boolean,
    isPlaying: Boolean,
) {
    val elapsedSeconds by remember(positionProvider) {
        derivedStateOf { positionProvider() / 1000L }
    }

    Row(
        modifier =
            Modifier
                .wrapContentWidth()
                .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isLive) {
            if (!isAtLiveEdge) {
                BehindLiveText(positionProvider, duration)
                TimeSeparator()
            }
            val badgeColor = if (isAtLiveEdge) PlayerLiveIndicator else PlayerScrimContentSecondary
            LiveDot(
                color = badgeColor,
                isPulsing = isAtLiveEdge && isPlaying,
                isLayerVisible = isLayerVisible,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.player_live_label),
                style = MaterialTheme.typography.labelMedium,
                color = badgeColor,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp,
            )
        } else {
            if (showRemainingTime) {
                val remainingSeconds by remember(positionProvider, duration) {
                    derivedStateOf { (duration - positionProvider()).coerceAtLeast(0L) / 1000L }
                }
                Text(
                    text = remember(remainingSeconds) { "-" + formatDuration(remainingSeconds.toInt(), padMinutes = true) },
                    style = MaterialTheme.typography.labelMedium,
                    color = PlayerScrimContent,
                    fontWeight = FontWeight.Bold,
                )
            } else {
                ElapsedText(elapsedSeconds)
            }
            TimeSeparator()
            Text(
                text = remember(duration) { formatDuration((duration / 1000L).toInt(), padMinutes = true) },
                style = MaterialTheme.typography.labelMedium,
                color = PlayerScrimContentSecondary,
            )
        }
    }
}

/** How far behind live the playhead is, counted from the end of the window rather than its start, which slides. */
@Composable
private fun BehindLiveText(
    positionProvider: () -> Long,
    windowDuration: Long,
) {
    val behindSeconds by remember(positionProvider, windowDuration) {
        derivedStateOf { LiveEdgePolicy.behindLiveMs(windowDuration, positionProvider()) / 1000L }
    }
    val formatted = remember(behindSeconds) { formatDuration(behindSeconds.toInt(), padMinutes = true) }
    Text(
        text = stringResource(R.string.player_live_time_behind, formatted),
        style = MaterialTheme.typography.labelMedium,
        color = PlayerScrimContent,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun ElapsedText(elapsedSeconds: Long) {
    Text(
        text = remember(elapsedSeconds) { formatDuration(elapsedSeconds.toInt(), padMinutes = true) },
        style = MaterialTheme.typography.labelMedium,
        color = PlayerScrimContent,
        fontWeight = FontWeight.Bold,
    )
}

/**
 * The dot beside the LIVE badge. It pulses only while live playback is running at the edge.
 *
 * The transition only exists while the layer is on screen and the dot pulses, so pausing, falling
 * behind or hiding the layer cancels the animation instead of leaving it driving the frame clock.
 * The alpha is read in `graphicsLayer`, so the pulse repaints without recomposing the pill.
 */
@Composable
private fun LiveDot(
    color: Color,
    isPulsing: Boolean,
    isLayerVisible: () -> Boolean,
) {
    val visible by remember(isLayerVisible) { derivedStateOf { isLayerVisible() } }

    if (visible && isPulsing) {
        val transition = rememberInfiniteTransition(label = "liveDot")
        val dotAlpha: State<Float> =
            transition.animateFloat(
                initialValue = 1f,
                targetValue = 0.2f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(800, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                label = "dotAlpha",
            )
        LiveDotShape(color, alphaProvider = { dotAlpha.value })
    } else {
        LiveDotShape(color, alphaProvider = { 1f })
    }
}

@Composable
private fun LiveDotShape(
    color: Color,
    alphaProvider: () -> Float,
) {
    Box(
        modifier =
            Modifier
                .size(LiveDotSize)
                .graphicsLayer { alpha = alphaProvider() }
                .background(color, CircleShape),
    )
}

@Composable
private fun TimeSeparator() {
    Text(
        text = stringResource(R.string.player_time_separator),
        style = MaterialTheme.typography.labelMedium,
        color = PlayerScrimContentSecondary,
        modifier = Modifier.padding(horizontal = 2.dp),
    )
}
