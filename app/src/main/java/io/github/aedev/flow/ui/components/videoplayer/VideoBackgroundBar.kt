package io.github.aedev.flow.ui.components.videoplayer

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.MiniBarSwipeAction
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.ui.components.shared.MediaMiniBarBounds
import io.github.aedev.flow.ui.components.shared.MediaMiniBarDefaults
import io.github.aedev.flow.ui.components.shared.MediaMiniBarSwipeReveal
import io.github.aedev.flow.ui.components.shared.MediaPlayPauseButton
import io.github.aedev.flow.ui.components.shared.MediaThumbnail
import io.github.aedev.flow.ui.components.shared.MiniBarReveal
import io.github.aedev.flow.ui.components.shared.icon
import io.github.aedev.flow.ui.components.shared.isDestructive
import io.github.aedev.flow.ui.components.shared.labelRes
import io.github.aedev.flow.ui.components.shared.mediaMiniBarSwipe
import io.github.aedev.flow.ui.components.shared.rememberMediaMiniBarSwipeHandler
import io.github.aedev.flow.ui.components.videoplayer.motion.playerOpenSpringSpec
import io.github.aedev.flow.ui.components.videoplayer.motion.withSteadyFrames
import io.github.aedev.flow.ui.screens.player.state.hasVisibleQueue
import io.github.aedev.flow.ui.theme.PlayerGround
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val ThumbnailWidth = 78.dp
private val ThumbnailStart = 10.dp
private val BarShadowElevation = 6.dp
private const val CLOSE_PULL_HEIGHTS = 0.5f
private const val CLOSE_FLING_VELOCITY = 800f

/** How far the card has grown before it takes on the player's status bar strip. */
private const val STATUS_STRIP_FROM = 0.8f

/** How far the card has grown when the player is started underneath it. */
private const val RESTORE_AT_FRACTION = 0.98f

/** A tap opens as a gentle swipe up would, so it reads as the same motion. */
private const val EXPAND_TAP_VELOCITY = 1000f

/**
 * The mini bar for a video playing in the background, in the music bar's slot and shape. A tap or
 * a swipe up grows it into the player the way the music bar grows into its own: the card stretches
 * to the window under the finger and the thumbnail moves into the video's place. [onRestore] runs
 * the moment the open is decided, with the window bounds the thumbnail is heading for, so the player
 * builds underneath once the card covers the window. A swipe down or sideways closes it.
 *
 * The gestures sit on the bar's resting box, outside every moving layer, so the finger is measured
 * against the window and not against a card that follows it.
 */
