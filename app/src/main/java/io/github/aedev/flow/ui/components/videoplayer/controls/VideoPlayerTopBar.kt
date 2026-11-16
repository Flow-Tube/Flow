package io.github.aedev.flow.ui.components.videoplayer.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PictureInPicture
import androidx.compose.material.icons.rounded.RemoveModerator
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SlowMotionVideo
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.PlayerOverlayPreferences
import io.github.aedev.flow.ui.screens.playon.PlayOnButton
import io.github.aedev.flow.ui.theme.PlayerScrim
import io.github.aedev.flow.ui.theme.PlayerScrimAffordance
import io.github.aedev.flow.ui.theme.PlayerScrimContent
import io.github.aedev.flow.ui.theme.PlayerScrimContentDisabled
import io.github.aedev.flow.ui.theme.PlayerScrimContentSecondary

/**
 * The row of actions along the top of the player: minimise, title, and the configurable action
 * cluster on the right.
 *
 * Which of the right-hand actions exist at all is driven by [preferences], so this takes the whole
 * snapshot rather than a boolean per button.
 */
@Composable
internal fun VideoPlayerTopBar(
    preferences: PlayerOverlayPreferences,
    isFullscreen: Boolean,
    isPortraitFullscreen: Boolean,
    videoTitle: String?,
    channelName: String?,
    resizeMode: Int,
    resizeModeLabels: List<String>,
    isPipSupported: Boolean,
    sbSubmitEnabled: Boolean,
    isSponsorBlockAvailable: Boolean,
    isSponsorBlockOffForVideo: Boolean,
    isCasting: Boolean,
    isSubtitlesEnabled: Boolean,
    isAutoplayOn: Boolean,
    isLooping: Boolean,
    isSleepTimerActive: Boolean,
    lockModeEnabled: Boolean,
    isLiveChatAvailable: Boolean,
    hasQueue: Boolean,
    topPadding: Dp,
    horizontalPadding: Dp,
    verticalPadding: Dp,
    rowMinHeight: Dp,
    pillHeight: Dp,
    actionButtonSize: Dp,
    actionIconSize: Dp,
    actionSpacing: Dp,
    actions: PlayerControlActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .playerEdgeScrim(ScrimEdge.Top, PlayerScrim.copy(alpha = TOP_BAR_SCRIM_ALPHA))
                .padding(top = topPadding),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = rowMinHeight)
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(actionSpacing),
            ) {
                PlayerPillIconButton(
                    onClick = actions.onBack,
                    icon = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.btn_minimize),
                    buttonSize = actionButtonSize,
                    iconSize = actionIconSize,
                    containerColor = Color.Transparent,
                )

                if (isFullscreen && !isPortraitFullscreen && !videoTitle.isNullOrBlank()) {
                    Column(
                        modifier =
                            Modifier
                                .weight(1f)
                                .clip(MaterialTheme.shapes.small)
                                .clickable(onClick = actions.onDescriptionClick)
                                .padding(end = 8.dp),
                    ) {
                        Text(
                            text = videoTitle,
                            color = PlayerScrimContent,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!channelName.isNullOrBlank()) {
                            Text(
                                text = channelName,
                                color = PlayerScrimContentSecondary,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                if (isPipSupported && preferences.pipEnabled) {
                    TopBarIconButton(
                        onClick = actions.onPipClick,
                        buttonSize = actionButtonSize,
                        iconSize = actionIconSize,
                        icon = Icons.Rounded.PictureInPicture,
                        contentDescription = stringResource(R.string.pip_mode),
                    )
                }

                if (sbSubmitEnabled) {
                    IconButton(
                        onClick = actions.onSbSubmitClick,
                        modifier = Modifier.size(actionButtonSize),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_upload_segment),
                            contentDescription = stringResource(R.string.sb_submit_dialog_title),
                            tint = PlayerScrimContent,
                            modifier = Modifier.size(actionIconSize),
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(actionSpacing),
            ) {
                if (isFullscreen) {
                    TopBarIconButton(
                        onClick = actions.onResizeClick,
                        buttonSize = actionButtonSize,
                        iconSize = actionIconSize,
                        icon =
                            when (resizeMode) {
                                0 -> Icons.Rounded.AspectRatio
                                1 -> Icons.Rounded.Fullscreen
                                else -> Icons.Rounded.ZoomIn
                            },
                        contentDescription = stringResource(R.string.resize_to, resizeModeLabels[resizeMode]),
                    )
                }

                if (isFullscreen && hasQueue) {
                    TopBarIconButton(
                        onClick = actions.onQueueClick,
                        buttonSize = actionButtonSize,
                        iconSize = actionIconSize,
                        icon = Icons.AutoMirrored.Rounded.QueueMusic,
                        contentDescription = stringResource(R.string.playlist_queue),
                    )
                }

                if (isSponsorBlockAvailable && preferences.sponsorBlockEnabled) {
                    TopBarToggleIconButton(
                        checked = !isSponsorBlockOffForVideo,
                        onCheckedChange = { on -> actions.onSponsorBlockToggle(!on) },
                        buttonSize = actionButtonSize,
                        iconSize = actionIconSize,
                        icon =
                            if (isSponsorBlockOffForVideo) {
                                Icons.Rounded.RemoveModerator
                            } else {
                                ImageVector.vectorResource(R.drawable.ic_block)
                            },
                        contentDescription = stringResource(R.string.player_settings_sponsorblock_video),
                    )
                }

                if (preferences.castEnabled) {
                    TopBarToggleIconButton(
                        checked = isCasting,
                        onCheckedChange = { actions.onCastClick() },
                        buttonSize = actionButtonSize,
                        iconSize = actionIconSize,
                        icon = if (isCasting) Icons.Rounded.Cast else Icons.Outlined.Cast,
                        contentDescription = stringResource(R.string.cast_to_tv),
                    )
                }

                if (preferences.captionsEnabled) {
                    TopBarToggleIconButton(
                        checked = isSubtitlesEnabled,
                        onCheckedChange = { actions.onSubtitleClick() },
                        buttonSize = actionButtonSize,
                        iconSize = actionIconSize,
                        icon =
                            if (isSubtitlesEnabled) {
                                Icons.Rounded.ClosedCaption
                            } else {
                                Icons.Outlined.ClosedCaption
                            },
                        contentDescription = stringResource(R.string.captions),
                        onLongClick = actions.onSubtitleLongClick,
                    )
                }

                if (preferences.autoplayEnabled) {
                    TopBarToggleIconButton(
                        checked = isAutoplayOn && !isLooping,
                        onCheckedChange = { next -> if (!isLooping) actions.onAutoplayToggle(next) },
                        enabled = !isLooping,
                        buttonSize = actionButtonSize,
                        iconSize = actionIconSize,
                        icon = Icons.Rounded.SlowMotionVideo,
                        contentDescription = stringResource(R.string.autoplay),
                    )
                }

                if (preferences.sleepTimerEnabled) {
                    TopBarToggleIconButton(
                        checked = isSleepTimerActive,
                        onCheckedChange = { actions.onSleepTimerClick() },
                        buttonSize = actionButtonSize,
                        iconSize = actionIconSize,
                        icon = Icons.Rounded.Bedtime,
                        contentDescription = stringResource(R.string.sleep_timer),
                    )
                }

                if (lockModeEnabled) {
                    TopBarIconButton(
                        onClick = actions.onTouchLockToggle,
                        buttonSize = actionButtonSize,
                        iconSize = actionIconSize,
                        icon = Icons.Rounded.Lock,
                        contentDescription = stringResource(R.string.player_lock_controls),
                    )
                }

                if (isLiveChatAvailable && isFullscreen) {
                    TopBarIconButton(
                        onClick = actions.onLiveChatClick,
                        buttonSize = actionButtonSize,
                        iconSize = actionIconSize,
                        icon = Icons.Outlined.ChatBubbleOutline,
                        contentDescription = stringResource(R.string.live_chat),
                    )
                }

                PlayOnButton(tint = PlayerScrimContent, buttonSize = actionButtonSize, iconSize = actionIconSize)

                TopBarIconButton(
                    onClick = actions.onSettingsClick,
                    buttonSize = actionButtonSize,
                    iconSize = actionIconSize,
                    icon = Icons.Rounded.Settings,
                    contentDescription = stringResource(R.string.settings),
                )
            }
        }

        if (isPortraitFullscreen) {
            PortraitFullscreenTitle(
                videoTitle = videoTitle,
                channelName = channelName,
                horizontalPadding = horizontalPadding + TitleInsetCorrection,
                onClick = actions.onDescriptionClick,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
            )
        }
    }
}

/**
 * The action row's horizontal padding is pulled in by half an icon button's inset so the glyphs
 * line up with the content edge; text carries no such inset, so the title adds it back.
 */
private val TitleInsetCorrection = 4.dp

/**
 * A top-bar action that is on or off.
 *
 * Over arbitrary video a tint shift is close to invisible against a bright frame, so checked state
 * takes a container and the Expressive checked shape instead of a colour alone.
 */
@Composable
private fun TopBarToggleIconButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    buttonSize: Dp,
    iconSize: Dp,
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    val toggle: (Boolean) -> Unit = { next ->
        haptics.performHapticFeedback(
            if (next) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff,
        )
        onCheckedChange(next)
    }

    if (onLongClick != null) {
        Box(
            modifier =
                Modifier
                    .size(buttonSize)
                    .clip(CircleShape)
                    .background(
                        color = if (checked) PlayerScrimAffordance else Color.Transparent,
                        shape = CircleShape,
                    ).combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = false, radius = buttonSize / 2),
                        onClick = { toggle(!checked) },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongClick()
                        },
                        onClickLabel = contentDescription,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (checked) MaterialTheme.colorScheme.primary else PlayerScrimContent,
                modifier = Modifier.size(iconSize),
            )
        }
        return
    }

    IconToggleButton(
        checked = checked,
        onCheckedChange = toggle,
        enabled = enabled,
        shapes = IconButtonDefaults.toggleableShapes(),
        colors =
            IconButtonDefaults.iconToggleButtonColors(
                contentColor = PlayerScrimContent,
                disabledContentColor = PlayerScrimContentDisabled,
                checkedContainerColor = PlayerScrimAffordance,
                checkedContentColor = MaterialTheme.colorScheme.primary,
            ),
        modifier = Modifier.size(buttonSize),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun TopBarIconButton(
    onClick: () -> Unit,
    buttonSize: Dp,
    iconSize: Dp,
    icon: ImageVector,
    contentDescription: String,
    tint: Color = PlayerScrimContent,
    onLongClick: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    if (onLongClick == null) {
        IconButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                onClick()
            },
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(buttonSize),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
        }
        return
    }

    Box(
        modifier =
            Modifier
                .size(buttonSize)
                .clip(CircleShape)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = false, radius = buttonSize / 2),
                    onClick = onClick,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    },
                    onClickLabel = contentDescription,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}
