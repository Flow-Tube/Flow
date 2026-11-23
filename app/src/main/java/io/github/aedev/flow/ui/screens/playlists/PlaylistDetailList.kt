package io.github.aedev.flow.ui.screens.playlists

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.PlaylistSwipes
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.components.layout.flowBottomContentPadding
import io.github.aedev.flow.ui.components.library.PlaylistVideoRow
import io.github.aedev.flow.ui.components.library.libraryListThumbnailWidth
import io.github.aedev.flow.ui.components.shared.FlowEmptyState
import io.github.aedev.flow.ui.components.shared.FlowLoadingIndicator
import io.github.aedev.flow.ui.components.shared.animateMediaListItem
import io.github.aedev.flow.ui.components.shared.reorderHandleDrag
import io.github.aedev.flow.ui.components.shared.reorderLift
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState

/** How the list lets the viewer act on its videos. */
internal class PlaylistListMode(
    val canReorder: Boolean,
    val canModify: Boolean,
    val selectionMode: Boolean,
    val selectedIds: Set<String>,
    val showAddedDate: Boolean,
    val isWatchLater: Boolean,
    val isLikes: Boolean = false,
    val searchQuery: String = "",
    val swipes: PlaylistSwipes = PlaylistSwipes(),
    val swipeEnabled: Boolean = false,
)

/** The playlist's videos under an optional [header], which stays put while videos are dragged. */
@Composable
internal fun PlaylistDetailList(
    videos: List<Video>,
    mode: PlaylistListMode,
    hasMorePages: Boolean,
    listState: LazyListState,
    reorderState: ReorderableLazyListState,
    onVideoClick: (index: Int, video: Video) -> Unit,
    onRemove: (Video) -> Unit,
    onMoveVideo: (video: Video, target: Int) -> Unit,
    onNearEnd: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = flowBottomContentPadding()),
    header: (@Composable () -> Unit)? = null,
) {
    // Until the first videos are laid out the list is only its header, which would read as its end.
    val canPage = hasMorePages && videos.isNotEmpty()
    LaunchedEffect(listState, canPage) {
        if (!canPage) return@LaunchedEffect
        // Keyed on the item count, so each page that arrives while the end is still in view asks once more.
        snapshotFlow {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            layout.totalItemsCount.takeIf { nearListEnd(last, it) } ?: -1
        }.filter { it >= 0 }
            .distinctUntilChanged()
            .collect { onNearEnd() }
    }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val thumbnailWidth = remember(maxWidth) { libraryListThumbnailWidth(maxWidth) }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
            if (header != null) item(key = "playlist-header", contentType = "header") { header() }
            if (videos.isEmpty() && mode.searchQuery.isNotBlank()) {
                item(key = "playlist-no-results", contentType = "empty") {
                    FlowEmptyState(
                        title = stringResource(R.string.playlist_search_no_results, mode.searchQuery.trim()),
                        icon = Icons.Outlined.SearchOff,
                    )
                }
            } else if (videos.isEmpty() && !hasMorePages) {
                item(key = "playlist-empty", contentType = "empty") {
                    when {
                        mode.isLikes -> {
                            FlowEmptyState(
                                title = stringResource(R.string.liked_videos_empty_title),
                                subtitle = stringResource(R.string.liked_videos_empty_body),
                                icon = Icons.Outlined.ThumbUp,
                            )
                        }

                        mode.isWatchLater -> {
                            FlowEmptyState(
                                title = stringResource(R.string.no_videos_saved),
                                subtitle = stringResource(R.string.no_videos_saved_body),
                                icon = Icons.Default.WatchLater,
                            )
                        }

                        else -> {
                            FlowEmptyState(
                                title = stringResource(R.string.playlist_empty_title),
                                subtitle = stringResource(R.string.playlist_empty_desc),
                                icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                            )
                        }
                    }
                }
            }
            itemsIndexed(items = videos, key = { _, video -> video.id }, contentType = { _, _ -> "playlist-video" }) { index, video ->
                ReorderableItem(
                    state = reorderState,
                    key = video.id,
                    enabled = mode.canReorder,
                    animateItemModifier = if (mode.canReorder) Modifier.animateItem() else animateMediaListItem(),
                ) { isDragging ->
                    val canMoveUp = mode.canReorder && index > 0
                    val canMoveDown = mode.canReorder && index < videos.lastIndex
                    PlaylistVideoRow(
                        modifier = Modifier.reorderLift(isDragging),
                        video = video,
                        isSelected = video.id in mode.selectedIds,
                        inSelectionMode = mode.selectionMode,
                        canModify = mode.canModify,
                        dragHandleModifier = reorderHandleDrag(enabled = mode.canReorder && !mode.selectionMode),
                        showDragHandle = mode.canReorder,
                        showAddedDate = mode.showAddedDate,
                        isWatchLater = mode.isWatchLater,
                        isLikes = mode.isLikes,
                        swipes = mode.swipes,
                        swipeEnabled = mode.swipeEnabled,
                        onRemove = { onRemove(video) },
                        onMoveToTop = if (canMoveUp) ({ onMoveVideo(video, 0) }) else null,
                        onMoveToBottom = if (canMoveDown) ({ onMoveVideo(video, videos.lastIndex) }) else null,
                        onMoveUp = if (canMoveUp) ({ onMoveVideo(video, index - 1) }) else null,
                        onMoveDown = if (canMoveDown) ({ onMoveVideo(video, index + 1) }) else null,
                        positionLabel =
                            if (mode.canReorder) stringResource(R.string.playlist_video_position, index + 1, videos.size) else null,
                        onClick = { onVideoClick(index, video) },
                        thumbnailWidth = thumbnailWidth,
                    )
                }
            }
            if (hasMorePages) {
                item(key = "playlist-loading-more", contentType = "loading") {
                    FlowLoadingIndicator(modifier = Modifier.padding(vertical = 24.dp))
                }
            }
        }
    }
}
