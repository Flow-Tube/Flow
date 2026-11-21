package io.github.aedev.flow.ui.screens.home

import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import io.github.aedev.flow.data.model.Video
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Feeds [viewModel] what Home knows: whether it is in the foreground, and which card the grid's
 * layout puts in the preview spot. The layout is read in a snapshotFlow, never in composition, and
 * only while the gate is open.
 */
@Composable
internal fun HomePreviewEffects(
    viewModel: HomePreviewViewModel,
    gridState: LazyGridState,
    videos: List<Video>,
    enabled: Boolean,
) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val foreground = enabled && lifecycleState.isAtLeast(Lifecycle.State.RESUMED) && LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(foreground) { viewModel.setForeground(foreground) }
    DisposableEffect(viewModel) { onDispose { viewModel.setForeground(false) } }

    val gate by viewModel.gate.collectAsStateWithLifecycle()
    val previewable = remember(videos) { videos.filter(Video::canPreview).mapTo(HashSet()) { it.id } }
    LaunchedEffect(gridState, previewable, gate.open) {
        if (!gate.open) return@LaunchedEffect
        snapshotFlow {
            val info = gridState.layoutInfo
            HomePreviewSelection.select(
                slots = info.previewSlots(),
                window = info.previewWindow(),
                scrolling = gridState.isScrollInProgress,
                current = viewModel.candidate.value,
                previewable = { it in previewable },
            ) as String?
        }.distinctUntilChanged()
            .collect(viewModel::onCandidate)
    }
}

private fun Video.canPreview(): Boolean = !isShort && !isLive && !isUpcoming && duration > 0

private fun LazyGridLayoutInfo.previewSlots(): List<PreviewSlot> = visibleItemsInfo.map { PreviewSlot(it.key, it.offset.y, it.size.height) }

private fun LazyGridLayoutInfo.previewWindow(): PreviewWindow = PreviewWindow(top = 0, bottom = viewportEndOffset - afterContentPadding)
