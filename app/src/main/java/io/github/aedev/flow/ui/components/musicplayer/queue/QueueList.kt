package io.github.aedev.flow.ui.components.musicplayer.queue

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.RepeatMode
import io.github.aedev.flow.ui.components.layout.FlowBottomInsets
import io.github.aedev.flow.ui.components.layout.LocalFlowBottomInsets
import io.github.aedev.flow.ui.components.shared.FlowSelectionToolbar
import io.github.aedev.flow.ui.components.shared.FlowSwitch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** What the queue can ask the player to do; one instance per player so rows stay skippable. */
@Immutable
internal class QueueActions(
    val onTrackClick: (Int) -> Unit,
    val onMoveTrack: (Int, Int) -> Unit,
    val onPlayNextFromQueue: (Int) -> Unit,
    val onSendQueueTrackToEnd: (Int) -> Unit,
    val onRemoveFromQueue: (Int) -> Unit,
    val onRadioTrackClick: (MusicTrack) -> Unit,
    val onPlayNextRadio: (MusicTrack) -> Unit,
    val onAddRadioToQueue: (MusicTrack) -> Unit,
    val onToggleEndlessRadio: (Boolean) -> Unit,
    val onShuffleQueue: () -> Unit,
    val onCycleRepeat: () -> Unit,
    val onRemoveTracks: (List<Int>) -> Unit,
    val onDownloadTracks: (List<MusicTrack>) -> Unit,
    val onSaveTracks: (List<MusicTrack>) -> Unit,
)

/**
 * The queue itself: hold and drag a row to reorder, swipe a row either way for what [swipes] sets
 * for that side, then the endless-radio toggle and its suggestions. While [selection] is active a
 * tap picks a row instead, and the picked songs can be moved, saved, downloaded or removed together.
 * [selectEntry] adds a Select row for a host with no header of its own.
 */
