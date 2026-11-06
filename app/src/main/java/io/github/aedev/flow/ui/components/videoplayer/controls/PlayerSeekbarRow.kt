package io.github.aedev.flow.ui.components.videoplayer.controls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.aedev.flow.data.local.ScrubPreviewStyle
import io.github.aedev.flow.data.model.SponsorBlockSegment
import io.github.aedev.flow.innertube.models.response.VideoHeatmap
import io.github.aedev.flow.player.stream.StoryboardLevel
import io.github.aedev.flow.player.stream.StoryboardSpec
import io.github.aedev.flow.ui.components.shared.MediaSeekBar
import io.github.aedev.flow.ui.theme.PlayerLiveIndicator
import org.schabi.newpipe.extractor.stream.StreamSegment

private val SeekPreviewGap = 12.dp
private val SeekInfoGap = 8.dp

/**
 * Everything a seek bar paints besides the playhead itself. Grouped because all three seek bars in
 * the player — expanded, always-visible and locked — need exactly this set and nothing else.
 */
@Immutable
data class PlayerSeekbarContent(
    val chapters: List<StreamSegment> = emptyList(),
    val sponsorSegments: List<SponsorBlockSegment> = emptyList(),
    val sponsorColors: Map<String, Color> = emptyMap(),
    val commentMarks: List<Float> = emptyList(),
    val bufferedPercentage: Float = 0f,
    val storyboard: List<StoryboardLevel> = emptyList(),
    val heatmap: VideoHeatmap? = null,
    val previewStyle: ScrubPreviewStyle = ScrubPreviewStyle.STRIP,
)

/**
 * The seek bar, plus the substitute shown when there is nothing to seek along.
 *
 * A live stream of unknown duration gets a plain progress-less bar instead. Both the expanded
 * controls and the thin always-visible strip need that same either/or, which is why it lives here
 * rather than being spelled out at each of them.
 */
@Composable
internal fun PlayerSeekbarRow(
    positionProvider: () -> Long,
    duration: Long,
    isLive: Boolean,
    content: PlayerSeekbarContent,
    edgeAligned: Boolean,
    horizontalPadding: Dp,
    onScrubProgress: (progress: Float, duration: Long) -> Unit,
    onScrubFinished: () -> Unit,
    modifier: Modifier = Modifier,
    seekbarZIndex: Float = 0f,
    isScrubbing: Boolean = false,
    interactive: Boolean = true,
) {
    if (isLive && duration <= 0L) {
        Box(
            modifier =
                modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(PlayerLiveIndicator),
        )
        return
    }

    // Derived rather than read: a live timeline's duration is recomputed from the playhead, and a
    // plain read here would recompose this row on every tick for a value that almost never moves.
    val seekDuration by remember(duration, isLive, positionProvider) {
        derivedStateOf { if (isLive) duration.coerceAtLeast(positionProvider()) else duration }
    }
    var trackWidthPx by remember { mutableIntStateOf(0) }

    // Above the pills row (zIndex 1f), so the scrub read-out overlays the time and chapter pills
    // rather than sliding behind them.
    Box(modifier = modifier.fillMaxWidth().zIndex(seekbarZIndex)) {
        AnimatedVisibility(
            visible = isScrubbing && seekDuration > 0L,
            enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
            // Reports no size of its own so the bar cannot be shoved down when a scrub starts, and
            // stacks upward from the bar's top edge.
            modifier =
                Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(0, 0) { placeable.place(x = 0, y = -placeable.height) }
                },
        ) {
            ScrubOverlay(
                positionProvider = positionProvider,
                duration = seekDuration,
                content = content,
                horizontalPadding = horizontalPadding,
                trackWidthPx = trackWidthPx,
            )
        }
        SeekBar(
            positionProvider = positionProvider,
            seekDuration = seekDuration,
            content = content,
            edgeAligned = edgeAligned,
            horizontalPadding = horizontalPadding,
            onScrubProgress = onScrubProgress,
            onScrubFinished = onScrubFinished,
            onTrackWidth = { trackWidthPx = it },
            interactive = interactive,
        )
    }
}

