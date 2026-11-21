package io.github.aedev.flow.ui.components.home

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.ClosedCaptionOff
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.player.stream.StoryboardLevel
import io.github.aedev.flow.player.stream.StoryboardSpec
import io.github.aedev.flow.ui.components.shared.FlowSpinner
import io.github.aedev.flow.ui.components.shared.MediaSeekBar
import io.github.aedev.flow.ui.components.shared.MediaTextBadge
import io.github.aedev.flow.ui.components.shared.MediaThumbnailDefaults
import io.github.aedev.flow.ui.components.videoplayer.controls.SeekPreviewThumbnail
import io.github.aedev.flow.ui.components.videoplayer.controls.anchoredToThumb
import io.github.aedev.flow.ui.components.videoplayer.gesture.DOUBLE_TAP_SEEK_WINDOW_MS
import io.github.aedev.flow.ui.components.videoplayer.overlay.SeekAnimationOverlay
import io.github.aedev.flow.ui.theme.PlayerScrimAffordance
import io.github.aedev.flow.ui.theme.PlayerScrimContent
import io.github.aedev.flow.utils.formatDuration
import kotlinx.coroutines.delay

private val ControlsPadding = 8.dp
private val TimeBarTouchHeight = 48.dp
private val SpinnerSize = 14.dp
private val BadgeAboveBar = 12.dp
private val ScrubPreviewWidth = 112.dp

/** What the controls show; the position is read separately, so a tick never rebuilds this. */
@Immutable
internal data class HomePreviewControlsState(
    val muted: Boolean,
    val hasCaptions: Boolean,
    val captionsOn: Boolean,
    val buffering: Boolean,
    val playing: Boolean,
    val durationMs: Long,
    val storyboard: List<StoryboardLevel>,
)

private class SeekFeedback(
    val forward: Boolean,
    val seconds: Int,
)

/**
 * A playing preview's controls, laid over its card: mute and captions at the top end, a scrubbable
 * time bar on the bottom edge, the time left where the duration badge was, double tap to seek and a
 * single tap to open. The position is polled only while the preview plays and this is composed.
 */
