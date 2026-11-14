package io.github.aedev.flow.ui.screens.settings.taste

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.settings.SettingsListScope
import io.github.aedev.flow.ui.components.settings.SettingsPage
import io.github.aedev.flow.ui.components.shared.FlowEmptyState

/** Every topic, channel and artist blocked in either engine, each one tap from coming back. */
@Composable
internal fun HiddenContentScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    viewModel: TasteViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hidden = state.hidden
    val allowedAi by viewModel.allowedAiChannels.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.resolveMissingNames() }
    SettingsPage(title = stringResource(R.string.taste_hidden_title), onBack = onBack, highlight = highlight) {
        if (!state.loading && hidden.count == 0 && allowedAi.isEmpty()) {
            item("hidden.empty") {
                FlowEmptyState(
                    title = stringResource(R.string.taste_hidden_empty_title),
                    subtitle = stringResource(R.string.taste_hidden_empty_body),
                    icon = Icons.Outlined.VisibilityOff,
                )
            }
            return@SettingsPage
        }
        hiddenGroup(
            "hidden.topics",
            R.string.taste_hidden_topics,
            hidden.topics.map { NamedItem(it, it.readableTopic()) },
            viewModel::unblockTopic,
        )
        hiddenChannels(hidden.channels, hidden.resolvingNames, viewModel::unblockChannel)
        hiddenGroup("hidden.artists", R.string.taste_hidden_artists, hidden.artists, viewModel::unblockArtist)
        hiddenGroup(
            key = "hidden.ai_allowed",
            header = R.string.ai_list_allowed_header,
            items = allowedAi,
            onUnblock = viewModel::hideAiChannelAgain,
            action = R.string.ai_list_hide_again,
            actionItem = R.string.ai_list_hide_again_item,
        )
    }
}

private fun SettingsListScope.hiddenGroup(
    key: String,
    header: Int,
    items: List<NamedItem>,
    onUnblock: (String) -> Unit,
    @StringRes action: Int = R.string.taste_unblock,
    @StringRes actionItem: Int = R.string.taste_unblock_item,
) {
    group(key = key, header = header) {
        items.forEach { item ->
            row("$key.${item.id}") { shape ->
                TasteActionRow(
                    name = item.name,
                    shape = shape,
                    actionLabel = stringResource(action),
                    actionDescription = stringResource(actionItem, item.name),
                ) { onUnblock(item.id) }
            }
        }
    }
}

/** A channel without a name yet reads as loading while names are fetched, then as unknown with its id below. */
private fun SettingsListScope.hiddenChannels(
    channels: List<NamedItem>,
    resolvingNames: Boolean,
    onUnblock: (String) -> Unit,
) {
    group(key = "hidden.channels", header = R.string.taste_hidden_channels) {
        channels.forEach { item ->
            row("hidden.channels.${item.id}") { shape ->
                val unnamed = item.name.isBlank()
                val label =
                    when {
                        !unnamed -> item.name
                        resolvingNames -> stringResource(R.string.loading_ellipsis)
                        else -> stringResource(R.string.taste_unknown_channel)
                    }
                TasteActionRow(
                    name = label,
                    shape = shape,
                    actionLabel = stringResource(R.string.taste_unblock),
                    actionDescription = stringResource(R.string.taste_unblock_item, if (unnamed) item.id else item.name),
                    supporting = item.id.takeIf { unnamed && !resolvingNames },
                    muted = unnamed,
                ) { onUnblock(item.id) }
            }
        }
    }
}

/** One named item with a single text action, such as unblocking or removing it. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun TasteActionRow(
    name: String,
    shape: Shape,
    actionLabel: String,
    actionDescription: String,
    supporting: String? = null,
    muted: Boolean = false,
    onAction: () -> Unit,
) {
    SegmentedListItem(
        verticalAlignment = Alignment.CenterVertically,
        shapes = ListItemDefaults.shapes(shape = shape),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        supportingContent = supporting?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        trailingContent = {
            TextButton(onClick = onAction, modifier = Modifier.semantics { contentDescription = actionDescription }) {
                Text(actionLabel)
            }
        },
    ) {
        Text(
            name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
        )
    }
}
