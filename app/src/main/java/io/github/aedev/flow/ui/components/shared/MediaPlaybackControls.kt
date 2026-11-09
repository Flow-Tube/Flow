package io.github.aedev.flow.ui.components.shared

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class PlaybackButtonType { PREVIOUS, PLAY_PAUSE, NEXT }

/**
 * Previous, play/pause and next as the music player draws them: elastic pills that trade width as one
 * is pressed, with a play/pause that morphs its corners between playing and paused.
 */
@Composable
fun MediaPlaybackControls(
    isPlaying: Boolean,
    isBuffering: Boolean,
    onPreviousClick: () -> Unit,
    onPlayPauseToggle: () -> Unit,
    onNextClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPreviewDirectionChange: (SkipDirection?) -> Unit = {},
    playPauseFocusRequester: FocusRequester? = null,
) {
    var isPressed by remember { mutableStateOf(false) }
    var isPreviousPressed by remember { mutableStateOf(false) }
    var isNextPressed by remember { mutableStateOf(false) }
    var lastClicked by remember { mutableStateOf<PlaybackButtonType?>(null) }
    var clickTrigger by remember { mutableIntStateOf(0) }
    val latestIsPlaying by rememberUpdatedState(isPlaying)
    val isPlayPauseLocked =
        lastClicked == PlaybackButtonType.NEXT || lastClicked == PlaybackButtonType.PREVIOUS
    var playPauseVisualState by remember { mutableStateOf(isPlaying) }
    var pendingPlayPauseState by remember { mutableStateOf<Boolean?>(null) }
    val hapticFeedback = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // On a remote the player opens with play/pause focused, where OK does the expected thing. A host
    // that passes its own requester places focus itself.
    val ownPlayPauseFocus = remember { FocusRequester() }
    val playPauseFocus = playPauseFocusRequester ?: ownPlayPauseFocus
    if (playPauseFocusRequester == null && LocalDpadNavigation.current) {
        LaunchedEffect(Unit) { ownPlayPauseFocus.requestFocus() }
    }

    LaunchedEffect(lastClicked, clickTrigger) {
        if (lastClicked != null) {
            val releaseDelay = if (lastClicked == PlaybackButtonType.PLAY_PAUSE) 220L else 600L
            delay(releaseDelay)
            lastClicked = null
        }
    }

    // Latch the icon while a skip is in flight so play/pause does not flicker on track changes.
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            pendingPlayPauseState = true
            return@LaunchedEffect
        }
        if (lastClicked != PlaybackButtonType.PLAY_PAUSE) {
            delay(220L)
        }
        if (!latestIsPlaying) {
            pendingPlayPauseState = false
        }
    }

    LaunchedEffect(isPlayPauseLocked, pendingPlayPauseState) {
        if (!isPlayPauseLocked) {
            pendingPlayPauseState?.let {
                playPauseVisualState = it
                pendingPlayPauseState = null
            }
        }
    }

    val elasticSpec = spring<Float>(dampingRatio = 0.62f, stiffness = 720f)

    val playPauseWeight by animateFloatAsState(
        targetValue =
            if (isPressed) {
                1.9f
            } else if (isPreviousPressed || isNextPressed) {
                1.1f
            } else {
                1.3f
            },
        animationSpec = elasticSpec,
        label = "playPauseWeight",
    )
    val previousWeight by animateFloatAsState(
        targetValue =
            if (isPreviousPressed) {
                0.65f
            } else if (isPressed) {
                0.35f
            } else {
                0.45f
            },
        animationSpec = elasticSpec,
        label = "previousWeight",
    )
    val nextWeight by animateFloatAsState(
        targetValue =
            if (isNextPressed) {
                0.65f
            } else if (isPressed) {
                0.35f
            } else {
                0.45f
            },
        animationSpec = elasticSpec,
        label = "nextWeight",
    )
    val playPauseCorner by animateDpAsState(
        targetValue = if (playPauseVisualState) 18.dp else 34.dp,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f),
        label = "playPauseCorner",
    )

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(68.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ElasticControlButton(
            weight = previousWeight,
            icon = Icons.Rounded.SkipPrevious,
            contentDescription = stringResource(R.string.previous),
            onClick = {
                lastClicked = PlaybackButtonType.PREVIOUS
                clickTrigger++
                scope.launch {
                    delay(180L)
                    onPreviousClick()
                }
            },
            onPressedChange = { isPreviousPressed = it },
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            iconSize = 30.dp,
            onLongPressStart = { onPreviewDirectionChange(SkipDirection.PREVIOUS) },
            onLongPressEnd = { onPreviewDirectionChange(null) },
        )

        ElasticControlButton(
            weight = playPauseWeight,
            icon = if (playPauseVisualState) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription =
                if (playPauseVisualState) stringResource(R.string.pause) else stringResource(R.string.play),
            onClick = {
                lastClicked = PlaybackButtonType.PLAY_PAUSE
                clickTrigger++
                hapticFeedback.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onPlayPauseToggle()
            },
            onPressedChange = { isPressed = it },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            iconSize = 36.dp,
            cornerRadius = playPauseCorner,
            isBuffering = isBuffering,
            modifier = Modifier.focusRequester(playPauseFocus),
        )

        ElasticControlButton(
            weight = nextWeight,
            icon = Icons.Rounded.SkipNext,
            contentDescription = stringResource(R.string.next),
            onClick = {
                lastClicked = PlaybackButtonType.NEXT
                clickTrigger++
                scope.launch {
                    delay(180L)
                    onNextClick()
                }
            },
            onPressedChange = { isNextPressed = it },
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            iconSize = 30.dp,
            onLongPressStart = { onPreviewDirectionChange(SkipDirection.NEXT) },
            onLongPressEnd = { onPreviewDirectionChange(null) },
        )
    }
}

