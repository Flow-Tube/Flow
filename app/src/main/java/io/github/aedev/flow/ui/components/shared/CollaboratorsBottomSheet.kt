package io.github.aedev.flow.ui.components.shared

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.VideoCollaborator
import io.github.aedev.flow.ui.components.layout.navigation.LocalMediaNavigator
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionRow
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsDefaults
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsGroup
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsSheet
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsViewModel
import io.github.aedev.flow.ui.components.shared.quickactions.sharedQuickActionsViewModel

/** The channels behind a collaboration, each one openable and subscribable. */
@Composable
fun CollaboratorsBottomSheet(
    collaborators: List<VideoCollaborator>,
    onDismiss: () -> Unit,
    viewModel: QuickActionsViewModel = sharedQuickActionsViewModel(),
) {
    QuickActionsSheet(onDismiss = onDismiss) { sheet ->
        QuickActionsGroup(
            title = stringResource(R.string.collaborators),
            rows = collaboratorRows(collaborators = collaborators, onOpened = sheet::close, viewModel = viewModel),
        )
    }
}

/** The ids of the [collaborators] the viewer is subscribed to, kept current. */
@Composable
fun subscribedCollaboratorIds(
    collaborators: List<VideoCollaborator>,
    viewModel: QuickActionsViewModel = sharedQuickActionsViewModel(),
): Set<String> {
    val subscribedChannelIds by viewModel.subscribedChannelIds.collectAsStateWithLifecycle()
    val channelIds = remember(collaborators) { collaborators.map { it.channelId }.filter { it.isNotBlank() }.distinct() }
    LaunchedEffect(channelIds) { channelIds.forEach(viewModel::loadSubscriptionState) }
    return subscribedChannelIds
}

/** One row per collaborator: its avatar, name and subscriber count, with its own subscribe button. */
@Composable
internal fun collaboratorRows(
    collaborators: List<VideoCollaborator>,
    onOpened: () -> Unit,
    viewModel: QuickActionsViewModel,
): List<QuickActionRow> {
    val subscribedChannelIds = subscribedCollaboratorIds(collaborators, viewModel)
    val bell = sharedSubscriptionBellViewModel()
    val notifyingChannelIds by bell.notifyingChannelIds.collectAsStateWithLifecycle()
    val navigator = LocalMediaNavigator.current
    val fallbackName = stringResource(R.string.collaborator)
    val ringColor = MaterialTheme.colorScheme.surfaceContainerHigh

    return collaborators.mapIndexed { index, collaborator ->
        val name = collaborator.name.ifBlank { fallbackName }
        val canOpen = collaborator.channelId.isNotBlank()
        QuickActionRow(key = collaborator.channelId.ifBlank { "collaborator:$index" }) { shape ->
            FlowNavRow(
                title = name,
                supportingText = collaborator.subscriberCountText.takeIf { it.isNotBlank() },
                onClick = {
                    navigator.openChannel(collaborator.channelId)
                    onOpened()
                },
                enabled = canOpen,
                shape = shape,
                leadingContent = {
                    ChannelAvatarStack(
                        urls = listOf(collaborator.thumbnailUrl).filter { it.isNotBlank() },
                        contentDescription = null,
                        avatarSize = QuickActionsDefaults.AvatarSize,
                        ringColor = ringColor,
                    )
                },
                trailingContent =
                    if (canOpen) {
                        {
                            val toggle = {
                                viewModel.toggleSubscription(collaborator.channelId, collaborator.name, collaborator.thumbnailUrl)
                            }
                            FlowSubscribeButton(
                                isSubscribed = collaborator.channelId in subscribedChannelIds,
                                onSubscribeClick = toggle,
                                onUnsubscribeClick = toggle,
                                isNotificationsEnabled = collaborator.channelId in notifyingChannelIds,
                                onNotificationChange = { bell.setNotifications(collaborator.channelId, it) },
                                size = FlowSubscribeButtonSize.Compact,
                                showSubscribedLabel = false,
                            )
                        }
                    } else {
                        null
                    },
            )
        }
    }
}