@Composable
internal fun VideoBackgroundBar(
    video: Video,
    bounds: MediaMiniBarBounds,
    containerWidthPx: Float,
    containerHeightPx: Float,
    restingBottomPx: () -> Float,
    dockShiftPx: () -> Float,
    onRestore: (videoWindowBounds: Rect) -> Unit,
    onClose: () -> Unit,
    onOpenQueue: () -> Unit,
    swipeLeftAction: MiniBarSwipeAction,
    swipeRightAction: MiniBarSwipeAction,
    swipeActions: VideoBarSwipeActions,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val heightPx = with(density) { MediaMiniBarDefaults.Height.toPx() }
    val spacerPx = with(density) { MediaMiniBarDefaults.BottomSpacer.toPx() }
    val cornerRadiusPx = with(density) { MediaMiniBarDefaults.CornerRadius.toPx() }
    val shadowPx = with(density) { BarShadowElevation.toPx() }
    val statusBarPx = WindowInsets.statusBars.getTop(density).toFloat()
    val thumbnailAtRest =
        with(density) {
            val width = ThumbnailWidth.toPx()
            val height = width * 9f / 16f
            Rect(ThumbnailStart.toPx(), (heightPx - height) / 2f, ThumbnailStart.toPx() + width, (heightPx + height) / 2f)
        }
    val playerState by EnhancedPlayerManager.getInstance().playerState.collectAsStateWithLifecycle()
    val hasQueue = hasVisibleQueue(playerState.queueTitle, playerState.queueSize)
    val motion = MaterialTheme.motionScheme
    val onRestoreState = rememberUpdatedState(onRestore)

    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, motion.defaultEffectsSpec()) }
    val pullY = remember { Animatable(0f) }
    val expand = remember { Animatable(0f) }
    val swipeX = remember { Animatable(0f) }
    val isExpanding by remember { derivedStateOf { expand.value > 0f } }
    val swipeHandler =
        rememberMediaMiniBarSwipeHandler(
            scope = scope,
            density = density,
            hapticFeedback = LocalHapticFeedback.current,
            offsetAnimatable = swipeX,
            screenWidthPx = containerWidthPx,
            onCommit = { towardsLeft -> swipeActions.commit(if (towardsLeft) swipeLeftAction else swipeRightAction) },
        )

    val restingTop = { containerHeightPx - restingBottomPx() - spacerPx - heightPx }
    val barStart = { bounds.start + dockShiftPx() }
    val barWidth = { (bounds.width - dockShiftPx()).coerceAtLeast(0f) }
    val frameAt = { fraction: Float ->
        val top = restingTop()
        backgroundBarExpandFrame(
            fraction = fraction,
            bar = Rect(barStart(), top, barStart() + barWidth(), top + heightPx),
            containerWidth = containerWidthPx,
            containerHeight = containerHeightPx,
            statusBarHeight = statusBarPx,
            barCornerRadiusPx = cornerRadiusPx,
            thumbnailAtRest = thumbnailAtRest,
        )
    }
    val frame = { frameAt(expand.value) }

    val containerOrigin = remember { floatArrayOf(0f, 0f) }

    fun settleExpand(
        fraction: Float,
        velocityY: Float,
    ) {
        val travel = restingTop().coerceAtLeast(1f)
        val expanding = shouldExpandBackgroundBar(fraction, velocityY)
        var restored = false

        // Building the player holds the main thread for a few hundred ms. Started once the card
        // covers the window, that pause lands in the spring's last, invisible stretch.
        fun restoreOnce() {
            if (restored) return
            restored = true
            // Grown, the card is the container, so its thumbnail is already in container coordinates.
            onRestoreState.value(frameAt(1f).thumbnail.translate(containerOrigin[0], containerOrigin[1]))
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            expand.snapTo(fraction)
            withSteadyFrames {
                expand.animateTo(if (expanding) 1f else 0f, playerOpenSpringSpec, -velocityY / travel) {
                    if (expanding && value >= RESTORE_AT_FRACTION) restoreOnce()
                }
            }
            if (expanding) restoreOnce()
        }
    }

    val surfaceColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val expandedColor = MaterialTheme.colorScheme.background

    Box(
        modifier =
            Modifier
                .offset { IntOffset(barStart().roundToInt(), restingTop().roundToInt()) }
                .layout { measurable, _ ->
                    val width = barWidth().roundToInt()
                    val height = heightPx.roundToInt()
                    val placeable = measurable.measure(Constraints.fixed(width, height))
                    layout(width, height) { placeable.place(0, 0) }
                }.onPlaced { coordinates ->
                    val position = coordinates.positionInWindow()
                    containerOrigin[0] = position.x - barStart()
                    containerOrigin[1] = position.y - restingTop()
                }.mediaMiniBarSwipe(enabled = true, handler = swipeHandler)
                .pointerInput(Unit) {
                    val tracker = VelocityTracker()
                    // Read and written on the gesture's own thread, so a fling that ends before the
                    // animatables catch up is still judged by where the finger actually took the bar.
                    var pulled = 0f
                    var grown = 0f
                    detectVerticalDragGestures(
                        onDragStart = {
                            tracker.resetTracking()
                            pulled = pullY.value
                            grown = expand.value
                        },
                        onVerticalDrag = { change, amount ->
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            if (pulled > 0f || (grown == 0f && amount > 0f)) {
                                pulled = (pulled + amount).coerceAtLeast(0f)
                                scope.launch(start = CoroutineStart.UNDISPATCHED) { pullY.snapTo(pulled) }
                            } else {
                                grown = (grown - amount / restingTop().coerceAtLeast(1f)).coerceIn(0f, 1f)
                                scope.launch(start = CoroutineStart.UNDISPATCHED) { expand.snapTo(grown) }
                            }
                        },
                        onDragEnd = {
                            val velocity = tracker.calculateVelocity().y
                            when {
                                pulled > 0f -> {
                                    scope.launch {
                                        if (pulled > heightPx * CLOSE_PULL_HEIGHTS || velocity > CLOSE_FLING_VELOCITY) {
                                            pullY.animateTo(heightPx * 2f, motion.fastSpatialSpec(), velocity.coerceAtLeast(0f))
                                            onClose()
                                        } else {
                                            pullY.animateTo(0f, motion.defaultSpatialSpec(), velocity)
                                        }
                                    }
                                }

                                grown > 0f -> {
                                    settleExpand(grown, velocity)
                                }
                            }
                        },
                        onDragCancel = {
                            scope.launch { pullY.animateTo(0f, motion.defaultSpatialSpec()) }
                            if (grown > 0f) settleExpand(grown, 0f)
                        },
                    )
                }.clickable { if (!isExpanding) settleExpand(0f, -EXPAND_TAP_VELOCITY) },
    ) {
        if (!isExpanding) {
            MediaMiniBarSwipeReveal(
                offset = { swipeX.value },
                towardsLeft = videoBarReveal(swipeLeftAction, swipeActions),
                towardsRight = videoBarReveal(swipeRightAction, swipeActions),
                shape = RoundedCornerShape(MediaMiniBarDefaults.CornerRadius),
            )
        }
        Box(
            modifier =
                Modifier
                    .layout { measurable, constraints ->
                        val current = frame()
                        val placeable =
                            measurable.measure(
                                Constraints.fixed(
                                    current.card.width
                                        .roundToInt()
                                        .coerceAtLeast(1),
                                    current.card.height
                                        .roundToInt()
                                        .coerceAtLeast(1),
                                ),
                            )
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            val top = restingTop()
                            placeable.place(
                                (current.card.left - barStart()).roundToInt(),
                                (current.card.top - top).roundToInt(),
                            )
                        }
                    }.graphicsLayer {
                        val current = frame()
                        translationX = swipeX.value
                        translationY = pullY.value + (1f - appear.value) * heightPx
                        alpha = appear.value * (1f - (pullY.value / (heightPx * 2f)).coerceIn(0f, 1f))
                        shape = RoundedCornerShape(current.cornerRadiusPx)
                        clip = true
                        shadowElevation = lerp(shadowPx, 0f, expand.value.coerceIn(0f, 1f))
                    }.drawBehind {
                        val grown = expand.value.coerceIn(0f, 1f)
                        drawRect(lerp(surfaceColor, expandedColor, grown))
                        // The player's ground draws the status bar strip in this colour; matching it
                        // makes the hand-off invisible there too.
                        drawRect(
                            PlayerGround,
                            size = Size(size.width, statusBarPx),
                            alpha =
                                (
                                    (grown - STATUS_STRIP_FROM) /
                                        (1f - STATUS_STRIP_FROM)
                                ).coerceIn(0f, 1f),
                        )
                    },
        ) {
            BackgroundBarContent(
                video = video,
                isPlaying = playerState.playWhenReady,
                isBuffering = playerState.isBuffering,
                hasQueue = hasQueue,
                onOpenQueue = onOpenQueue,
                barWidthPx = barWidth,
                barHeightPx = heightPx,
                frame = frame,
            )
        }
    }
}

