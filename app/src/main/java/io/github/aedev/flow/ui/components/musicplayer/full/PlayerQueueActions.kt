package io.github.aedev.flow.ui.components.musicplayer.full

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.musicplayer.queue.QueueActions
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionUndo
import io.github.aedev.flow.ui.components.shared.quickactions.sharedQuickActionsViewModel
import io.github.aedev.flow.ui.screens.music.MusicPlayerViewModel

/** What the player's queue does with its rows; removals are announced, with undo for a single song. */
@Composable
internal fun rememberPlayerQueueActions(
    viewModel: MusicPlayerViewModel,
    sheets: FullPlayerSheetState,
): QueueActions {
    val quickActions = sharedQuickActionsViewModel()
    val removedFromQueue = stringResource(R.string.removed_from_queue)
    val resources = LocalResources.current
    return remember(viewModel, quickActions, removedFromQueue, resources, sheets) {
        QueueActions(
            onTrackClick = { viewModel.playFromQueue(it) },
            onMoveTrack = { from, to -> viewModel.moveTrack(from, to) },
            onPlayNextFromQueue = { viewModel.playNextFromQueuePosition(it) },
            onSendQueueTrackToEnd = { viewModel.moveQueueTrackToEnd(it) },
            onRemoveFromQueue = { index ->
                viewModel.removeFromQueue(index)?.let { quickActions.announce(removedFromQueue, QuickActionUndo.MusicQueueRemoval(it)) }
            },
            onRadioTrackClick = { viewModel.playRadioTrack(it) },
            onPlayNextRadio = { viewModel.playNextFromRadio(it) },
            onAddRadioToQueue = { viewModel.addRadioTrackToQueue(it) },
            onToggleEndlessRadio = { viewModel.setEndlessRadioEnabled(it) },
            onShuffleQueue = { viewModel.toggleShuffle() },
            onCycleRepeat = { viewModel.toggleRepeat() },
            onRemoveTracks = { indices ->
                val removed = indices.count { viewModel.removeFromQueue(it) != null }
                if (removed > 0) {
                    quickActions.announce(resources.getQuantityString(R.plurals.songs_removed_from_queue, removed, removed))
                }
            },
            onDownloadTracks = { viewModel.downloadTracks(it) },
            onSaveTracks = { sheets.saveTracks = it },
        )
    }
}
