package io.github.aedev.flow.ui.components.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.entity.DownloadWithItems
import io.github.aedev.flow.data.music.DownloadedTrack
import io.github.aedev.flow.data.video.DownloadProgressUpdate
import io.github.aedev.flow.data.video.DownloadedVideo
import io.github.aedev.flow.ui.components.layout.flowBottomContentPadding
import io.github.aedev.flow.ui.components.shared.FlowEmptyState
import io.github.aedev.flow.ui.components.shared.FlowPullToRefreshBox
import io.github.aedev.flow.ui.components.shared.MediaKind
import io.github.aedev.flow.ui.components.shared.animateMediaGridItem

private val GridSpacing = 12.dp
private val MusicGridPadding = 16.dp

/** Rows carry their own side padding, so content above them is inset to match; cards use the grid's. */
private fun rowInset(columns: Int): Dp = if (columns > 1) 0.dp else 16.dp

/** Rows carry their own side padding; cards need the grid's. */
@Composable
private fun listPadding(side: Dp) =
    PaddingValues(
        start = side,
        end = side,
        top = 4.dp,
        bottom = flowBottomContentPadding(),
    )

/** Which items are selected and how a tap changes that; tapping plays when [active] is false. */
internal class LibrarySelection(
    val active: Boolean,
    val ids: Set<String>,
    val onToggle: (String) -> Unit,
)

/** What the in-progress rows can do; each callback takes the download's video id. */
internal class ActiveDownloadActions(
    val onPause: (String) -> Unit,
    val onResume: (String) -> Unit,
    val onRetry: (String) -> Unit,
    val onCancel: (id: String, title: String) -> Unit,
    val onCancelAll: () -> Unit,
)

