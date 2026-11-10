package io.github.aedev.flow.ui.screens.music.collection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.components.music.item.MusicItemDensity
import io.github.aedev.flow.ui.components.music.item.MusicTrackItem
import io.github.aedev.flow.ui.components.shared.FlowSearchField
import io.github.aedev.flow.ui.components.shared.FlowSegmentedGap
import io.github.aedev.flow.ui.components.shared.FlowSpinner
import io.github.aedev.flow.ui.components.shared.flowSegmentShape
import io.github.aedev.flow.ui.theme.Dimensions

private val ResultsMinHeight = 240.dp

/**
 * Adds songs from the catalogue to your playlist. Songs already in it show a check, so nothing is
 * added twice, and the playlist behind the sheet shows each one as soon as it lands.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MusicAddSongsSheet(
    search: SongSearchState,
    inPlaylist: Set<String>,
    onQueryChange: (String) -> Unit,
    onAdd: (MusicTrack) -> Unit,
    onPreview: (MusicTrack) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(
            text = stringResource(R.string.ui_add_songs),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        FlowSearchField(
            query = search.query,
            onQueryChange = onQueryChange,
            placeholder = stringResource(R.string.ui_search_songs_to_add),
            onClear = { onQueryChange("") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            releaseFocusWithKeyboard = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Box(modifier = Modifier.fillMaxWidth().heightIn(min = ResultsMinHeight), contentAlignment = Alignment.TopCenter) {
            when {
                search.isSearching && search.results.isEmpty() -> {
                    FlowSpinner(modifier = Modifier.padding(top = 32.dp))
                }

                search.query.isNotBlank() && search.results.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.ui_no_songs_found),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 32.dp),
                    )
                }

                else -> {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(FlowSegmentedGap)) {
                        itemsIndexed(search.results, key = { index, track -> "${track.videoId}#$index" }) { index, track ->
                            val added = track.videoId in inPlaylist
                            val tint = if (added) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            MusicTrackItem(
                                track = track,
                                onClick = { onPreview(track) },
                                modifier = Modifier.padding(horizontal = Dimensions.ContentPaddingHorizontal),
                                density = MusicItemDensity.Compact,
                                shape = flowSegmentShape(index = index, count = search.results.size),
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                showMenu = false,
                                trailingContent = {
                                    IconButton(onClick = { onAdd(track) }, enabled = !added) {
                                        Icon(
                                            imageVector = if (added) Icons.Rounded.CheckCircle else Icons.Outlined.AddCircleOutline,
                                            contentDescription = stringResource(if (added) R.string.ui_added else R.string.add_to_playlist),
                                            tint = tint,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
