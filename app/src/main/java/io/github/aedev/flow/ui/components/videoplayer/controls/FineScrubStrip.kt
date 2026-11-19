package io.github.aedev.flow.ui.components.videoplayer.controls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.player.stream.StoryboardLevel
import io.github.aedev.flow.player.stream.StoryboardSpec
import io.github.aedev.flow.ui.components.videoplayer.motion.PlayerGestureThresholds
import io.github.aedev.flow.ui.theme.PlayerScrimContent
import io.github.aedev.flow.ui.theme.PlayerScrimPanel
import io.github.aedev.flow.utils.formatDurationMillis
import kotlinx.coroutines.flow.drop
import kotlin.math.abs

private val FineScrubFrameWidth = 72.dp
private val FineScrubFrameGap = 4.dp

/** Upward travel during a seek bar drag past which the pull is watched as a request for fine mode. */
private val FineScrubPullStart = 16.dp

/** Upward travel that opens fine mode. */
private val FineScrubPullCommit = 45.dp

/** Where fine mode was opened from: the position it started at and whether the video was playing. */
internal class FineScrubSession(
    val startMs: Long,
    val wasPlaying: Boolean,
)

/**
 * Fine scrubbing: a pull up during a seek bar drag pauses the video and opens a strip of storyboard
 * frames, one per storyboard interval, that seeks as it scrolls and stays open after the finger
 * lifts. It closes with its close button, a fling down, or playback starting, and playback resumes
 * if it was playing when it opened. Null [open] means the video has no storyboard to scrub through.
 */
internal class FineScrubController(
    val level: StoryboardLevel?,
    val session: FineScrubSession?,
    val open: (() -> Unit)?,
    val close: (resume: Boolean) -> Unit,
)

@Composable
internal fun rememberFineScrubController(
    storyboard: List<StoryboardLevel>,
    durationMs: Long,
    isLive: Boolean,
    isPlaying: Boolean,
    positionProvider: () -> Long,
    onPlayPause: () -> Unit,
    onScrubFinished: () -> Unit,
): FineScrubController {
    val frameWidthPx = with(LocalDensity.current) { FineScrubFrameWidth.roundToPx() }
    val level = remember(storyboard, frameWidthPx) { StoryboardSpec.levelFor(storyboard, frameWidthPx) }
    var session by remember { mutableStateOf<FineScrubSession?>(null) }
    val currentPlayPause by rememberUpdatedState(onPlayPause)
    val currentScrubFinished by rememberUpdatedState(onScrubFinished)
    val close: (Boolean) -> Unit = { resume ->
        session?.let {
            currentScrubFinished()
            if (resume && it.wasPlaying) currentPlayPause()
        }
        session = null
    }
    // Starting playback is one of the ways out; it is already playing, so nothing is resumed.
    LaunchedEffect(isPlaying) {
        if (isPlaying && session != null) close(false)
    }
    val usable = level != null && !isLive && durationMs > 0L
    val open: (() -> Unit)? =
        if (usable) {
            {
                if (session == null) {
                    val wasPlaying = isPlaying
                    session = FineScrubSession(startMs = positionProvider(), wasPlaying = wasPlaying)
                    if (wasPlaying) currentPlayPause()
                }
            }
        } else {
            null
        }
    return FineScrubController(level = level, session = session, open = open, close = close)
}

/**
 * Watches a seek bar drag, without taking it, for a clearly vertical pull up; past
 * [FineScrubPullCommit] it plays a haptic, takes the gesture over so the seek bar lets go, and
 * calls [onOpen].
 */
internal fun Modifier.fineScrubPull(onOpen: (() -> Unit)?): Modifier =
    if (onOpen == null) {
        this
    } else {
        pointerInput(onOpen) {
            val startPx = FineScrubPullStart.toPx()
            val commitPx = FineScrubPullCommit.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var pullFrom: Offset? = null
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    if (!change.pressed) return@awaitEachGesture
                    val rise = down.position.y - change.position.y
                    if (pullFrom == null && rise > startPx) pullFrom = change.position
                    val from = pullFrom ?: continue
                    if (abs(change.position.x - from.x) * 2f > (from.y - change.position.y).coerceAtLeast(0f) + startPx) {
                        pullFrom = null
                        continue
                    }
                    if (rise >= commitPx) {
                        event.changes.forEach { it.consume() }
                        onOpen()
                        break
                    }
                }
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                    if (event.changes.none { it.pressed }) break
                }
            }
        }
    }

