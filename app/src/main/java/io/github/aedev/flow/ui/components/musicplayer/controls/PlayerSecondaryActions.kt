package io.github.aedev.flow.ui.components.musicplayer.controls

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.player.RepeatMode
import io.github.aedev.flow.ui.components.musicplayer.common.readableAccentOn

private val SegmentFullRadius = 21.dp
private val SegmentEdgeRadius = 8.dp

/** The first, inner and last segments of a player button group: round at the group's ends only. */
internal val PlayerSegmentLeadingShape =
    RoundedCornerShape(
        topStart = SegmentFullRadius,
        bottomStart = SegmentFullRadius,
        topEnd = SegmentEdgeRadius,
        bottomEnd = SegmentEdgeRadius,
    )
internal val PlayerSegmentInnerShape = RoundedCornerShape(SegmentEdgeRadius)
internal val PlayerSegmentTrailingShape =
    RoundedCornerShape(
        topStart = SegmentEdgeRadius,
        bottomStart = SegmentEdgeRadius,
        topEnd = SegmentFullRadius,
        bottomEnd = SegmentFullRadius,
    )

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerSecondaryActions(
    lyricsActive: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    onLyricsClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onRepeatClick: () -> Unit,
    onQueueClick: () -> Unit,
    modifier: Modifier = Modifier,
    queueActive: Boolean = false,
    sleepTimerActive: Boolean = false,
    onSleepTimerClick: (() -> Unit)? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(42.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerToggleButton(
            checked = lyricsActive,
            checkedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
            checkedContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            icon = Icons.Outlined.Lyrics,
            contentDescription = stringResource(R.string.lyrics),
            uncheckedShape = PlayerSegmentLeadingShape,
            onClick = onLyricsClick,
        )
        PlayerToggleButton(
            checked = shuffleEnabled,
            checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            icon = Icons.Rounded.Shuffle,
            contentDescription = stringResource(R.string.shuffle),
            uncheckedShape = PlayerSegmentInnerShape,
            onClick = onShuffleClick,
        )
        PlayerToggleButton(
            checked = repeatMode != RepeatMode.OFF,
            checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            icon =
                when (repeatMode) {
                    RepeatMode.ONE -> Icons.Rounded.RepeatOne
                    else -> Icons.Rounded.Repeat
                },
            contentDescription = stringResource(R.string.repeat),
            uncheckedShape = PlayerSegmentInnerShape,
            onClick = onRepeatClick,
        )
        PlayerToggleButton(
            checked = queueActive,
            checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            icon = Icons.Outlined.QueueMusic,
            contentDescription = stringResource(R.string.playlist_queue),
            uncheckedShape = if (onSleepTimerClick == null) PlayerSegmentTrailingShape else PlayerSegmentInnerShape,
            onClick = onQueueClick,
        )
        if (onSleepTimerClick != null) {
            PlayerToggleButton(
                checked = sleepTimerActive,
                checkedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                checkedContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                icon = if (sleepTimerActive) Icons.Filled.Bedtime else Icons.Outlined.Bedtime,
                contentDescription = stringResource(R.string.sleep_timer),
                uncheckedShape = PlayerSegmentTrailingShape,
                onClick = onSleepTimerClick,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RowScope.PlayerToggleButton(
    checked: Boolean,
    checkedContainerColor: Color,
    checkedContentColor: Color,
    icon: ImageVector,
    contentDescription: String?,
    uncheckedShape: Shape,
    onClick: () -> Unit,
) {
    ToggleButton(
        checked = checked,
        onCheckedChange = { onClick() },
        modifier =
            Modifier
                .weight(1f)
                .fillMaxHeight(),
        colors =
            ToggleButtonDefaults.toggleButtonColors(
                containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                checkedContainerColor = checkedContainerColor,
                checkedContentColor = checkedContentColor,
            ),
        shapes =
            ToggleButtonShapes(
                shape = uncheckedShape,
                pressedShape = RoundedCornerShape(12.dp),
                checkedShape = RoundedCornerShape(SegmentFullRadius),
            ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
fun PlayerMainActionButtons(
    isLiked: Boolean,
    isDownloaded: Boolean,
    onLikeClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onAddToPlaylist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hapticFeedback = LocalHapticFeedback.current
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SplitCapsuleButton(
            active = isDownloaded,
            shape =
                RoundedCornerShape(
                    topStart = 50.dp,
                    topEnd = 6.dp,
                    bottomStart = 50.dp,
                    bottomEnd = 6.dp,
                ),
            icon = if (isDownloaded) Icons.Rounded.OfflinePin else Icons.Outlined.Download,
            contentDescription = stringResource(R.string.download),
            onClick = onDownloadClick,
        )
        SplitCapsuleButton(
            active = isLiked,
            shape =
                RoundedCornerShape(
                    topStart = 6.dp,
                    topEnd = 50.dp,
                    bottomStart = 6.dp,
                    bottomEnd = 50.dp,
                ),
            icon = if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = stringResource(R.string.like),
            onClick = onLikeClick,
            onLongClick = {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                onAddToPlaylist()
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SplitCapsuleButton(
    active: Boolean,
    shape: RoundedCornerShape,
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val containerColor by animateColorAsState(
        targetValue =
            if (active) {
                scheme.primary
            } else {
                scheme.onPrimary.copy(alpha = 0.7f)
            },
        animationSpec = tween(durationMillis = 250),
        label = "capsuleContainer",
    )
    val contentColor by animateColorAsState(
        targetValue =
            if (active) {
                scheme.onPrimary
            } else {
                // Contrast-check against what the eye sees: the translucent pill over the surface.
                readableAccentOn(
                    container = lerp(scheme.surface, scheme.onPrimary, 0.7f),
                    accent = scheme.primary,
                )
            },
        animationSpec = tween(durationMillis = 250),
        label = "capsuleContent",
    )
    Box(
        modifier =
            Modifier
                .size(width = 50.dp, height = 42.dp)
                .clip(shape)
                .background(containerColor)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(22.dp),
        )
    }
}
