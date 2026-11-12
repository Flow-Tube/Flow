package io.github.aedev.flow.ui.components.videoplayer.sheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.transcript.TranscriptCue
import io.github.aedev.flow.player.state.SubtitleOption
import io.github.aedev.flow.ui.components.shared.FlowBottomSheet
import io.github.aedev.flow.ui.components.shared.FlowLoadingIndicator
import io.github.aedev.flow.ui.components.shared.FlowSearchField
import io.github.aedev.flow.ui.components.shared.FlowSheetHeader
import io.github.aedev.flow.ui.components.shared.defaultSheetExpandedHeight
import io.github.aedev.flow.ui.components.shared.rememberFlowBottomSheetState
import io.github.aedev.flow.ui.components.shared.rememberMediaArtworkTint

private val EmptyStateHeight = 160.dp

/** How far up the list the line being spoken is parked when the transcript follows playback. */
private const val FOLLOW_OFFSET_ITEMS = 2

/**
 * The video's captions as a readable, seekable list that follows playback.
 *
 * The position arrives as a lambda and is read inside `derivedStateOf`, so a clock that ticks every
 * second only recomposes the rows when the line being spoken actually changes.
 *
 * Scrolling hands the list to the reader for good: playback stops dragging it around and the sync
 * pill offers the way back, rather than the line being spoken yanking them off whatever they were
 * reading a moment after they let go.
 */
@Composable
fun FlowTranscriptBottomSheet(
    cues: List<TranscriptCue>,
    isLoading: Boolean,
    currentPositionMs: () -> Long,
    artworkUrl: String?,
    onSeekMs: (Long) -> Unit,
    onDismiss: () -> Unit,
    chapters: List<TranscriptChapter> = emptyList(),
    tracks: List<SubtitleOption> = emptyList(),
    selectedTrackUrl: String? = null,
    onTrackSelected: (String) -> Unit = {},
    exportTitle: String? = null,
    expandedHeight: Dp? = null,
    collapsedHeight: Dp = 0.dp,
    enableVerticalDismiss: Boolean = true,
    onSheetProgressChange: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberFlowBottomSheetState()
    val listState = rememberLazyListState()
    val tint = rememberMediaArtworkTint(artworkUrl)
    var query by remember { mutableStateOf("") }

    val items =
        remember(cues, chapters, query) {
            val matches =
                if (query.isBlank()) cues else cues.filter { it.text.contains(query, ignoreCase = true) }
            transcriptItems(matches, chapters)
        }

    val activeStartMs by remember(cues) {
        derivedStateOf {
            val position = currentPositionMs()
            cues.lastOrNull { it.startMs <= position }?.startMs
        }
    }

    // One instance for the lifetime of the sheet, so the scroll connection below cannot be left
    // writing to a state object that a track change has already replaced.
    val following = remember { mutableStateOf(true) }
    LaunchedEffect(cues) { following.value = true }

    // Any scroll the reader starts — a drag, the fling off one, a wheel — hands them the list.
    // Nested scroll is what tells theirs from ours: the follow's own animateScrollToItem moves the
    // list without dispatching here at all, so it cannot cancel the very thing that started it.
    val readerScroll =
        remember(following) {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source == NestedScrollSource.UserInput && available.y != 0f) {
                        following.value = false
                    }
                    return Offset.Zero
                }
            }
        }

    val isFollowing = following.value && query.isBlank()

    LaunchedEffect(activeStartMs, isFollowing, items) {
        if (!isFollowing) return@LaunchedEffect
        val index = items.indexOfFirst { it is TranscriptItem.Line && it.cue.startMs == activeStartMs }
        if (index >= 0) {
            listState.animateScrollToItem((index - FOLLOW_OFFSET_ITEMS).coerceAtLeast(0))
        }
    }

    FlowBottomSheet(
        onDismiss = onDismiss,
        modifier = modifier,
        state = sheetState,
        expandedHeight = expandedHeight ?: defaultSheetExpandedHeight(),
        collapsedHeight = collapsedHeight,
        dismissible = enableVerticalDismiss,
        // As on every other player sheet: a tap on the video reaches the player instead of closing
        // what is being read over it.
        dismissOnOutsideTap = false,
        shape = RectangleShape,
        containerColor = MaterialTheme.colorScheme.surface,
        onProgressChange = onSheetProgressChange,
        header = { dragModifier ->
            Column(modifier = dragModifier) {
                FlowSheetHeader(
                    inSidePane = !enableVerticalDismiss,
                    title = stringResource(R.string.transcript),
                    onClose = { sheetState.dismiss() },
                    actions = {
                        if (exportTitle != null && cues.isNotEmpty()) {
                            TranscriptExportMenu(
                                cues = cues,
                                chapters = chapters,
                                title = exportTitle,
                                language = tracks.firstOrNull { it.url == selectedTrackUrl }?.label,
                            )
                        }
                    },
                )
                if (cues.isNotEmpty() || tracks.size > 1) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = TranscriptHorizontalPadding)
                                .padding(top = 10.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (cues.isNotEmpty()) {
                            FlowSearchField(
                                query = query,
                                onQueryChange = { query = it },
                                placeholder = stringResource(R.string.transcript_search_hint),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (tracks.size > 1) {
                            TranscriptLanguageMenu(
                                tracks = tracks,
                                selectedTrackUrl = selectedTrackUrl,
                                onTrackSelected = onTrackSelected,
                            )
                        }
                    }
                }
            }
        },
    ) {
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxWidth().height(EmptyStateHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    FlowLoadingIndicator()
                }
            }

            items.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxWidth().height(EmptyStateHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text =
                            stringResource(
                                if (cues.isEmpty()) R.string.transcript_unavailable else R.string.transcript_no_matches,
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = TranscriptHorizontalPadding),
                    )
                }
            }

            else -> {
                TranscriptList(
                    items = items,
                    listState = listState,
                    activeStartMs = activeStartMs,
                    // A search is its own way of leaving the current line, and its own way back, so
                    // the pill stays out of it.
                    showSyncPill = !following.value && query.isBlank() && activeStartMs != null,
                    tint = tint,
                    onSeekMs = onSeekMs,
                    onSync = { following.value = true },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .nestedScroll(readerScroll),
                )
            }
        }
    }
}
