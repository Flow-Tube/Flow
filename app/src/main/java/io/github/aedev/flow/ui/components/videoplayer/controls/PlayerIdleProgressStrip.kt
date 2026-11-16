package io.github.aedev.flow.ui.components.videoplayer.controls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import io.github.aedev.flow.data.local.IdleProgressBar
import io.github.aedev.flow.ui.components.shared.RestTrackHeight

/**
 * The thin progress bar the portrait player keeps while its controls are hidden (#1550).
 *
 * On the video it is the edge-aligned bar and takes touches. Below the video it hangs under the
 * box's bottom edge, outside the bounds any touch can reach, so it is drawn only: scrubbing waits
 * for the full bar that comes with the controls.
 */
@Composable
internal fun BoxScope.PlayerIdleProgressStrip(
    position: IdleProgressBar,
    visible: Boolean,
    fadeSpec: FiniteAnimationSpec<Float>,
    positionProvider: () -> Long,
    duration: Long,
    isLive: Boolean,
    content: PlayerSeekbarContent,
    horizontalPadding: Dp,
    onScrubProgress: (progress: Float, duration: Long) -> Unit,
    onScrubFinished: () -> Unit,
    isScrubbing: Boolean,
) {
    if (position == IdleProgressBar.OFF) return
    val below = position == IdleProgressBar.BELOW_VIDEO
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(fadeSpec),
        exit = fadeOut(fadeSpec),
        modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .then(if (below) Modifier.hangBelowEdge() else Modifier),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            PlayerSeekbarRow(
                positionProvider = positionProvider,
                duration = duration,
                isLive = isLive,
                content = content,
                edgeAligned = true,
                horizontalPadding = horizontalPadding,
                onScrubProgress = onScrubProgress,
                onScrubFinished = onScrubFinished,
                isScrubbing = isScrubbing,
                interactive = !below,
            )
        }
    }
}

/** Moves the painted band, which sits at the bottom of the row, to start just under the box edge. */
private fun Modifier.hangBelowEdge(): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            placeable.place(0, minOf(placeable.height, RestTrackHeight.roundToPx()))
        }
    }
