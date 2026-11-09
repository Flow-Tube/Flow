package io.github.aedev.flow.ui.components.musicplayer.queue

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
import androidx.compose.runtime.Composable
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
import io.github.aedev.flow.ui.components.shared.FlowSwitch
import io.github.aedev.flow.ui.components.shared.rememberReorderableLazyListState
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
)

/**
 * The queue itself: hold and drag a row to reorder, swipe a row either way for what [swipes] sets
 * for that side, then the endless-radio toggle and its suggestions.
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
) {
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current

    val localQueue = remember { mutableStateListOf<MusicTrack>() }
    val localKeys = remember { mutableStateListOf<String>() }
    LaunchedEffect(queue) {
        localQueue.clear()
        localQueue.addAll(queue)
        val seen = HashMap<String, Int>()
        localKeys.clear()
        queue.forEach { track ->
            val occurrence = (seen[track.videoId] ?: 0) + 1
            seen[track.videoId] = occurrence
            localKeys.add("${track.videoId}#$occurrence")
        }
    }

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

    val playNextLabel = stringResource(R.string.play_next)
    val moveToEndLabel = stringResource(R.string.queue_move_to_end)
    val removeLabel = stringResource(R.string.remove_from_queue)

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(localQueue, key = { index, _ -> localKeys.getOrNull(index) ?: index }) { index, track ->
            ReorderableItem(
                state = reorderableState,
                key = localKeys.getOrNull(index) ?: index,
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
                QueueTrackRow(
                    track = track,
                    isCurrent = isCurrent,
                    isPlaying = isPlaying,
                    isRadioItem = false,
                    isDragging = isDragging,
                    swipeEnabled = !isCurrent && !isDragging,
                    isDownloaded = downloadedTrackIds.contains(track.videoId),
                    leftSwipe = swipes.left.rowSwipe,
                    rightSwipe = swipes.right.rowSwipe,
                    rowKey = localKeys.getOrNull(index) ?: track.videoId,
                    onClick = { actions.onTrackClick(index) },
                    onSwipe = { swipe -> onQueueSwipe(swipe, index) },
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
                            if (swipe == QueueRowSwipe.PLAY_NEXT) actions.onPlayNextRadio(track) else actions.onAddRadioToQueue(track)
                        },
                    )
                }
            }
            if (isRadioLoading && radioTracks.isEmpty()) {
                item(key = "radio_loading") {
                    RadioLoadingRow()
                }
            }
        }

        item(key = "bottom_spacer") {
            Spacer(
                modifier =
                    Modifier
                        .then(if (clearNavigationBar) Modifier.navigationBarsPadding() else Modifier)
                        .height(28.dp),
            )
        }
    }
}

/** Title, track count, and the shuffle and repeat pair shared by the sheet and the side pane. */
@Composable
internal fun QueueHeader(
    trackCount: Int,
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    onShuffleQueue: () -> Unit,
    onCycleRepeat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 22.dp, end = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.up_next),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = pluralStringResource(R.plurals.queue_track_count, trackCount, trackCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilledTonalIconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onShuffleQueue()
                },
                modifier = Modifier.size(width = 52.dp, height = 42.dp),
                shape =
                    RoundedCornerShape(
                        topStart = 21.dp,
                        bottomStart = 21.dp,
                        topEnd = 6.dp,
                        bottomEnd = 6.dp,
                    ),
                colors =
                    IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor =
                            if (shuffleEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            },
                        contentColor =
                            if (shuffleEnabled) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            },
                    ),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Shuffle,
                    contentDescription = stringResource(R.string.shuffle),
                    modifier = Modifier.size(20.dp),
                )
            }
            val repeatActive = repeatMode != RepeatMode.OFF
            FilledTonalIconButton(
                onClick = onCycleRepeat,
                modifier = Modifier.size(width = 52.dp, height = 42.dp),
                shape =
                    RoundedCornerShape(
                        topStart = 6.dp,
                        bottomStart = 6.dp,
                        topEnd = 21.dp,
                        bottomEnd = 21.dp,
                    ),
                colors =
                    IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor =
                            if (repeatActive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            },
                        contentColor =
                            if (repeatActive) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                            },
                    ),
            ) {
                Icon(
                    imageVector =
                        if (repeatMode == RepeatMode.ONE) {
                            Icons.Rounded.RepeatOne
                        } else {
                            Icons.Rounded.Repeat
                        },
                    contentDescription = stringResource(R.string.repeat),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