@Composable
private fun BackgroundBarContent(
    video: Video,
    isPlaying: Boolean,
    isBuffering: Boolean,
    hasQueue: Boolean,
    onOpenQueue: () -> Unit,
    barWidthPx: () -> Float,
    barHeightPx: Float,
    frame: () -> BackgroundBarExpandFrame,
) {
    val chrome = Modifier.graphicsLayer { alpha = frame().chromeAlpha }
    Box(
        modifier =
            Modifier.layout { measurable, _ ->
                val placeable = measurable.measure(Constraints.fixed(barWidthPx().roundToInt(), barHeightPx.roundToInt()))
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            },
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(start = ThumbnailStart, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(modifier = Modifier.width(ThumbnailWidth))
            Column(modifier = Modifier.weight(1f).then(chrome)) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = video.channelName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MediaPlayPauseButton(
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                onClick = {
                    val manager = EnhancedPlayerManager.getInstance()
                    if (isPlaying) manager.pause() else manager.play()
                },
                modifier = chrome,
            )
            if (hasQueue) {
                IconButton(onClick = onOpenQueue, modifier = chrome) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                        contentDescription = stringResource(R.string.playlist_queue),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        BackgroundBarProgress(
            isPlaying = isPlaying,
            modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp).then(chrome),
        )
    }
    MediaThumbnail(
        videoId = video.id,
        thumbnailUrl = video.thumbnailUrl,
        width = ThumbnailWidth,
        shape = MaterialTheme.shapes.medium,
        modifier =
            Modifier.layout { measurable, _ ->
                val thumbnail = frame().thumbnail
                val placeable =
                    measurable.measure(
                        Constraints.fixed(
                            thumbnail.width.roundToInt().coerceAtLeast(1),
                            thumbnail.height.roundToInt().coerceAtLeast(1),
                        ),
                    )
                layout(placeable.width, placeable.height) {
                    placeable.place(thumbnail.left.roundToInt(), thumbnail.top.roundToInt())
                }
            },
    )
}

/** What a swipe towards one side uncovers: the video it moves to, or the action. */
@Composable
private fun videoBarReveal(
    action: MiniBarSwipeAction,
    actions: VideoBarSwipeActions,
): MiniBarReveal {
    val peek = actions.peek(action)
    val available = actions.isAvailable(action)
    val label =
        when {
            peek != null -> peek.title
            !available && action == MiniBarSwipeAction.NEXT -> stringResource(R.string.mini_bar_nothing_next)
            !available && action == MiniBarSwipeAction.PREVIOUS -> stringResource(R.string.mini_bar_nothing_previous)
            else -> stringResource(action.labelRes)
        }
    return MiniBarReveal(
        icon = action.icon,
        label = label,
        destructive = action.isDestructive,
        enabled = available,
        peekImageUrl = peek?.thumbnailUrl,
    )
}

/**
 * Playback progress along the bottom edge. Nothing publishes the position while the player is
 * hidden, so it is read once a second, and only while the video plays and the app is in view.
 */
@Composable
private fun BackgroundBarProgress(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val playback by rememberPolledPlayback(isPlaying)
    LinearProgressIndicator(
        progress = { playback.fraction },
        modifier = modifier.fillMaxWidth().height(2.dp),
        color = MaterialTheme.colorScheme.primary,
        trackColor = Color.Transparent,
        drawStopIndicator = {},
        gapSize = 0.dp,
    )
}
