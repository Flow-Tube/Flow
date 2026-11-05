package io.github.aedev.flow.ui.components.musicplayer.queue

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Explicit
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.components.PlayingWaveform
import io.github.aedev.flow.ui.components.musicplayer.common.formatTime
import io.github.aedev.flow.ui.components.musicplayer.motion.QueueRowSwipeGestureHandler

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RadioLoadingRow() {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        LoadingIndicator(
            modifier = Modifier.size(30.dp),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
internal fun QueueTrackRow(
    track: MusicTrack,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isRadioItem: Boolean,
    isDragging: Boolean,
    swipeEnabled: Boolean,
    isDownloaded: Boolean,
    leftSwipe: QueueRowSwipe,
    rightSwipe: QueueRowSwipe,
    rowKey: Any,
    onClick: () -> Unit,
    onSwipe: (QueueRowSwipe) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current

    var rowSizePx by remember { mutableStateOf(IntSize.Zero) }
    val offsetX = remember(rowKey) { Animatable(0f) }
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    val currentLeftSwipe by rememberUpdatedState(leftSwipe)
    val currentRightSwipe by rememberUpdatedState(rightSwipe)
    val swipeHandler =
        remember(rowKey) {
            QueueRowSwipeGestureHandler(
                scope = scope,
                offset = offsetX,
                density = density.density,
                itemWidthPx = { rowSizePx.width.toFloat() },
                haptics = haptics,
                // A radio row joins the queue on either swipe, so it leaves the radio list too.
                flyOffOnCommit = { towardsLeft ->
                    isRadioItem || (if (towardsLeft) currentLeftSwipe else currentRightSwipe).leavesList
                },
                onCommit = { towardsLeft -> currentOnSwipe(if (towardsLeft) currentLeftSwipe else currentRightSwipe) },
            )
        }

    val rowCorner by animateDpAsState(
        targetValue = if (isCurrent) 30.dp else 14.dp,
        label = "queueRowCorner",
    )
    val artCorner by animateDpAsState(
        targetValue = if (isCurrent) 22.dp else 10.dp,
        label = "queueArtCorner",
    )
    val elevation by animateDpAsState(
        targetValue = if (isDragging) 6.dp else 0.dp,
        label = "queueRowElevation",
    )

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .onSizeChanged { rowSizePx = it },
    ) {
        SwipeRevealPanel(
            offsetX = offsetX.value,
            swipe = if (offsetX.value < 0f) leftSwipe else rightSwipe,
            rowHeightPx = rowSizePx.height,
            isTargeted = swipeHandler.isInCommitZone,
        )

        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer { translationX = offsetX.value }
                    .clip(RoundedCornerShape(rowCorner))
                    .clickable(enabled = offsetX.value == 0f, onClick = onClick)
                    .then(
                        if (swipeEnabled) {
                            Modifier.pointerInput(rowKey, swipeHandler) {
                                detectHorizontalDragGestures(
                                    onDragStart = { swipeHandler.onDragStart() },
                                    onHorizontalDrag = { change, dragAmount ->
                                        change.consume()
                                        swipeHandler.onDrag(dragAmount)
                                    },
                                    onDragEnd = { swipeHandler.onDragEnd() },
                                    onDragCancel = { swipeHandler.onDragCancel() },
                                )
                            }
                        } else {
                            Modifier
                        },
                    ),
            shape = RoundedCornerShape(rowCorner),
            color =
                if (isCurrent) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.55f)
                },
            tonalElevation = elevation,
            shadowElevation = elevation,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(artCorner)),
                ) {
                    AsyncImage(
                        model = track.listThumbnailUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    if (isCurrent) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.35f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            PlayingWaveform(
                                color = Color.White.copy(alpha = 0.9f),
                                animate = isPlaying,
                                barCount = 3,
                                barWidth = 2.5.dp,
                                barSpacing = 1.5.dp,
                                staggerMillis = 120,
                            )
                        }
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color =
                            if (isCurrent) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (track.isExplicit == true) {
                            Icon(
                                imageVector = Icons.Outlined.Explicit,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        Text(
                            text = track.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (track.duration > 0) {
                            Text(
                                text = "• " + formatTime(track.duration * 1000L),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
                if (isDownloaded) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = stringResource(R.string.downloaded),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                if (isRadioItem) {
                    Icon(
                        imageVector = Icons.Rounded.Sensors,
                        contentDescription = stringResource(R.string.music_endless_radio_title),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                        modifier =
                            Modifier
                                .padding(end = 4.dp)
                                .size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxScope.SwipeRevealPanel(
    offsetX: Float,
    swipe: QueueRowSwipe,
    rowHeightPx: Int,
    isTargeted: Boolean,
) {
    if (offsetX == 0f) return
    val density = LocalDensity.current
    val revealWidthPx = kotlin.math.abs(offsetX)
    val revealProgress = (revealWidthPx / (56.dp.value * density.density)).coerceIn(0f, 1f)
    // The row moves by a physical offset, so the panel ignores the layout direction too.
    val towardLeft = offsetX < 0f

    val containerColor = swipe.containerColor
    val contentColor = swipe.contentColor
    val backgroundColor by animateColorAsState(
        targetValue = if (isTargeted) containerColor else containerColor.copy(alpha = 0.82f),
        animationSpec = tween(durationMillis = 150),
        label = "queueSwipeRevealColor",
    )
    val iconAlpha by animateFloatAsState(
        targetValue = revealProgress * if (isTargeted) 1f else 0.88f,
        animationSpec = tween(durationMillis = 120),
        label = "queueSwipeIconAlpha",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (isTargeted) 1.1f else 0.95f,
        animationSpec = tween(durationMillis = 120),
        label = "queueSwipeIconScale",
    )

    val revealWidthDp = with(density) { revealWidthPx.toDp() }
    Box(
        modifier =
            Modifier
                .align(if (towardLeft) AbsoluteAlignment.CenterRight else AbsoluteAlignment.CenterLeft)
                .absolutePadding(
                    left = if (towardLeft) 0.dp else 8.dp,
                    right = if (towardLeft) 8.dp else 0.dp,
                ).height(with(density) { rowHeightPx.toDp() })
                .padding(vertical = 2.dp)
                .width(revealWidthDp)
                .clip(CircleShape)
                .background(backgroundColor),
        contentAlignment = if (towardLeft) AbsoluteAlignment.CenterRight else AbsoluteAlignment.CenterLeft,
    ) {
        Icon(
            imageVector = swipe.icon,
            contentDescription = stringResource(swipe.labelRes),
            tint = contentColor,
            modifier =
                Modifier
                    .padding(horizontal = 14.dp)
                    .size(22.dp)
                    .graphicsLayer {
                        alpha = iconAlpha
                        scaleX = iconScale
                        scaleY = iconScale
                    },
        )
    }
}