@Composable
internal fun QueueList(
    queue: List<MusicTrack>,
    radioTracks: List<MusicTrack>,
    currentIndex: Int,
    isPlaying: Boolean,
    isRadioLoading: Boolean,
    endlessRadioEnabled: Boolean,
    downloadedTrackIds: Set<String>,
    swipes: QueueSwipes,
    actions: QueueActions,
    modifier: Modifier = Modifier,
    clearNavigationBar: Boolean = true,
    selection: QueueSelection = remember { QueueSelection() },
    selectEntry: Boolean = false,
) {
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current

    val localQueue = remember { mutableStateListOf<MusicTrack>() }
    val localKeys = remember { mutableStateListOf<String>() }
    LaunchedEffect(queue) {
        localQueue.clear()
        localQueue.addAll(queue)
        localKeys.clear()
        localKeys.addAll(queueKeys(queue))
        selection.retain(localKeys)
    }
    val selecting = selection.active
    BackHandler(enabled = selecting) { selection.end() }
    val picked = selection.keys.orEmpty()

    fun pickedIndices(): List<Int> = localKeys.indices.filter { localKeys[it] in picked }

    fun pickedTracks(): List<MusicTrack> = pickedIndices().mapNotNull { localQueue.getOrNull(it) }

    var dragInfo by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val reorderableState =
        rememberReorderableLazyListState(listState) { from, to ->
            val fromIdx = from.index
            val toIdx = to.index
            if (fromIdx in localQueue.indices && toIdx in localQueue.indices && fromIdx != toIdx) {
                dragInfo = (dragInfo?.first ?: fromIdx) to toIdx
                localQueue.add(toIdx, localQueue.removeAt(fromIdx))
                localKeys.add(toIdx, localKeys.removeAt(fromIdx))
            }
        }

    LaunchedEffect(reorderableState.isAnyItemDragging) {
        if (!reorderableState.isAnyItemDragging) {
            dragInfo?.let { (from, to) ->
                if (from != to) actions.onMoveTrack(from, to)
            }
            dragInfo = null
        }
    }

    LaunchedEffect(Unit) {
        if (currentIndex in queue.indices) {
            listState.scrollToItem(currentIndex)
        }
    }

    fun onQueueSwipe(
        swipe: QueueRowSwipe,
        index: Int,
    ) {
        when (swipe) {
            QueueRowSwipe.PLAY_NEXT -> actions.onPlayNextFromQueue(index)
            QueueRowSwipe.MOVE_TO_END, QueueRowSwipe.ADD_TO_QUEUE -> actions.onSendQueueTrackToEnd(index)
            QueueRowSwipe.REMOVE -> actions.onRemoveFromQueue(index)
        }
    }

    var menuTarget by remember { mutableStateOf<QueueMenuTarget?>(null) }

    fun onMenuAction(
        target: QueueMenuTarget,
        action: QueueRowMenuAction,
    ) {
        val index = target.index
        when (action) {
            QueueRowMenuAction.PLAY_NEXT -> {
                if (target.isRadioItem) actions.onPlayNextRadio(target.track) else actions.onPlayNextFromQueue(index)
            }

            QueueRowMenuAction.ADD_TO_QUEUE -> {
                actions.onAddRadioToQueue(target.track)
            }

            QueueRowMenuAction.MOVE_UP -> {
                actions.onMoveTrack(index, index - 1)
            }

            QueueRowMenuAction.MOVE_DOWN -> {
                actions.onMoveTrack(index, index + 1)
            }

            QueueRowMenuAction.MOVE_TO_END -> {
                actions.onSendQueueTrackToEnd(index)
            }

            QueueRowMenuAction.REMOVE -> {
                actions.onRemoveFromQueue(index)
            }

            QueueRowMenuAction.SELECT -> {
                selection.start(localKeys.getOrNull(index))
            }
        }
    }

    val playNextLabel = stringResource(R.string.play_next)
    val moveToEndLabel = stringResource(R.string.queue_move_to_end)
    val removeLabel = stringResource(R.string.remove_from_queue)

    Box(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (selecting) {
                QueueSelectionBar(
                    count = picked.size,
                    allPicked = localKeys.isNotEmpty() && picked.containsAll(localKeys),
                    onToggleAll = { selection.toggleAll(localKeys.toList()) },
                    onClose = selection::end,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
            } else if (selectEntry) {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp), contentAlignment = Alignment.CenterEnd) {
                    TextButton(onClick = { selection.start() }) { Text(stringResource(R.string.select_songs)) }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(localQueue, key = { index, _ -> localKeys.getOrNull(index) ?: index }) { index, track ->
                    ReorderableItem(
                        state = reorderableState,
                        key = localKeys.getOrNull(index) ?: index,
                        enabled = !selecting,
                    ) { isDragging ->
                        val scale by animateFloatAsState(
                            targetValue = if (isDragging) 1.02f else 1f,
                            animationSpec =
                                spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            label = "queueRowLift",
                        )
                        val isCurrent = index == currentIndex
                        val key = localKeys.getOrNull(index) ?: track.videoId
                        QueueTrackRow(
                            track = track,
                            isCurrent = isCurrent,
                            isPlaying = isPlaying,
                            isRadioItem = false,
                            isDragging = isDragging,
                            swipeEnabled = !isCurrent && !isDragging && !selecting,
                            isDownloaded = downloadedTrackIds.contains(track.videoId),
                            leftSwipe = swipes.left.rowSwipe,
                            rightSwipe = swipes.right.rowSwipe,
                            rowKey = localKeys.getOrNull(index) ?: track.videoId,
                            onClick = { if (selecting) selection.toggle(key) else actions.onTrackClick(index) },
                            onSwipe = { swipe -> onQueueSwipe(swipe, index) },
                            onMenu = if (isCurrent || selecting) null else ({ menuTarget = QueueMenuTarget(track, index, false) }),
                            selected = if (selecting) key in picked else null,
                            modifier =
                                Modifier
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                    }.semantics {
                                        if (!isCurrent) {
                                            customActions =
                                                listOf(
                                                    playNextLabel to QueueRowSwipe.PLAY_NEXT,
                                                    moveToEndLabel to QueueRowSwipe.MOVE_TO_END,
                                                    removeLabel to QueueRowSwipe.REMOVE,
                                                ).map { (label, swipe) ->
                                                    CustomAccessibilityAction(label) {
                                                        onQueueSwipe(swipe, index)
                                                        true
                                                    }
                                                }
                                        }
                                    }.longPressDraggableHandle(
                                        onDragStarted = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDragStopped = {
                                            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                        },
                                    ),
                        )
                    }
                }

                if (!selecting) {
                    item(key = "endless_radio_toggle") {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .animateItem()
                                    .padding(top = 10.dp, bottom = 4.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { actions.onToggleEndlessRadio(!endlessRadioEnabled) }
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Sensors,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                            Text(
                                text = stringResource(R.string.music_endless_radio_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            FlowSwitch(
                                checked = endlessRadioEnabled,
                                onCheckedChange = actions.onToggleEndlessRadio,
                            )
                        }
                    }

                    if (endlessRadioEnabled) {
                        itemsIndexed(radioTracks, key = { _, track -> "radio:${track.videoId}" }) { _, track ->
                            Box(modifier = Modifier.animateItem()) {
                                QueueTrackRow(
                                    track = track,
                                    isCurrent = false,
                                    isPlaying = isPlaying,
                                    isRadioItem = true,
                                    isDragging = false,
                                    swipeEnabled = true,
                                    isDownloaded = downloadedTrackIds.contains(track.videoId),
                                    leftSwipe = QueueRowSwipe.PLAY_NEXT,
                                    rightSwipe = QueueRowSwipe.ADD_TO_QUEUE,
                                    rowKey = "radio:${track.videoId}",
                                    onClick = { actions.onRadioTrackClick(track) },
                                    onSwipe = { swipe ->
                                        if (swipe == QueueRowSwipe.PLAY_NEXT) {
                                            actions.onPlayNextRadio(track)
                                        } else {
                                            actions.onAddRadioToQueue(track)
                                        }
                                    },
                                    onMenu = { menuTarget = QueueMenuTarget(track, index = -1, isRadioItem = true) },
                                )
                            }
                        }
                        if (isRadioLoading && radioTracks.isEmpty()) {
                            item(key = "radio_loading") {
                                RadioLoadingRow()
                            }
                        }
                    }
                }

                item(key = "bottom_spacer") {
                    Spacer(
                        modifier =
                            Modifier
                                .then(if (clearNavigationBar) Modifier.navigationBarsPadding() else Modifier)
                                .height(if (selecting) SelectionToolbarRoom else 28.dp),
                    )
                }
            }
        }
        // The toolbar floats over the queue's own bottom edge, not above the app's bars under the player.
        CompositionLocalProvider(LocalFlowBottomInsets provides FlowBottomInsets.None) {
            FlowSelectionToolbar(
                visible = selecting && picked.isNotEmpty(),
                summary = pluralStringResource(R.plurals.selected_count_template, picked.size, picked.size),
                actions =
                    queueSelectionActions(
                        onPlayNext = {
                            val moves = playNextMoves(pickedIndices(), currentIndex, localQueue.size)
                            moves.forEach { (from, to) -> actions.onMoveTrack(from, to) }
                            selection.end()
                        },
                        onAddToPlaylist = {
                            actions.onSaveTracks(pickedTracks())
                            selection.end()
                        },
                        onDownload = {
                            actions.onDownloadTracks(pickedTracks())
                            selection.end()
                        },
                        onRemove = {
                            actions.onRemoveTracks(removalOrder(pickedIndices(), currentIndex))
                            selection.end()
                        },
                    ),
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .then(if (clearNavigationBar) Modifier.navigationBarsPadding() else Modifier),
            )
        }
    }

    menuTarget?.let { target ->
        QueueRowMenuSheet(
            track = target.track,
            actions = queueRowMenuActions(target.isRadioItem, target.index, localQueue.lastIndex),
            onAction = { action -> onMenuAction(target, action) },
            onDismiss = { menuTarget = null },
        )
    }
}

/** Room under the last row so the selection toolbar never covers it. */
private val SelectionToolbarRoom = 96.dp

/** The row a queue menu is open for; a radio row has no queue index. */
private class QueueMenuTarget(
    val track: MusicTrack,
    val index: Int,
    val isRadioItem: Boolean,
)
