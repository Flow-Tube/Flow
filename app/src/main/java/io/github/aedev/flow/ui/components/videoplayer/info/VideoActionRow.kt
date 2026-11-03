package io.github.aedev.flow.ui.components.videoplayer.info

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.utils.formatViewCount

private val ActionSpacing = 8.dp
private val ActionIconSize = 18.dp
private val ActionIconSpacing = 6.dp
private val ActionContentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)

/**
 * The action buttons under a video.
 *
 * Each one is a real Material 3 button rather than a tinted `Surface`, so it carries the shape
 * morph on press and the checked shape on a state it owns. The like/dislike pair is one connected
 * group: the two belong to a single choice, and the group draws the seam the hand-rolled divider
 * used to stand in for.
 */
@Composable
internal fun VideoActionRow(
    likeState: String,
    likeCount: Long? = null,
    dislikeCount: Long?,
    onLikeClick: () -> Unit,
    onDislikeClick: () -> Unit,
    onShareClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onSaveClick: () -> Unit,
    onBackgroundPlayClick: () -> Unit,
    onCopyLinkClick: () -> Unit = {},
    onCopyLinkAtTimeClick: () -> Unit = {},
    isSaved: Boolean = false,
    isDownloaded: Boolean = false,
    onNoteClick: (() -> Unit)? = null,
    hasNote: Boolean = false,
    isDeviceFile: Boolean = false,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(ActionSpacing),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (isDeviceFile) {
            deviceFileActions(onBackgroundPlayClick, onShareClick)
            return@LazyRow
        }
        item {
            SegmentedLikeDislikeButton(
                likeState = likeState,
                likeCount = likeCount,
                dislikeCount = dislikeCount,
                onLikeClick = onLikeClick,
                onDislikeClick = onDislikeClick,
            )
        }

        item {
            ActionToggle(
                icon = if (isSaved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                label = if (isSaved) stringResource(R.string.saved) else stringResource(R.string.save),
                checked = isSaved,
                onCheckedChange = onSaveClick,
            )
        }

        item {
            ActionToggle(
                icon = if (isDownloaded) Icons.Outlined.CheckCircle else Icons.Outlined.Download,
                label = if (isDownloaded) stringResource(R.string.downloaded) else stringResource(R.string.download),
                checked = isDownloaded,
                onCheckedChange = onDownloadClick,
            )
        }

        if (onNoteClick != null) {
            item {
                ActionToggle(
                    icon = if (hasNote) Icons.Filled.StickyNote2 else Icons.Outlined.StickyNote2,
                    label = stringResource(if (hasNote) R.string.note_title else R.string.note_add),
                    checked = hasNote,
                    onCheckedChange = onNoteClick,
                )
            }
        }

        item {
            ActionButton(
                icon = Icons.Outlined.Headphones,
                label = stringResource(R.string.player_action_background),
                onClick = onBackgroundPlayClick,
            )
        }

        item {
            ActionButton(
                icon = Icons.Outlined.Share,
                label = stringResource(R.string.share),
                onClick = onShareClick,
            )
        }

        item {
            ActionButton(
                icon = Icons.Outlined.Link,
                label = stringResource(R.string.player_action_copy_link),
                haptic = HapticFeedbackType.ContextClick,
                onClick = onCopyLinkClick,
            )
        }

        item {
            ActionButton(
                icon = Icons.Outlined.Timer,
                label = stringResource(R.string.player_action_copy_link_at_time),
                haptic = HapticFeedbackType.ContextClick,
                onClick = onCopyLinkAtTimeClick,
            )
        }
    }
}

/** A file on the device has no likes, link or download; it can play in the background and be shared. */
private fun LazyListScope.deviceFileActions(
    onBackgroundPlayClick: () -> Unit,
    onShareClick: () -> Unit,
) {
    item {
        ActionButton(
            icon = Icons.Outlined.Headphones,
            label = stringResource(R.string.player_action_background),
            onClick = onBackgroundPlayClick,
        )
    }
    item {
        ActionButton(
            icon = Icons.Outlined.Share,
            label = stringResource(R.string.share),
            onClick = onShareClick,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SegmentedLikeDislikeButton(
    likeState: String,
    likeCount: Long? = null,
    dislikeCount: Long?,
    onLikeClick: () -> Unit,
    onDislikeClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val isLiked = likeState == LIKED
    val isDisliked = likeState == DISLIKED
    val likeText =
        when {
            likeCount != null && likeCount > 0 -> formatViewCount(likeCount)
            isLiked -> stringResource(R.string.liked)
            else -> stringResource(R.string.like)
        }

    androidx.compose.foundation.layout.Row(
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        ToggleButton(
            checked = isLiked,
            onCheckedChange = {
                haptics.performHapticFeedback(if (isLiked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                onLikeClick()
            },
            shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
            colors = actionToggleColors(),
            contentPadding = ActionContentPadding,
        ) {
            Icon(
                imageVector = if (isLiked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                contentDescription = stringResource(R.string.like),
                modifier = Modifier.size(ActionIconSize),
            )
            Spacer(modifier = Modifier.width(ActionIconSpacing))
            Text(text = likeText, style = MaterialTheme.typography.labelLarge)
        }

        ToggleButton(
            checked = isDisliked,
            onCheckedChange = {
                haptics.performHapticFeedback(if (isDisliked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                onDislikeClick()
            },
            shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
            colors = actionToggleColors(),
            contentPadding = ActionContentPadding,
        ) {
            Icon(
                imageVector = if (isDisliked) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                contentDescription = stringResource(R.string.player_action_dislike),
                modifier = Modifier.size(ActionIconSize),
            )
            if (dislikeCount != null && dislikeCount > 0) {
                Spacer(modifier = Modifier.width(ActionIconSpacing))
                Text(text = formatViewCount(dislikeCount), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** An action that turns something on and stays on, like Saved or Downloaded. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActionToggle(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    ToggleButton(
        checked = checked,
        onCheckedChange = {
            haptics.performHapticFeedback(if (checked) HapticFeedbackType.ToggleOff else HapticFeedbackType.Confirm)
            onCheckedChange()
        },
        shapes = actionToggleShapes(),
        colors = actionToggleColors(),
        contentPadding = ActionContentPadding,
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(ActionIconSize))
        Spacer(modifier = Modifier.width(ActionIconSpacing))
        Text(text = label, style = MaterialTheme.typography.labelLarge)
    }
}

/** An action that happens once and holds no state, resting on the same colour as an unchecked toggle. */
@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    haptic: HapticFeedbackType = HapticFeedbackType.Confirm,
) {
    val haptics = LocalHapticFeedback.current
    androidx.compose.material3.FilledTonalButton(
        onClick = {
            haptics.performHapticFeedback(haptic)
            onClick()
        },
        shapes = ButtonDefaults.shapes(),
        colors =
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        contentPadding = ActionContentPadding,
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(ActionIconSize))
        Spacer(modifier = Modifier.width(ActionIconSpacing))
        Text(text = label, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * The tonal palette an action button carries, and the corner morph it runs on press and while
 * checked, taken from the theme rather than the expressive colour helpers so both flavours build.
 * Only a checked toggle is tinted; every action at rest, toggle or one-tap, shares one container.
 */
@Composable
private fun actionToggleColors() =
    ToggleButtonDefaults.toggleButtonColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )

@Composable
private fun actionToggleShapes() =
    ToggleButtonShapes(
        shape = CircleShape,
        pressedShape = MaterialTheme.shapes.medium,
        checkedShape = CircleShape,
    )

private const val LIKED = "LIKED"
private const val DISLIKED = "DISLIKED"
