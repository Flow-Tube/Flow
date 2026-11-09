package io.github.aedev.flow.ui.screens.subscriptions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Channel
import io.github.aedev.flow.data.model.SubscriptionGroup
import io.github.aedev.flow.ui.components.shared.ChannelAvatarImage
import io.github.aedev.flow.ui.components.shared.FlowAlertDialog
import io.github.aedev.flow.ui.components.shared.FlowSearchField
import io.github.aedev.flow.ui.components.shared.ReorderHandle
import io.github.aedev.flow.ui.utils.flowWindowDpSize
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private val GroupListMaxHeight = 280.dp
private val ChannelListMaxHeight = 280.dp
private val RowVerticalPadding = 4.dp
private val ActionIconSize = 32.dp
private val ActionGlyphSize = 16.dp
private val ChannelAvatarSize = 32.dp
private val ContentSpacing = 8.dp

@Composable
internal fun SubscriptionGroupsManagerDialog(
    groups: List<SubscriptionGroup>,
    onDismiss: () -> Unit,
    onCreateNew: () -> Unit,
    onEdit: (SubscriptionGroup) -> Unit,
    onDelete: (SubscriptionGroup) -> Unit,
    onOrderChanged: (List<String>) -> Unit,
) {
    FlowAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.manage_groups)) },
        text = {
            if (groups.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_groups_yet),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = ContentSpacing),
                )
            } else {
                SubscriptionGroupsReorderList(
                    groups = groups,
                    onEdit = onEdit,
                    onDelete = onDelete,
                    onOrderChanged = onOrderChanged,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCreateNew) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(ActionGlyphSize))
                Spacer(modifier = Modifier.width(RowVerticalPadding))
                Text(stringResource(R.string.new_group))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

/**
 * The library needs each move applied before its callback returns, so the order is held here and
 * saved once a drag ends. Writing every step to Room let the library repeat a step and undo it.
 */
