package io.github.aedev.flow.ui.components.videoplayer.sheet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.transcript.TranscriptCue
import io.github.aedev.flow.ui.components.shared.MediaArtworkTint
import io.github.aedev.flow.utils.formatDurationMillis

internal val TranscriptHorizontalPadding = 16.dp
private val RowVerticalPadding = 12.dp
private val HeadingTopPadding = 20.dp
private val HeadingBottomPadding = 4.dp
private val TimestampShape = RoundedCornerShape(50)
private val TimestampHorizontalPadding = 8.dp
private val TimestampVerticalPadding = 3.dp

/** Space between a timestamp and the line it stamps, held open by the timestamp's own slot. */
private val TimestampGap = 10.dp

/** Room under the last line for the sync pill that floats over the bottom of the list. */
private val ListBottomPadding = 64.dp
private val SyncPillPadding = 12.dp

/** Id of the inline slot each line opens for its timestamp. */
private const val TIMESTAMP_SLOT = "timestamp"

/**
 * The lines themselves, with the sync pill floating over their bottom edge.
 *
 * A [SelectionContainer] wraps the list so a transcript can be read out of the app: long-pressing
 * a line opens the platform's own selection handles and copy action, and a tap still seeks.
 */
@Composable
internal fun TranscriptList(
    items: List<TranscriptItem>,
    listState: LazyListState,
    activeStartMs: Long?,
    showSyncPill: Boolean,
    tint: MediaArtworkTint,
    onSeekMs: (Long) -> Unit,
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timestampStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium)
    val slot = rememberTimestampSlot(items = items, style = timestampStyle)

    Box(modifier = modifier) {
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = ListBottomPadding),
            ) {
                items(items = items, key = { it.key }) { item ->
                    when (item) {
                        is TranscriptItem.Heading -> {
                            TranscriptHeading(title = item.chapter.title)
                        }

                        is TranscriptItem.Line -> {
                            TranscriptLine(
                                cue = item.cue,
                                isActive = item.cue.startMs == activeStartMs,
                                tint = tint,
                                slot = slot,
                                timestampStyle = timestampStyle,
                                onClick = { onSeekMs(item.cue.startMs) },
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showSyncPill,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = SyncPillPadding),
        ) {
            SyncToVideoPill(tint = tint, onClick = onSync)
        }
    }
}

/** How much room a line opens for its timestamp, and how tall that slot is. */
private data class TimestampSlot(
    val width: TextUnit,
    val height: TextUnit,
)

/**
 * Sizes the inline slot from the longest timestamp in the transcript — the last line's, since they
 * only grow — so the whole list is measured once and every line starts at the same place.
 */
@Composable
private fun rememberTimestampSlot(
    items: List<TranscriptItem>,
    style: TextStyle,
): TimestampSlot {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(items, style, density) {
        val longest =
            items
                .filterIsInstance<TranscriptItem.Line>()
                .lastOrNull()
                ?.cue
                ?.startMs
                ?: 0L
        val measured = measurer.measure(formatDurationMillis(longest), style).size
        with(density) {
            TimestampSlot(
                width = (measured.width + (TimestampHorizontalPadding * 2 + TimestampGap).toPx()).toSp(),
                height = (measured.height + TimestampVerticalPadding.toPx() * 2).toSp(),
            )
        }
    }
}

/**
 * One spoken line, stamped with the time it was said.
 *
 * The timestamp takes an inline slot rather than a column of its own, so a line that wraps uses the
 * full width of the sheet instead of leaving a stripe of empty space down the left.
 */
@Composable
private fun TranscriptLine(
    cue: TranscriptCue,
    isActive: Boolean,
    tint: MediaArtworkTint,
    slot: TimestampSlot,
    timestampStyle: TextStyle,
    onClick: () -> Unit,
) {
    val label = remember(cue.startMs) { formatDurationMillis(cue.startMs) }
    val text =
        remember(label, cue.text) {
            buildAnnotatedString {
                // The stamp doubles as the slot's alternate text, so a line copied out of the
                // transcript — or read aloud — still says when it was said.
                appendInlineContent(TIMESTAMP_SLOT, "$label ")
                append(cue.text)
            }
        }
    val inlineContent =
        remember(slot, isActive, tint, timestampStyle) {
            mapOf(
                TIMESTAMP_SLOT to
                    InlineTextContent(
                        Placeholder(
                            width = slot.width,
                            height = slot.height,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                        ),
                    ) { stamp ->
                        TranscriptTimestamp(
                            label = stamp.trim(),
                            isActive = isActive,
                            tint = tint,
                            style = timestampStyle,
                        )
                    },
            )
        }

    Text(
        text = text,
        inlineContent = inlineContent,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
        color = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = TranscriptHorizontalPadding, vertical = RowVerticalPadding),
    )
}

/**
 * The timestamp badge inside a line's inline slot. The slot is as wide as the longest stamp plus
 * the gap, so the badge is aligned to its start and keeps its own width.
 */
@Composable
private fun TranscriptTimestamp(
    label: String,
    isActive: Boolean,
    tint: MediaArtworkTint,
    style: TextStyle,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier =
                Modifier
                    .clip(TimestampShape)
                    .background(if (isActive) tint.accent else tint.container)
                    .padding(
                        horizontal = TimestampHorizontalPadding,
                        vertical = TimestampVerticalPadding,
                    ),
        ) {
            Text(
                text = label,
                style = style,
                color = if (isActive) tint.container else tint.onContainer,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TranscriptHeading(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start = TranscriptHorizontalPadding,
                    end = TranscriptHorizontalPadding,
                    top = HeadingTopPadding,
                    bottom = HeadingBottomPadding,
                ),
    )
}

@Composable
private fun SyncToVideoPill(
    tint: MediaArtworkTint,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        colors =
            ButtonDefaults.filledTonalButtonColors(
                containerColor = tint.accent,
                contentColor = tint.container,
            ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Sync,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.transcript_resync),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