/**
 * Video downloads under the screen's [header]: what is still downloading, then what is finished,
 * as rows on a phone and as cards on wider windows, as many columns as the space holds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideosDownloadsList(
    header: @Composable () -> Unit,
    shelf: @Composable (horizontalInset: Dp) -> Unit,
    videos: List<DownloadedVideo>,
    totalCount: Int,
    incomplete: List<DownloadWithItems>,
    progress: Map<String, DownloadProgressUpdate>,
    mergingIds: Set<String>,
    query: String,
    selection: LibrarySelection,
    activeActions: ActiveDownloadActions,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onVideoClick: (List<DownloadedVideo>, Int) -> Unit,
    onDelete: (id: String, title: String) -> Unit,
    onSwipeDelete: (id: String) -> Unit,
    onHomeClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowPullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        state = rememberPullToRefreshState(),
        modifier = modifier.fillMaxSize(),
    ) {
        if (totalCount == 0 && incomplete.isEmpty()) {
            DownloadsEmptyState(kind = MediaKind.Videos, onHomeClick = onHomeClick)
            return@FlowPullToRefreshBox
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val grid = remember(maxWidth) { libraryGridLayoutFor(maxWidth) }
            val columns = grid.columns
            val partialRows = remember(videos.size, grid) { grid.partialRows(videos.size) }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = listPadding(grid.padding),
                horizontalArrangement = Arrangement.spacedBy(grid.spacing),
                modifier = Modifier.fillMaxSize(),
            ) {
                fullWidth("header") { Box(Modifier.padding(horizontal = rowInset(columns))) { header() } }
                fullWidth("collections") { shelf(rowInset(columns)) }
                activeSection(incomplete, progress, mergingIds, activeActions, rowInset(columns))
                if (videos.isNotEmpty()) {
                    fullWidth(
                        "section_done",
                    ) { DownloadsSectionHeader(stringResource(R.string.downloads_section_downloaded), videos.size, rowInset(columns)) }
                } else if (query.isNotBlank()) {
                    fullWidth("no_results") { NoResults(query) }
                }
                itemsIndexed(
                    items = videos,
                    key = { _, video -> video.video.id },
                    contentType = { index, _ -> if (grid.isGrid && index !in partialRows) "video_card" else "video_row" },
                    span = { index, _ -> GridItemSpan(if (index in partialRows) maxLineSpan else 1) },
                ) { index, video ->
                    val asCard = grid.isGrid && index !in partialRows
                    DownloadSwipeToDelete(
                        enabled = !selection.active,
                        onDelete = { onSwipeDelete(video.video.id) },
                        modifier = animateMediaGridItem().padding(bottom = if (asCard) GridSpacing else 0.dp),
                    ) {
                        VideoDownloadItem(
                            video = video,
                            asCard = asCard,
                            selectionMode = selection.active,
                            selected = video.video.id in selection.ids,
                            onClick = { if (selection.active) selection.onToggle(video.video.id) else onVideoClick(videos, index) },
                            onDeleteClick = { onDelete(video.video.id, video.video.title) },
                            rowThumbnailWidth = grid.rowThumbnailWidth,
                        )
                    }
                }
            }
        }
    }
}

/** Song downloads under [header]: in progress first, then finished, in one or two columns of rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MusicDownloadsList(
    header: @Composable () -> Unit,
    shelf: @Composable (horizontalInset: Dp) -> Unit,
    tracks: List<DownloadedTrack>,
    totalCount: Int,
    incomplete: List<DownloadWithItems>,
    progress: Map<String, DownloadProgressUpdate>,
    columns: Int,
    query: String,
    selection: LibrarySelection,
    activeActions: ActiveDownloadActions,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onMusicClick: (List<DownloadedTrack>, Int) -> Unit,
    onSwipeDelete: (id: String) -> Unit,
    onHomeClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowPullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        state = rememberPullToRefreshState(),
        modifier = modifier.fillMaxSize(),
    ) {
        if (totalCount == 0 && incomplete.isEmpty()) {
            DownloadsEmptyState(kind = MediaKind.Music, onHomeClick = onHomeClick)
            return@FlowPullToRefreshBox
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = listPadding(if (columns > 1) MusicGridPadding else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(GridSpacing),
            modifier = Modifier.fillMaxSize(),
        ) {
            fullWidth("header") { Box(Modifier.padding(horizontal = rowInset(columns))) { header() } }
            fullWidth("collections") { shelf(rowInset(columns)) }
            activeSection(incomplete, progress, emptySet(), activeActions, rowInset(columns))
            if (tracks.isNotEmpty()) {
                fullWidth(
                    "section_done",
                ) { DownloadsSectionHeader(stringResource(R.string.downloads_section_downloaded), tracks.size, rowInset(columns)) }
            } else if (query.isNotBlank()) {
                fullWidth("no_results") { NoResults(query) }
            }
            itemsIndexed(tracks, key = { _, track -> track.track.videoId }, contentType = { _, _ -> "track" }) { index, track ->
                DownloadSwipeToDelete(
                    enabled = !selection.active,
                    onDelete = { onSwipeDelete(track.track.videoId) },
                    modifier = animateMediaGridItem(),
                ) {
                    MusicDownloadRow(
                        downloadedTrack = track,
                        selectionMode = selection.active,
                        selected = track.track.videoId in selection.ids,
                        onClick = { if (selection.active) selection.onToggle(track.track.videoId) else onMusicClick(tracks, index) },
                    )
                }
            }
        }
    }
}

private fun LazyGridScope.fullWidth(
    key: String,
    content: @Composable () -> Unit,
) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = key) { content() }
}

private fun LazyGridScope.activeSection(
    incomplete: List<DownloadWithItems>,
    progress: Map<String, DownloadProgressUpdate>,
    mergingIds: Set<String>,
    actions: ActiveDownloadActions,
    inset: Dp,
) {
    if (incomplete.isEmpty()) return
    fullWidth("section_active") {
        DownloadsSectionHeader(stringResource(R.string.downloads_section_in_progress), incomplete.size, inset) {
            IconButton(onClick = actions.onCancelAll) {
                Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.remove_incomplete_downloads))
            }
        }
    }
    items(
        items = incomplete,
        key = { "active_${it.download.videoId}" },
        span = { GridItemSpan(maxLineSpan) },
        contentType = { "active" },
    ) { download ->
        val id = download.download.videoId
        ActiveDownloadRow(
            download = download,
            progress = progress[id],
            isMerging = id in mergingIds,
            onPauseClick = { actions.onPause(id) },
            onResumeClick = { actions.onResume(id) },
            onRetryClick = { actions.onRetry(id) },
            onCancelClick = { actions.onCancel(id, download.download.title) },
            modifier = animateMediaGridItem(),
        )
    }
}

@Composable
private fun DownloadsSectionHeader(
    title: String,
    count: Int,
    inset: Dp,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = inset, end = 4.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp).weight(1f),
        )
        action?.invoke()
    }
}

@Composable
private fun NoResults(query: String) {
    FlowEmptyState(
        title = stringResource(R.string.downloads_no_results, query.trim()),
        icon = Icons.Outlined.SearchOff,
        modifier = Modifier.padding(top = 48.dp),
    )
}
