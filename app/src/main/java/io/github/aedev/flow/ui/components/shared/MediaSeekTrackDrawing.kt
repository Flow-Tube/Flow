package io.github.aedev.flow.ui.components.shared

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.lerp
import io.github.aedev.flow.data.model.SponsorBlockSegment
import io.github.aedev.flow.ui.theme.SPONSOR_BLOCK_SEGMENT_ALPHA
import io.github.aedev.flow.ui.theme.defaultSponsorBlockColor
import org.schabi.newpipe.extractor.stream.StreamSegment

private val SponsorPointWidth = 3.dp
private val CommentMarkDiameter = 3.dp

internal val RestTrackHeight = 5.dp
internal val ActiveTrackHeight = 10.dp

/**
 * Paints the track: base, buffered, progress, chapter gaps, SponsorBlock segments and comment marks.
 *
 * Shared by both shapes so their appearance cannot drift apart — the earlier version drew the same
 * thing twice, once in a sibling Canvas and once for the locked variant.
 */
internal fun DrawScope.drawSeekTrack(
    fraction: Float,
    expansion: Float,
    chapters: List<StreamSegment>,
    sponsorSegments: List<SponsorBlockSegment>,
    sponsorColors: Map<String, Color>,
    commentMarks: List<Float>,
    duration: Long,
    bufferedValue: Float,
    colors: SeekTrackColors,
    bottomAligned: Boolean,
) {
    val trackHeightPx = lerp(RestTrackHeight.toPx(), ActiveTrackHeight.toPx(), expansion)
    val width = size.width
    val trackTop =
        if (bottomAligned) {
            size.height - trackHeightPx
        } else {
            (size.height - trackHeightPx) / 2f
        }
    val trackBottom = trackTop + trackHeightPx
    val capRadius = CornerRadius(trackHeightPx / 2f)

    val gapWidth = lerp(2.dp.toPx(), 3.dp.toPx(), expansion)
    val boundaries =
        if (chapters.isNotEmpty() && duration > 0) {
            chapters
                .asSequence()
                .map { it.startTimeSeconds }
                .filter { it > 0 }
                .map { (it * 1000f) / duration.toFloat() }
                .filter { it in 0f..1f }
                .map { it * width }
                .sorted()
                .toList()
        } else {
            emptyList()
        }

    val segments =
        buildList {
            var segStart = 0f
            for (boundary in boundaries) {
                val segEnd = boundary - gapWidth / 2f
                if (segEnd > segStart) {
                    add(segStart to segEnd)
                }
                segStart = (boundary + gapWidth / 2f).coerceAtMost(width)
            }
            if (width > segStart) {
                add(segStart to width)
            }
        }

    val trackPath =
        Path().apply {
            segments.forEachIndexed { index, (segStart, segEnd) ->
                val startRadius = if (index == 0) capRadius else CornerRadius.Zero
                val endRadius = if (index == segments.lastIndex) capRadius else CornerRadius.Zero
                addRoundRect(
                    RoundRect(
                        rect = Rect(segStart, trackTop, segEnd, trackBottom),
                        topLeft = startRadius,
                        topRight = endRadius,
                        bottomRight = endRadius,
                        bottomLeft = startRadius,
                    ),
                )
            }
        }

    clipPath(trackPath) {
        drawRect(
            color = colors.track,
            topLeft = Offset(0f, trackTop),
            size = Size(width, trackHeightPx),
        )

        if (bufferedValue > 0f) {
            drawRect(
                color = colors.buffered,
                topLeft = Offset(0f, trackTop),
                size = Size(width * bufferedValue.coerceIn(0f, 1f), trackHeightPx),
            )
        }

        drawRect(
            color = colors.active,
            topLeft = Offset(0f, trackTop),
            size = Size(width * fraction, trackHeightPx),
        )

        // Drawn above progress so a segment stays visible after playback passes it.
        if (duration > 0) {
            sponsorSegments.forEach { segment ->
                val startRatio = (segment.startTime * 1000f / duration.toFloat()).coerceIn(0f, 1f)
                val endRatio = (segment.endTime * 1000f / duration.toFloat()).coerceIn(0f, 1f)

                val startX: Float
                val segWidth: Float
                if (segment.isPoint) {
                    segWidth = minOf(SponsorPointWidth.toPx(), width)
                    startX = (startRatio * width - segWidth / 2f).coerceIn(0f, width - segWidth)
                } else {
                    startX = startRatio * width
                    segWidth = (endRatio * width) - startX
                }
                if (segWidth > 0f) {
                    val segmentColor =
                        (
                            sponsorColors[segment.category]
                                ?: defaultSponsorBlockColor(segment.category)
                        ).copy(alpha = SPONSOR_BLOCK_SEGMENT_ALPHA)

                    drawRect(
                        color = segmentColor,
                        topLeft = Offset(startX, trackTop),
                        size = Size(segWidth, trackHeightPx),
                    )
                }
            }

            val currentTimeSeconds = fraction.coerceIn(0f, 1f) * duration / 1000f
            val isInsideSponsorSegment =
                sponsorSegments.any { segment ->
                    currentTimeSeconds >= segment.startTime && currentTimeSeconds < segment.endTime
                }
            if (isInsideSponsorSegment) {
                val playheadX = width * fraction.coerceIn(0f, 1f)
                val outerWidth = minOf(4.dp.toPx(), width)
                val innerWidth = minOf(2.dp.toPx(), width)
                drawRect(
                    color = colors.playhead,
                    topLeft =
                        Offset(
                            x = (playheadX - outerWidth / 2f).coerceIn(0f, width - outerWidth),
                            y = trackTop,
                        ),
                    size = Size(outerWidth, trackHeightPx),
                )
                drawRect(
                    color = colors.active,
                    topLeft =
                        Offset(
                            x = (playheadX - innerWidth / 2f).coerceIn(0f, width - innerWidth),
                            y = trackTop,
                        ),
                    size = Size(innerWidth, trackHeightPx),
                )
            }
        }
    }

    // Outside the clip: a comment often points at a chapter start, and the gap there would cut the dot.
    drawCommentMarks(commentMarks, centerY = trackTop + trackHeightPx / 2f, color = colors.mark)
}

/**
 * One dot per mark, the same size whatever the track is doing.
 *
 * The marks arrive merged by share of the bar, which still leaves room for two dots to overlap on a
 * narrow phone bar, so one that would touch the dot before it is left out here where the width is known.
 */
private fun DrawScope.drawCommentMarks(
    marks: List<Float>,
    centerY: Float,
    color: Color,
) {
    val radius = CommentMarkDiameter.toPx() / 2f
    val maxX = (size.width - radius).coerceAtLeast(radius)
    var lastX = Float.NEGATIVE_INFINITY
    marks.fastForEach { mark ->
        val x = (mark * size.width).coerceIn(radius, maxX)
        if (x - lastX > radius * 2f) {
            drawCircle(color = color, radius = radius, center = Offset(x, centerY))
            lastX = x
        }
    }
}
