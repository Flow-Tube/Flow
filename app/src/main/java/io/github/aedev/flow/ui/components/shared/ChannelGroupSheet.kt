package io.github.aedev.flow.ui.components.shared

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.SubscriptionGroup

@Composable
fun ChannelGroupSheet(
    groups: List<SubscriptionGroup>,
    channelId: String,
    onToggle: (String, Boolean) -> Unit,
    onCreateNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    val entries =
        remember(groups, channelId) {
            groups.map { group ->
                CollectionSheetEntry(
                    id = group.name,
                    name = group.name,
                    supporting = "",
                    thumbnailUrl = "",
                    isSaved = channelId in group.channelIds,
                )
            }
        }

    SaveToCollectionSheet(
        title = stringResource(R.string.channel_groups_sheet_title),
        entries = entries,
        placeholderIcon = Icons.Rounded.Folder,
        createLabel = stringResource(R.string.new_group),
        emptyLabel = stringResource(R.string.channel_groups_empty),
        onToggle = { entry -> onToggle(entry.id, !entry.isSaved) },
        onCreateNew = onCreateNew,
        onDismiss = onDismiss,
    )
}