@Composable
internal fun HomePreviewControls(
    state: HomePreviewControlsState,
    readPositionMs: () -> Long,
    onToggleMute: () -> Unit,
    onToggleCaptions: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var positionMs by remember { mutableLongStateOf(readPositionMs()) }
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    var lastTouchMs by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    var idle by remember { mutableStateOf(false) }
    var seekFeedback by remember { mutableStateOf<SeekFeedback?>(null) }
    val scrubbing = scrubMs != null
    val currentRead by rememberUpdatedState(readPositionMs)

    LaunchedEffect(state.playing, scrubbing) {
        while (state.playing && !scrubbing) {
            positionMs = currentRead()
            delay(HomePreviewControlsPolicy.POSITION_TICK_MS)
        }
    }
    LaunchedEffect(lastTouchMs, scrubbing) {
        idle = false
        if (!scrubbing) {
            delay(HomePreviewControlsPolicy.AUTO_HIDE_MS)
            idle = true
        }
    }
    LaunchedEffect(seekFeedback) {
        if (seekFeedback != null) {
            delay(DOUBLE_TAP_SEEK_WINDOW_MS)
            seekFeedback = null
        }
    }

    val shown =
        HomePreviewControlsPolicy.shown(
            hasCaptions = state.hasCaptions,
            idleMs = if (idle) HomePreviewControlsPolicy.AUTO_HIDE_MS else 0L,
            scrubbing = scrubbing,
        )
    val remaining by remember(state.durationMs) {
        derivedStateOf { HomePreviewControlsPolicy.remainingSeconds(scrubMs ?: positionMs, state.durationMs) }
    }
    val currentState by rememberUpdatedState(state)
    val currentOnSeekTo by rememberUpdatedState(onSeekTo)
    val currentOnOpen by rememberUpdatedState(onOpen)

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { currentOnOpen() },
                            onDoubleTap = { offset ->
                                val delta = HomePreviewControlsPolicy.doubleTapSeekMs(offset.x, size.width.toFloat())
                                val target = HomePreviewControlsPolicy.seekTarget(positionMs, delta, currentState.durationMs)
                                positionMs = target
                                currentOnSeekTo(target)
                                val forward = delta > 0
                                val previous = seekFeedback?.takeIf { it.forward == forward }
                                val step = (HomePreviewControlsPolicy.SEEK_STEP_MS / MILLIS_PER_SECOND).toInt()
                                seekFeedback = SeekFeedback(forward, (previous?.seconds ?: 0) + step)
                                lastTouchMs = SystemClock.uptimeMillis()
                            },
                        )
                    },
        )
        SeekAnimationOverlay(
            showSeekBack = seekFeedback?.forward == false,
            showSeekForward = seekFeedback?.forward == true,
            seekSeconds = seekFeedback?.seconds ?: 0,
            zoneFraction = HALF,
        )

        Column(
            modifier = Modifier.align(Alignment.TopEnd).padding(ControlsPadding),
            verticalArrangement = Arrangement.spacedBy(ControlsPadding),
        ) {
            PreviewIconButton(
                icon = if (state.muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                contentDescription = stringResource(if (state.muted) R.string.home_preview_unmute else R.string.home_preview_mute),
                onClick = {
                    lastTouchMs = SystemClock.uptimeMillis()
                    onToggleMute()
                },
            )
            AnimatedVisibility(
                visible = shown.captions,
                enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
                exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
            ) {
                PreviewIconButton(
                    icon = if (state.captionsOn) Icons.Rounded.ClosedCaption else Icons.Rounded.ClosedCaptionOff,
                    contentDescription =
                        stringResource(if (state.captionsOn) R.string.home_preview_captions_off else R.string.home_preview_captions_on),
                    onClick = {
                        lastTouchMs = SystemClock.uptimeMillis()
                        onToggleCaptions()
                    },
                )
            }
        }

        if (shown.badge) {
            Row(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = MediaThumbnailDefaults.BadgePadding, bottom = BadgeAboveBar),
                horizontalArrangement = Arrangement.spacedBy(MediaThumbnailDefaults.BadgePadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.buffering) FlowSpinner(modifier = Modifier.size(SpinnerSize), color = PlayerScrimContent)
                MediaTextBadge(text = formatDuration(remaining))
            }
        }

        val scrubAt = scrubMs
        if (scrubAt != null && state.storyboard.isNotEmpty()) {
            val targetPx = with(LocalDensity.current) { ScrubPreviewWidth.roundToPx() }
            val level = remember(state.storyboard, targetPx) { StoryboardSpec.levelFor(state.storyboard, targetPx) }
            if (level != null) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = TimeBarTouchHeight)
                            .fillMaxWidth()
                            .anchoredToThumb({ scrubMs ?: 0L }, state.durationMs),
                ) {
                    SeekPreviewThumbnail(level = level, positionProvider = { scrubMs ?: 0L }, width = ScrubPreviewWidth)
                }
            }
        }

        MediaSeekBar(
            value = { fractionOf(scrubMs ?: positionMs, state.durationMs) },
            onValueChange = { fraction ->
                scrubMs = (fraction * state.durationMs).toLong()
                lastTouchMs = SystemClock.uptimeMillis()
            },
            onValueChangeFinished = {
                scrubMs?.let { target ->
                    positionMs = target
                    onSeekTo(target)
                }
                scrubMs = null
            },
            duration = state.durationMs,
            edgeAligned = true,
            edgeAlignedHeight = TimeBarTouchHeight,
            // A full-width card runs the bar to the screen edge, where a drag would otherwise start the back gesture.
            modifier = Modifier.align(Alignment.BottomCenter).systemGestureExclusion(),
        )
    }
}

@Composable
private fun PreviewIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    FilledIconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = PlayerScrimAffordance, contentColor = PlayerScrimContent),
        modifier = Modifier.size(IconButtonDefaults.extraSmallContainerSize()),
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, modifier = Modifier.size(IconButtonDefaults.extraSmallIconSize))
    }
}

private fun fractionOf(
    positionMs: Long,
    durationMs: Long,
): Float = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

private const val HALF = 0.5f
private const val MILLIS_PER_SECOND = 1_000L
