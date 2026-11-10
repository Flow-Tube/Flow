package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R

private val MenuWidth = 200.dp
private val MenuLabelHorizontalPadding = 16.dp
private val MenuLabelVerticalPadding = 8.dp

/**
 * How much room the control takes.
 *
 * [Compact] is the extra-small Material 3 size, for a row that already carries an avatar, a channel
 * name and a subscriber count beside it.
 */
enum class FlowSubscribeButtonSize {
    Default,
    Compact,

    /**
     * Stretches the unsubscribed control to the width it is given, for a row that shares its space
     * with another action — it otherwise sizes to its short label and sits small beside a stretched
     * neighbour. The subscribed control always sizes to its own content, which is the only width
     * that fits "Subscribed" beside the menu on one line.
     */
    Wide,
}

/**
 * The one subscribe control, shared by the channel page, the player, the subscription manager and
 * search.
 *
 * Tapping while subscribed opens the menu rather than unsubscribing outright, so the destructive
 * action always needs a second, deliberate tap. A control that stands for several channels passes
 * [onSubscribedClick] instead: it then reads "Subscribed" with no bell or menu, since neither can
 * say which channel they act on. A surface short on room, or a list in which every row is already
 * subscribed, passes [showSubscribedLabel] false to keep only the bell and the menu.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FlowSubscribeButton(
    isSubscribed: Boolean,
    onSubscribeClick: () -> Unit,
    modifier: Modifier = Modifier,
    isNotificationsEnabled: Boolean = false,
    onUnsubscribeClick: () -> Unit = {},
    onNotificationChange: ((Boolean) -> Unit)? = null,
    areShortsExcluded: Boolean? = null,
    onShortsExcludeChange: (Boolean) -> Unit = {},
    onManageGroups: (() -> Unit)? = null,
    onAddNote: (() -> Unit)? = null,
    size: FlowSubscribeButtonSize = FlowSubscribeButtonSize.Default,
    tint: MediaArtworkTint? = null,
    onSubscribedClick: (() -> Unit)? = null,
    showSubscribedLabel: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    var menuExpanded by remember { mutableStateOf(false) }
    val compact = size == FlowSubscribeButtonSize.Compact
    val containerHeight =
        if (compact) ButtonDefaults.ExtraSmallContainerHeight else ButtonDefaults.MinHeight
    val leadingIconSize =
        if (compact) ButtonDefaults.ExtraSmallIconSize else SplitButtonDefaults.LeadingIconSize
    val trailingIconSize =
        if (compact) SplitButtonDefaults.ExtraSmallTrailingButtonIconSize else SplitButtonDefaults.TrailingIconSize

    val fill = if (size == FlowSubscribeButtonSize.Wide) Modifier.fillMaxWidth() else Modifier

    // On an artwork-tinted card the theme's own primary lands as an unrelated colour, and its
    // contrast is against the theme surface rather than against the tint the card actually drew.
    // Filling with the ink the tint already clamped to 4.5:1 stays readable whatever the avatar is.
    val tonalColors =
        if (tint != null) {
            ButtonDefaults.filledTonalButtonColors(
                containerColor = tint.raised,
                contentColor = tint.onContainer,
            )
        } else {
            ButtonDefaults.filledTonalButtonColors()
        }
    val subscribeColors =
        ToggleButtonDefaults.toggleButtonColors(
            containerColor = tint?.onContainer ?: MaterialTheme.colorScheme.primary,
            contentColor = tint?.container ?: MaterialTheme.colorScheme.onPrimary,
        )

    Box(modifier = modifier) {
        if (isSubscribed && onSubscribedClick != null) {
            FilledTonalButton(
                onClick = onSubscribedClick,
                colors = tonalColors,
                contentPadding = if (compact) ButtonDefaults.ExtraSmallContentPadding else ButtonDefaults.ContentPadding,
                modifier = Modifier.heightIn(min = containerHeight),
            ) {
                Text(text = stringResource(R.string.subscribed), maxLines = 1)
            }
        } else if (isSubscribed) {
            SplitButtonLayout(
                leadingButton = {
                    SplitButtonDefaults.TonalLeadingButton(
                        onClick = {
                            if (onNotificationChange != null) onNotificationChange(!isNotificationsEnabled) else menuExpanded = true
                        },
                        colors = tonalColors,
                        contentPadding =
                            if (compact) {
                                SplitButtonDefaults.ExtraSmallLeadingButtonContentPadding
                            } else {
                                SplitButtonDefaults.SmallLeadingButtonContentPadding
                            },
                        modifier =
                            Modifier
                                .heightIn(min = containerHeight)
                                .then(
                                    if (showSubscribedLabel) {
                                        Modifier
                                    } else {
                                        Modifier.semantics { toggleableState = ToggleableState(isNotificationsEnabled) }
                                    },
                                ),
                    ) {
                        // A bell nothing listens to would look like a broken control (#1467), so a
                        // caller that cannot change notifications shows the label alone.
                        if (onNotificationChange != null || !showSubscribedLabel) {
                            Icon(
                                imageVector =
                                    if (isNotificationsEnabled) {
                                        Icons.Rounded.NotificationsActive
                                    } else {
                                        Icons.Rounded.NotificationsOff
                                    },
                                contentDescription = if (showSubscribedLabel) null else stringResource(R.string.notifications),
                                modifier = Modifier.size(leadingIconSize),
                            )
                        }
                        if (showSubscribedLabel) {
                            if (onNotificationChange != null) Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                            Text(text = stringResource(R.string.subscribed), maxLines = 1)
                        }
                    }
                },
                trailingButton = {
                    SplitButtonDefaults.TonalTrailingButton(
                        checked = menuExpanded,
                        onCheckedChange = { menuExpanded = it },
                        colors = tonalColors,
                        contentPadding =
                            if (compact) {
                                SplitButtonDefaults.ExtraSmallTrailingButtonContentPadding
                            } else {
                                SplitButtonDefaults.SmallTrailingButtonContentPadding
                            },
                        modifier = Modifier.heightIn(min = containerHeight),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.subscribed),
                            modifier = Modifier.size(trailingIconSize),
                        )
                    }
                },
            )
        } else {
            ToggleButton(
                checked = false,
                onCheckedChange = {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onSubscribeClick()
                },
                shapes = ToggleButtonShapes(CircleShape, CircleShape, CircleShape),
                colors = subscribeColors,
                contentPadding =
                    if (compact) ButtonDefaults.ExtraSmallContentPadding else ToggleButtonDefaults.ContentPadding,
                modifier = fill.heightIn(min = containerHeight),
            ) {
                Text(text = stringResource(R.string.subscribe), maxLines = 1)
            }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            modifier = Modifier.width(MenuWidth),
        ) {
            if (onNotificationChange != null) {
                Text(
                    text = stringResource(R.string.notifications),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier.padding(
                            horizontal = MenuLabelHorizontalPadding,
                            vertical = MenuLabelVerticalPadding,
                        ),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.on)) },
                    leadingIcon = { Icon(Icons.Rounded.NotificationsActive, contentDescription = null) },
                    onClick = {
                        onNotificationChange(true)
                        menuExpanded = false
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.off)) },
                    leadingIcon = { Icon(Icons.Rounded.NotificationsOff, contentDescription = null) },
                    onClick = {
                        onNotificationChange(false)
                        menuExpanded = false
                    },
                )
                HorizontalDivider()
            }

            if (areShortsExcluded != null) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (areShortsExcluded) R.string.show_channel_shorts else R.string.hide_channel_shorts,
                            ),
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector =
                                if (areShortsExcluded) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        onShortsExcludeChange(!areShortsExcluded)
                        menuExpanded = false
                    },
                )
                HorizontalDivider()
            }

            if (onAddNote != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.note_add)) },
                    leadingIcon = { Icon(Icons.Outlined.StickyNote2, contentDescription = null) },
                    onClick = {
                        onAddNote()
                        menuExpanded = false
                    },
                )
            }

            if (onManageGroups != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.channel_add_to_group)) },
                    leadingIcon = { Icon(Icons.Rounded.Folder, contentDescription = null) },
                    onClick = {
                        onManageGroups()
                        menuExpanded = false
                    },
                )
                HorizontalDivider()
            }

            DropdownMenuItem(
                text = { Text(stringResource(R.string.unsubscribe)) },
                leadingIcon = { Icon(Icons.Rounded.PersonRemove, contentDescription = null) },
                onClick = {
                    onUnsubscribeClick()
                    menuExpanded = false
                },
            )
        }
    }
}
