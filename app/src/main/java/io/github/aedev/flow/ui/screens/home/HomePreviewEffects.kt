package io.github.aedev.flow.ui.screens.home

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.components.home.HomePreviewControls
import io.github.aedev.flow.ui.components.home.HomePreviewControlsState
import io.github.aedev.flow.ui.components.home.HomePreviewSurface
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

/** What each feed card draws over its thumbnail: the preview, on the one card it is for. [onOpen] gets the card's video id. */
@Composable
internal fun rememberHomePreviewOverlay(
    viewModel: HomePreviewViewModel,
    onOpen: (String) -> Unit,
): @Composable BoxScope.(String) -> Unit {
    val currentOnOpen by rememberUpdatedState(onOpen)
    return remember(viewModel) {
        val overlay: @Composable BoxScope.(
            String,
        ) -> Unit = { videoId -> HomePreviewCardOverlay(viewModel, videoId) { currentOnOpen(videoId) } }
        overlay
    }
}

@Composable
private fun HomePreviewCardOverlay(
    viewModel: HomePreviewViewModel,
    videoId: String,
    onOpen: () -> Unit,
) {
    val active by viewModel.activeVideoId.collectAsStateWithLifecycle()
    if (active != videoId) return
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.videoId != videoId || state.phase == HomePreviewPhase.FINISHED) return
    val player by viewModel.surfacePlayer.collectAsStateWithLifecycle()
    HomePreviewSurface(
        player = player,
        showVideo = state.showsVideo,
        onAttached = { attached -> viewModel.onSurfaceAttached(videoId, attached) },
    )
    if (state.showsVideo) {
        HomePreviewControls(
            state =
                HomePreviewControlsState(
                    muted = state.muted,
                    hasCaptions = state.hasCaptions,
                    captionsOn = state.captionsOn,
                    buffering = state.buffering,
                    playing = state.playing,
                    durationMs = state.durationMs,
                    storyboard = state.storyboard,
                ),
            readPositionMs = { viewModel.positionMs },
            onToggleMute = viewModel::toggleMute,
            onToggleCaptions = viewModel::toggleCaptions,
            onSeekTo = viewModel::seekTo,
            onOpen = onOpen,
        )
    }
}

private fun Video.canPreview(): Boolean = !isShort && !isLive && !isUpcoming && duration > 0

private fun LazyGridLayoutInfo.previewSlots(): List<PreviewSlot> = visibleItemsInfo.map { PreviewSlot(it.key, it.offset.y, it.size.height) }

private fun LazyGridLayoutInfo.previewWindow(): PreviewWindow = PreviewWindow(top = 0, bottom = viewportEndOffset - afterContentPadding)