@Composable
private fun RowScope.ElasticControlButton(
    weight: Float,
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    onPressedChange: (Boolean) -> Unit,
    containerColor: Color,
    contentColor: Color,
    iconSize: Dp,
    cornerRadius: Dp = 34.dp,
    isBuffering: Boolean = false,
    onLongPressStart: (() -> Unit)? = null,
    onLongPressEnd: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnPressedChange by rememberUpdatedState(onPressedChange)
    val currentLongPressStart by rememberUpdatedState(onLongPressStart)
    val currentLongPressEnd by rememberUpdatedState(onLongPressEnd)
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(cornerRadius)
    // The gesture below is touch only; these give the button a focus stop, a key press and a click
    // action, so a remote's OK, a keyboard's Enter and TalkBack reach it too.
    Box(
        modifier =
            modifier
                .weight(weight)
                .fillMaxHeight()
                .clip(shape)
                .background(containerColor)
                .indication(interactionSource, ripple(focusRingShape = shape, enablePressIndication = false))
                .semantics {
                    role = Role.Button
                    onClick {
                        currentOnClick()
                        true
                    }
                }.onKeyEvent { event ->
                    val isConfirm = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                    if (isConfirm && event.type == KeyEventType.KeyUp) {
                        currentOnClick()
                        true
                    } else {
                        false
                    }
                }.focusable(interactionSource = interactionSource)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        currentOnPressedChange(true)
                        var longPressed = false
                        try {
                            var cancelled = false
                            val upBeforeTimeout =
                                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                    val up = waitForUpOrCancellation()
                                    if (up == null) cancelled = true
                                    up
                                }
                            when {
                                cancelled -> {
                                    Unit
                                }

                                upBeforeTimeout != null -> {
                                    currentOnClick()
                                }

                                currentLongPressStart != null -> {
                                    longPressed = true
                                    currentLongPressStart?.invoke()
                                    waitForUpOrCancellation()
                                }

                                else -> {
                                    if (waitForUpOrCancellation() != null) {
                                        currentOnClick()
                                    }
                                }
                            }
                        } finally {
                            currentOnPressedChange(false)
                            if (longPressed) currentLongPressEnd?.invoke()
                        }
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        if (isBuffering) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = contentColor,
                strokeWidth = 3.dp,
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = contentColor,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
