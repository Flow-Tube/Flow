package io.github.aedev.flow.ui.screens.music.collection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.MusicPlaylist
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.components.layout.flowBottomContentPadding
import io.github.aedev.flow.ui.components.music.item.MusicItemDensity
import io.github.aedev.flow.ui.components.music.item.MusicTrackItem
import io.github.aedev.flow.ui.components.music.section.MusicCollectionShelf
import io.github.aedev.flow.ui.components.shared.FlowEmptyState
import io.github.aedev.flow.ui.components.shared.FlowSegmentedGap
import io.github.aedev.flow.ui.components.shared.FlowSpinner
import io.github.aedev.flow.ui.components.shared.ReorderHandle
import io.github.aedev.flow.ui.components.shared.ReorderableLazyListState
import io.github.aedev.flow.ui.components.shared.flowSegmentShape
import io.github.aedev.flow.ui.theme.Dimensions

// Room for the selection toolbar to float over the last row.
private val ListEndPadding = 96.dp

/** How the list lets the viewer act on its songs. */
internal class CollectionListMode(
    val kind: MusicCollectionKind?,
    val canReorder: Boolean,
    val selectedKeys: Set<String>? = null,
    /** Set while a search narrows the list; each song keeps its number in the whole collection. */
    val searchQuery: String = "",
    val positions: Map<String, Int> = emptyMap(),
) {
    val inSelection: Boolean get() = selectedKeys != null
}

/** What the end of the list says: the size, a page loading, or a page that failed with Retry. */
internal class CollectionFooter(
    val summary: String,
    val isLoadingMore: Boolean,
    val moreFailed: Boolean,
    val onRetryMore: () -> Unit,
)

/**
 * The songs of an album or playlist under an optional [header]. Positions in [reorderState] count
 * the header as item zero, so the list and the reorder state must agree on whether it is there.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MusicCollectionList(
    tracks: List<Pair<String, MusicTrack>>,
    mode: CollectionListMode,
    footer: CollectionFooter,
    otherVersions: List<MusicPlaylist>,
    listState: LazyListState,
    reorderState: ReorderableLazyListState,
    onTrackClick: (key: String) -> Unit,
    onTrackMenu: (MusicTrack) -> Unit,
    onRemove: (MusicTrack) -> Unit,
    onCollectionClick: (MusicPlaylist) -> Unit,
    onCollectionMenu: (MusicPlaylist) -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    suggestions: CollectionSuggestions? = null,
    onReachedEnd: () -> Unit = {},
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = flowBottomContentPadding(ListEndPadding)),
        verticalArrangement = Arrangement.spacedBy(FlowSegmentedGap),
    ) {
        if (header != null) item(key = "collection-header", contentType = "header") { header() }
        if (tracks.isEmpty() && mode.searchQuery.isNotBlank()) {
            item(key = "collection-no-results", contentType = "empty") {
                FlowEmptyState(
                    title = stringResource(R.string.music_collection_no_matches, mode.searchQuery.trim()),
                    icon = Icons.Outlined.SearchOff,
                )
            }
        } else if (tracks.isEmpty() && !footer.isLoadingMore) {
            item(key = "collection-empty", contentType = "empty") { CollectionEmptyState(mode.kind) }
        }
        itemsIndexed(tracks, key = { _, (key, _) -> key }, contentType = { _, _ -> "track" }) { index, (key, track) ->
            val selected = mode.selectedKeys?.contains(key) == true
            val rowColor = MaterialTheme.colorScheme.surfaceContainerHigh
            MusicTrackItem(
                track = track,
                onClick = { onTrackClick(key) },
                onLongClick = { if (mode.inSelection) onTrackClick(key) else onTrackMenu(track) },
                modifier =
                    Modifier
                        .padding(horizontal = Dimensions.ContentPaddingHorizontal)
                        .then(if (mode.canReorder) reorderState.itemModifier(index) else Modifier),
                density = MusicItemDensity.Compact,
                index = mode.positions[key] ?: (index + 1),
                shape = flowSegmentShape(index = index, count = tracks.size),
                containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else rowColor,
                leadingContent =
                    when {
                        mode.inSelection -> {
                            { Checkbox(checked = selected, onCheckedChange = { onTrackClick(key) }) }
                        }

                        mode.canReorder -> {
                            { ReorderHandle(modifier = reorderState.handleModifier(index)) }
                        }

                        else -> {
                            null
                        }
                    },
                showMenu = false,
                trailingContent = if (mode.inSelection) null else removeButton(mode.kind, track, onRemove),
            )
        }
        if (otherVersions.isNotEmpty()) {
            item(key = "collection-other-versions", contentType = "shelf") {
                MusicCollectionShelf(
                    title = stringResource(R.string.section_other_versions),
                    collections = otherVersions,
                    keyNamespace = "other_versions",
                    onCollectionClick = onCollectionClick,
                    onCollectionMenu = onCollectionMenu,
                )
            }
        }
        if (tracks.isNotEmpty() || footer.isLoadingMore) {
            item(key = "collection-footer", contentType = "footer") {
                LaunchedEffect(footer.isLoadingMore) { if (!footer.isLoadingMore) onReachedEnd() }
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when {
                        footer.isLoadingMore -> FlowSpinner()
                        footer.moreFailed -> TextButton(onClick = footer.onRetryMore) { Text(stringResource(R.string.retry)) }
                    }
                    Text(
                        text = footer.summary,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        suggestions?.let { collectionSuggestions(it) }
    }
}

private fun removeButton(
    kind: MusicCollectionKind?,
    track: MusicTrack,
    onRemove: (MusicTrack) -> Unit,
): (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? =
    when (kind) {
        MusicCollectionKind.OWN -> {
            {
                IconButton(onClick = { onRemove(track) }) {
                    Icon(Icons.Rounded.RemoveCircleOutline, contentDescription = stringResource(R.string.ui_delete_from_playlist))
                }
            }
        }

        MusicCollectionKind.LIKED -> {
            {
                IconButton(onClick = { onRemove(track) }) {
                    Icon(
                        Icons.Rounded.Favorite,
                        contentDescription = stringResource(R.string.unlike),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        else -> {
            null
        }
    }

@Composable
private fun CollectionEmptyState(kind: MusicCollectionKind?) {
    when (kind) {
        MusicCollectionKind.LIKED -> {
            FlowEmptyState(
                title = stringResource(R.string.liked_music_empty_title),
                subtitle = stringResource(R.string.liked_music_empty_body),
                icon = Icons.Outlined.FavoriteBorder,
            )
        }

        else -> {
            FlowEmptyState(
                title = stringResource(R.string.music_playlist_empty_title),
                subtitle = stringResource(R.string.music_playlist_empty_body),
                icon = Icons.AutoMirrored.Outlined.QueueMusic,
            )
        }
    }
}

/** "12 songs · 44 min" under the list and in the header. */
@Composable
internal fun songsSummary(
    count: Int,
    durationText: String?,
): String =
    listOfNotNull(pluralStringResource(R.plurals.songs_count_template, count, count), durationText?.takeIf(String::isNotBlank))
        .joinToString(" ${stringResource(R.string.metadata_separator)} ")
