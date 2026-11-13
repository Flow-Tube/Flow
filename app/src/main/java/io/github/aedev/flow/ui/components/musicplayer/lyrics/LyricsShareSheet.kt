package io.github.aedev.flow.ui.components.musicplayer.lyrics

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.lyrics.LyricsEntry
import io.github.aedev.flow.ui.components.shared.FlowActionButton
import io.github.aedev.flow.ui.components.shared.FlowModalSheetDefaults
import io.github.aedev.flow.ui.components.shared.FlowSheetHeader
import io.github.aedev.flow.ui.components.shared.rememberFlowSheetState
import io.github.aedev.flow.ui.components.shared.toggleHapticType
import kotlinx.coroutines.launch

private val EdgePadding = 16.dp
private val RowSpacing = 4.dp
private val ControlSpacing = 12.dp
private val LinePadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
private const val DISABLED_LINE_ALPHA = 0.38f

/** Picks up to [MAX_SHARE_LINES] lyric lines, then styles the card they make and saves or shares it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LyricsShareSheet(
    title: String,
    artist: String,
    artworkUrl: String,
    lines: List<LyricsEntry>,
    positionProvider: () -> Long,
    initialAlign: TextAlign,
    onDismiss: () -> Unit,
) {
    val texts = remember(lines) { shareableLyricLines(lines) }
    var selected by remember(texts) { mutableStateOf(emptyList<Int>()) }
    var customising by remember { mutableStateOf(false) }
    // Held here so going back to change the lines keeps the look already picked.
    var style by remember { mutableStateOf(LyricsCardStyle.ARTWORK) }
    var align by remember { mutableStateOf(initialAlign) }
    var frame by remember { mutableStateOf(LyricsCardFrame.SQUARE) }
    var aspect by remember { mutableStateOf(LyricsCardAspect.FIT) }
    val styling = customising && selected.isNotEmpty()
    val sheetState = rememberFlowSheetState()
    val scope = rememberCoroutineScope()
    val properties = remember(styling) { ModalBottomSheetProperties(shouldDismissOnBackPress = !styling) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        properties = properties,
        modifier = FlowModalSheetDefaults.modifier,
        contentWindowInsets = FlowModalSheetDefaults.contentWindowInsets,
    ) {
        BackHandler(enabled = styling) { customising = false }
        FlowSheetHeader(
            title = stringResource(R.string.lyrics_share),
            subtitle = stringResource(R.string.lyrics_share_selected, selected.size, MAX_SHARE_LINES),
            onClose = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } },
            onBack = if (styling) ({ customising = false }) else null,
            showDragHandle = false,
        )
        if (styling) {
            LyricsShareStyler(
                title = title,
                artist = artist,
                artworkUrl = artworkUrl,
                lines = selected.map { texts[it] },
                style = style,
                onStyleChange = { style = it },
                align = align,
                onAlignChange = { align = it },
                frame = frame,
                onFrameChange = { frame = it },
                aspect = aspect,
                onAspectChange = { aspect = it },
            )
        } else {
            LyricsSharePicker(
                texts = texts,
                selected = selected,
                // Read once: the picker opens at the line being sung and must not follow playback.
                initialIndex = remember(lines) { Snapshot.withoutReadObservation { shareLineIndexAt(lines, positionProvider()) } },
                onToggle = { selected = toggleShareLine(selected, it) },
                onNext = { customising = true },
            )
        }
    }
}

@Composable
private fun ColumnScope.LyricsSharePicker(
    texts: List<String>,
    selected: List<Int>,
    initialIndex: Int,
    onToggle: (Int) -> Unit,
    onNext: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    LazyColumn(
        state = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex),
        modifier = Modifier.weight(1f, fill = false),
        contentPadding = PaddingValues(horizontal = EdgePadding, vertical = ControlSpacing),
        verticalArrangement = Arrangement.spacedBy(RowSpacing),
    ) {
        itemsIndexed(texts) { index, text ->
            val isSelected = index in selected
            val enabled = isSelected || selected.size < MAX_SHARE_LINES
            Surface(
                selected = isSelected,
                onClick = {
                    haptics.performHapticFeedback(toggleHapticType(!isSelected))
                    onToggle(index)
                },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                contentColor =
                    when {
                        isSelected -> MaterialTheme.colorScheme.onSecondaryContainer
                        enabled -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_LINE_ALPHA)
                    },
            ) {
                Text(
                    text = text,
                    modifier = Modifier.padding(LinePadding),
                    style = lyricsShareLineStyle(MaterialTheme.typography.titleLarge),
                )
            }
        }
    }
    FlowActionButton(
        text = stringResource(R.string.next),
        onClick = onNext,
        enabled = selected.isNotEmpty(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = EdgePadding, vertical = ControlSpacing),
    )
}
