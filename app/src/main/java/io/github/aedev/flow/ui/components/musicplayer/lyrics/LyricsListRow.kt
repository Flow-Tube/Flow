package io.github.aedev.flow.ui.components.musicplayer.lyrics

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.data.lyrics.LyricsEntry
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val STAGGER_PER_ROW_MS = 20L
private const val STAGGER_MAX_MS = 200L

/** An instrumental break's indicator stops this long before the next line starts. */
private const val INDICATOR_LEAD_OUT_MS = 650L

/**
 * One row of the lyrics list, placed at its offset from the anchor. Rows spring to a new offset
 * one after another, nearest first, so a line change cascades; a manual scroll freezes them.
 */
@Composable
internal fun LyricsListRow(
    listItem: LyricsListItem,
    distance: Int,
    targetOffset: Float,
    isInitialLayout: Boolean,
    state: LyricsPanelState,
    lines: List<LyricsEntry>,
    presentation: LyricsPresentation,
    isSynced: Boolean,
    active: Boolean,
    isPlaying: Boolean,
    syncOffsetMs: Long,
    motionEnabled: Boolean,
    blurOtherLines: Boolean,
    indicatorColor: Color,
    positionProvider: () -> Long,
    onHeight: (Int) -> Unit,
    onLineClick: (index: Int, item: LyricsEntry) -> Unit,
) {
    val offset = remember { Animatable(targetOffset) }
    val scrollSpec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(targetOffset, state.isAutoScrollEnabled, isInitialLayout) {
        when {
            isInitialLayout || !motionEnabled -> {
                offset.snapTo(targetOffset)
            }

            state.isAutoScrollEnabled -> {
                delay((distance * STAGGER_PER_ROW_MS).coerceAtMost(STAGGER_MAX_MS))
                offset.animateTo(targetOffset, scrollSpec)
            }
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(maxHeight = Constraints.Infinity))
                    layout(placeable.width, 0) { placeable.place(0, 0) }
                }.offset { IntOffset(0, (offset.value + state.userManualOffset).roundToInt()) },
    ) {
        when (listItem) {
            is LyricsListItem.Indicator -> {
                val gapEnd = listItem.gapEndMs - INDICATOR_LEAD_OUT_MS
                val visible by remember(listItem) {
                    derivedStateOf {
                        state.isAutoScrollEnabled && state.currentPosition in listItem.gapStartMs..gapEnd
                    }
                }
                IntervalIndicator(
                    gapStartMs = listItem.gapStartMs,
                    gapEndMs = gapEnd,
                    positionProvider = positionProvider,
                    visible = visible,
                    animate = isPlaying && active && motionEnabled,
                    color = indicatorColor,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .onSizeChanged { onHeight(it.height) }
                            .padding(horizontal = 24.dp)
                            .wrapContentWidth(Alignment.CenterHorizontally),
                )
            }

            is LyricsListItem.Line -> {
                val index = listItem.index
                val item = listItem.entry
                val pairedMainLineIndex =
                    remember(lines, index) {
                        if (item.isBackground) (index - 1 downTo 0).firstOrNull { lines.getOrNull(it)?.isBackground == false } ?: -1 else -1
                    }
                val bgVisible by remember(listItem, pairedMainLineIndex) {
                    derivedStateOf {
                        if (!item.isBackground) return@derivedStateOf false
                        val inGapWithMain =
                            pairedMainLineIndex != -1 &&
                                state.currentPosition >= lines[pairedMainLineIndex].time &&
                                state.currentPosition <= item.time
                        state.activeLineIndices.contains(pairedMainLineIndex) || state.activeLineIndices.contains(index) || inGapWithMain
                    }
                }
                LyricsLine(
                    index = index,
                    item = item,
                    isSynced = isSynced,
                    // The && stops the active line's frame loop while the panel is retained
                    // invisible; one recomposition on reopen restores it before the first frame.
                    isActiveLine = state.activeLineIndices.contains(index) && active,
                    isPlaying = isPlaying,
                    syncOffsetMs = syncOffsetMs,
                    bgVisible = bgVisible,
                    positionProvider = positionProvider,
                    nextLineTimeMs = presentation.nextLineTimes.getOrNull(index),
                    look = presentation.looks[index],
                    isAutoScrollEnabled = state.isAutoScrollEnabled,
                    displayedCurrentLineIndex = state.deferredCurrentLineIndex,
                    showTranslation = presentation.showTranslation,
                    romanization = presentation.romanizations.getOrNull(index),
                    romanizationOnly = presentation.romanizationOnly,
                    motionEnabled = motionEnabled,
                    blurOtherLines = blurOtherLines,
                    onSizeChanged = onHeight,
                    onClick = { onLineClick(index, item) },
                )
            }
        }
    }
}