/**
 * Everything a scrub puts above the bar, stacked in one column: the read-out, the frame or strip,
 * and the rewatch curve resting on the bar.
 *
 * One column rather than three separately offset children so the whole thing fades as a unit and
 * each part sits clear of the one below it whatever the others are doing.
 */
@Composable
private fun ScrubOverlay(
    positionProvider: () -> Long,
    duration: Long,
    content: PlayerSeekbarContent,
    horizontalPadding: Dp,
    trackWidthPx: Int,
) {
    val trackPx =
        (trackWidthPx - 2 * with(LocalDensity.current) { horizontalPadding.roundToPx() }).coerceAtLeast(0)

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding),
    ) {
        SeekScrubInfoRow(
            positionProvider = positionProvider,
            durationMs = duration,
            content = content,
        )
        if (content.storyboard.isNotEmpty() && trackPx > 0) {
            Spacer(modifier = Modifier.height(SeekInfoGap))
            when (content.previewStyle) {
                ScrubPreviewStyle.STRIP -> {
                    SeekStrip(
                        levels = content.storyboard,
                        positionProvider = positionProvider,
                        duration = duration,
                        trackWidthPx = trackPx,
                    )
                }

                ScrubPreviewStyle.FRAME -> {
                    SeekFrame(
                        levels = content.storyboard,
                        positionProvider = positionProvider,
                        duration = duration,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(SeekPreviewGap))
        content.heatmap?.takeIf { !it.isEmpty }?.let { heatmap ->
            SeekHeatmapGraph(heatmap = heatmap, durationMs = duration)
        }
    }
}

/** The single frame under the thumb. */
@Composable
private fun SeekFrame(
    levels: List<StoryboardLevel>,
    positionProvider: () -> Long,
    duration: Long,
) {
    val widthPx = with(LocalDensity.current) { SeekPreviewWidth.roundToPx() }
    val level = remember(levels, widthPx) { StoryboardSpec.levelFor(levels, widthPx) } ?: return

    SeekPreviewThumbnail(
        level = level,
        positionProvider = positionProvider,
        modifier = Modifier.anchoredToThumb(positionProvider, duration),
    )
}

/** The same preview widened into a filmstrip around the thumb. */
@Composable
private fun SeekStrip(
    levels: List<StoryboardLevel>,
    positionProvider: () -> Long,
    duration: Long,
    trackWidthPx: Int,
) {
    val nominalWidthPx = with(LocalDensity.current) { SeekStripNominalFrameWidth.roundToPx() }
    val level = remember(levels, nominalWidthPx) { StoryboardSpec.levelFor(levels, nominalWidthPx) } ?: return

    SeekPreviewStrip(
        level = level,
        positionProvider = positionProvider,
        durationMs = duration,
        stripWidthPx = trackWidthPx,
    )
}

@Composable
private fun SeekBar(
    positionProvider: () -> Long,
    seekDuration: Long,
    content: PlayerSeekbarContent,
    edgeAligned: Boolean,
    horizontalPadding: Dp,
    onScrubProgress: (progress: Float, duration: Long) -> Unit,
    onScrubFinished: () -> Unit,
    onTrackWidth: (Int) -> Unit,
    interactive: Boolean,
) {
    MediaSeekBar(
        value = {
            if (seekDuration > 0) {
                (positionProvider().toFloat() / seekDuration.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
        },
        onValueChange = { progress -> onScrubProgress(progress, seekDuration) },
        onValueChangeFinished = onScrubFinished,
        enabled = interactive,
        chapters = content.chapters,
        sponsorSegments = content.sponsorSegments,
        sponsorColors = content.sponsorColors,
        commentMarks = content.commentMarks,
        duration = seekDuration,
        bufferedValue = content.bufferedPercentage,
        edgeAligned = edgeAligned,
        modifier =
            Modifier
                .fillMaxWidth()
                .onSizeChanged { onTrackWidth(it.width) }
                .padding(horizontal = horizontalPadding),
    )
}