@Composable
private fun SubscriptionGroupsReorderList(
    groups: List<SubscriptionGroup>,
    onEdit: (SubscriptionGroup) -> Unit,
    onDelete: (SubscriptionGroup) -> Unit,
    onOrderChanged: (List<String>) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var ordered by remember(groups) { mutableStateOf(groups) }
    val listState = rememberLazyListState()
    val reorderState =
        rememberReorderableLazyListState(listState) { from, to ->
            ordered = ordered.toMutableList().apply { add(to.index, removeAt(from.index)) }
            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }

    LaunchedEffect(reorderState.isAnyItemDragging) {
        if (!reorderState.isAnyItemDragging && ordered.map { it.name } != groups.map { it.name }) {
            onOrderChanged(ordered.map { it.name })
        }
    }

    fun move(
        from: Int,
        to: Int,
    ): Boolean {
        if (to !in ordered.indices) return false
        ordered = ordered.toMutableList().apply { add(to, removeAt(from)) }
        onOrderChanged(ordered.map { it.name })
        return true
    }

    val windowHeight = flowWindowDpSize().height
    val moveUpLabel = stringResource(R.string.move_up)
    val moveDownLabel = stringResource(R.string.move_down)
    LazyColumn(
        state = listState,
        modifier = Modifier.heightIn(max = maxOf(GroupListMaxHeight, windowHeight / 2)),
    ) {
        itemsIndexed(ordered, key = { _, group -> group.name }) { index, group ->
            ReorderableItem(state = reorderState, key = group.name) { isDragging ->
                val itemScope = this
                Surface(
                    color =
                        if (isDragging) {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        } else {
                            Color.Transparent
                        },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .semantics {
                                    customActions =
                                        buildList {
                                            if (index > 0) add(CustomAccessibilityAction(moveUpLabel) { move(index, index - 1) })
                                            if (index < ordered.lastIndex) {
                                                add(CustomAccessibilityAction(moveDownLabel) { move(index, index + 1) })
                                            }
                                        }
                                },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier =
                                with(itemScope) {
                                    Modifier
                                        .size(LocalMinimumInteractiveComponentSize.current)
                                        .draggableHandle(
                                            onDragStarted = {
                                                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                            },
                                            onDragStopped = { haptics.performHapticFeedback(HapticFeedbackType.GestureEnd) },
                                        )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            ReorderHandle()
                        }
                        Text(
                            text = group.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text =
                                pluralStringResource(
                                    R.plurals.channels_count,
                                    group.channelIds.size,
                                    group.channelIds.size,
                                ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = ContentSpacing),
                        )
                        IconButton(onClick = { onEdit(group) }, modifier = Modifier.size(ActionIconSize)) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(ActionGlyphSize),
                            )
                        }
                        IconButton(onClick = { onDelete(group) }, modifier = Modifier.size(ActionIconSize)) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(ActionGlyphSize),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SubscriptionCreateEditGroupDialog(
    existingGroup: SubscriptionGroup?,
    allChannels: List<Channel>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, channelIds: List<String>) -> Unit,
) {
    var groupName by remember { mutableStateOf(existingGroup?.name.orEmpty()) }
    var selectedChannelIds by remember { mutableStateOf(existingGroup?.channelIds.orEmpty().toSet()) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredChannels =
        remember(allChannels, searchQuery) {
            if (searchQuery.isBlank()) {
                allChannels
            } else {
                allChannels.filter { it.name.contains(searchQuery, ignoreCase = true) }
            }
        }

    FlowAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(if (existingGroup == null) R.string.new_group else R.string.edit_group),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ContentSpacing)) {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text(stringResource(R.string.group_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowSearchField(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    placeholder = stringResource(R.string.search_channels_hint),
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                        )
                    },
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = ChannelListMaxHeight),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(filteredChannels, key = { it.id }) { channel ->
                        val isChecked = channel.id in selectedChannelIds
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedChannelIds =
                                            if (isChecked) {
                                                selectedChannelIds - channel.id
                                            } else {
                                                selectedChannelIds + channel.id
                                            }
                                    }.padding(vertical = RowVerticalPadding, horizontal = RowVerticalPadding),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = isChecked, onCheckedChange = null)
                            ChannelAvatarImage(
                                url = channel.thumbnailUrl,
                                contentDescription = null,
                                modifier =
                                    Modifier
                                        .size(ChannelAvatarSize)
                                        .clip(subscriptionAvatarShape(channel.isMusic))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                            Spacer(modifier = Modifier.width(ContentSpacing))
                            Text(
                                text = channel.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(groupName.trim(), selectedChannelIds.toList()) },
                enabled = groupName.isNotBlank() && selectedChannelIds.isNotEmpty(),
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * The group manager and the create or edit dialog it hands off to, as one flow: shown while the user
 * manages groups, and [onDismiss] once they leave it.
 */
@Composable
internal fun SubscriptionGroupDialogsHost(
    viewModel: SubscriptionsViewModel,
    uiState: SubscriptionsUiState,
    onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<SubscriptionGroup?>(null) }

    if (!editing) {
        SubscriptionGroupsManagerDialog(
            groups = uiState.groups,
            onDismiss = onDismiss,
            onCreateNew = {
                editingGroup = null
                editing = true
            },
            onEdit = { group ->
                editingGroup = group
                editing = true
            },
            onDelete = { group -> viewModel.deleteGroup(group.name) },
            onOrderChanged = viewModel::setGroupOrder,
        )
    } else {
        SubscriptionCreateEditGroupDialog(
            existingGroup = editingGroup,
            allChannels = uiState.subscribedChannels,
            onDismiss = onDismiss,
            onConfirm = { name, channelIds ->
                val existing = editingGroup
                if (existing == null) {
                    viewModel.createGroup(name, channelIds)
                } else {
                    viewModel.updateGroup(existing.name, name, channelIds)
                }
                onDismiss()
            },
        )
    }
}