/** The strip of frames while fine mode is open, over the middle of the player. */
@Composable
internal fun BoxScope.FineScrubLayer(
    controller: FineScrubController,
    durationMs: Long,
    onScrubProgress: (progress: Float, duration: Long) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val session = controller.session
    val level = controller.level
    LaunchedEffect(session) {
        if (session != null) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
    }
    AnimatedVisibility(
        visible = session != null && level != null,
        enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
        exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
        modifier = Modifier.align(Alignment.Center),
    ) {
        if (session != null && level != null) {
            FineScrubStrip(
                level = level,
                durationMs = durationMs,
                startPositionMs = session.startMs,
                onScrub = { positionMs -> onScrubProgress(positionMs.toFloat() / durationMs, durationMs) },
                onClose = { controller.close(true) },
            )
        }
    }
}

@Composable
private fun FineScrubStrip(
    level: StoryboardLevel,
    durationMs: Long,
    startPositionMs: Long,
    onScrub: (positionMs: Long) -> Unit,
    onClose: () -> Unit,
) {
    val density = LocalDensity.current
    val itemWidthPx = with(density) { (FineScrubFrameWidth + FineScrubFrameGap).toPx() }
    val interval = level.intervalMs.coerceAtLeast(1L)
    val frameCount = ((durationMs + interval - 1) / interval).toInt().coerceAtLeast(1)
    val startFrame = (startPositionMs / interval).toInt().coerceIn(0, frameCount - 1)
    val listState =
        rememberLazyListState(
            initialFirstVisibleItemIndex = startFrame,
            initialFirstVisibleItemScrollOffset = ((startPositionMs % interval).toFloat() / interval * itemWidthPx).toInt(),
        )
    val positionMs by remember(listState, interval, durationMs, itemWidthPx) {
        derivedStateOf {
            val frames = listState.firstVisibleItemIndex + listState.firstVisibleItemScrollOffset / itemWidthPx
            (frames * interval).toLong().coerceIn(0L, durationMs)
        }
    }
    val currentOnScrub by rememberUpdatedState(onScrub)
    LaunchedEffect(listState) {
        snapshotFlow { positionMs }.drop(1).collect { currentOnScrub(it) }
    }
    val shownSecond by remember { derivedStateOf { positionMs / 1_000L } }
    val closeVelocity = with(density) { PlayerGestureThresholds(this.density).collapseFlingVelocity }
    val frameHeight = FineScrubFrameWidth * (level.thumbnailHeight.toFloat() / level.thumbnailWidth.coerceAtLeast(1))

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .background(PlayerScrimPanel, MaterialTheme.shapes.large)
                .draggable(
                    state = rememberDraggableState { },
                    orientation = Orientation.Vertical,
                    onDragStopped = { velocity -> if (velocity > closeVelocity) onClose() },
                ),
    ) {
        val sidePadding = ((maxWidth - FineScrubFrameWidth - FineScrubFrameGap) / 2).coerceAtLeast(0.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = formatDurationMillis(shownSecond * 1_000L, padMinutes = true),
                    color = PlayerScrimContent,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.Center).padding(vertical = 10.dp),
                )
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = PlayerScrimContent,
                    )
                }
            }
            Box(modifier = Modifier.padding(bottom = 12.dp)) {
                LazyRow(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = sidePadding),
                ) {
                    items(frameCount) { index ->
                        Box(modifier = Modifier.width(FineScrubFrameWidth + FineScrubFrameGap), contentAlignment = Alignment.Center) {
                            SeekPreviewThumbnail(
                                level = level,
                                positionProvider = { index * interval },
                                width = FineScrubFrameWidth,
                            )
                        }
                    }
                }
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .width(2.dp)
                            .height(frameHeight + 8.dp)
                            .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}
