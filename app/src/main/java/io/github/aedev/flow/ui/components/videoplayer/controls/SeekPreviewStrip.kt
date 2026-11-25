package io.github.aedev.flow.ui.components.videoplayer.controls

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.player.stream.StoryboardLevel
import io.github.aedev.flow.player.stream.StoryboardTile
import io.github.aedev.flow.ui.theme.PlayerScrim
import io.github.aedev.flow.ui.theme.PlayerScrimContent

/** Height of the strip, and so of every frame in it. */
internal val SeekStripHeight: Dp = 64.dp

/** The width a storyboard level is picked against, before that level's own aspect sets the real one. */
internal val SeekStripNominalFrameWidth: Dp = 112.dp

private val SeekStripFrameGap = 2.dp
private val SeekStripPlayheadWidth = 2.dp

/**
 * The scrub preview as a filmstrip: the frame being seeked to, under the thumb, with its neighbours
 * either side.
 *
 * The strip is a magnified timeline rather than the whole video — one frame per storyboard interval
 * — so it slides under the thumb far faster than the thumb crosses the bar. Only the sprite sheets
 * are resolved in composition, and a window of frames sits on one sheet for tens of seconds; every
 * crop is worked out in the draw pass, so a drag redraws without recomposing.
 */
@Composable
internal fun SeekPreviewStrip(
    level: StoryboardLevel,
    positionProvider: () -> Long,
    durationMs: Long,
    stripWidthPx: Int,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val frameWidthPx =
        with(density) { SeekStripHeight.toPx() } * level.thumbnailWidth / level.thumbnailHeight
    val gapPx = with(density) { SeekStripFrameGap.toPx() }
    val playheadPx = with(density) { SeekStripPlayheadWidth.toPx() }

    val sheetUrls by remember(level, stripWidthPx, frameWidthPx, durationMs, positionProvider) {
        derivedStateOf {
            visibleFrames(level, positionProvider(), durationMs, stripWidthPx.toFloat(), frameWidthPx)
                .mapNotNull { level.tileAt(it.positionMs)?.sheetUrl }
                .distinct()
        }
    }
    val sheets = rememberStoryboardSheets(sheetUrls)

    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .height(SeekStripHeight)
                .clip(RoundedCornerShape(SeekPreviewCorner))
                .background(PlayerScrim),
    ) {
        val position = positionProvider()
        val thumbX = scrubThumbFraction(position, durationMs) * size.width
        seekStripFrames(
            positionMs = position,
            intervalMs = level.intervalMs,
            frameCount = stripFrameCount(level.frameCount, level.isLive, level.intervalMs, durationMs),
            thumbXPx = thumbX,
            frameWidthPx = frameWidthPx,
            stripWidthPx = size.width,
        ).forEach { frame ->
            val tile = level.tileAt(frame.positionMs) ?: return@forEach
            val painter = sheets.painterFor(tile.sheetUrl) ?: return@forEach
            clipRect(
                left = frame.leftPx,
                top = 0f,
                right = (frame.leftPx + frameWidthPx - gapPx).coerceAtLeast(frame.leftPx),
                bottom = size.height,
            ) {
                drawFrame(painter, tile, frame.leftPx)
            }
        }
        drawRect(
            color = PlayerScrimContent,
            topLeft = Offset(thumbX - playheadPx / 2f, 0f),
            size = Size(playheadPx, size.height),
        )
    }
}

private fun DrawScope.drawFrame(
    painter: Painter,
    tile: StoryboardTile,
    leftPx: Float,
) {
    val scale = size.height / tile.height.toFloat()
    translate(left = leftPx, top = 0f) {
        scale(scaleX = scale, scaleY = scale, pivot = Offset.Zero) {
            translate(left = -tile.left.toFloat(), top = -tile.top.toFloat()) {
                with(painter) { draw(size = Size(tile.sheetWidth.toFloat(), tile.sheetHeight.toFloat())) }
            }
        }
    }
}

private fun visibleFrames(
    level: StoryboardLevel,
    positionMs: Long,
    durationMs: Long,
    stripWidthPx: Float,
    frameWidthPx: Float,
): List<SeekStripFrame> =
    seekStripFrames(
        positionMs = positionMs,
        intervalMs = level.intervalMs,
        frameCount = stripFrameCount(level.frameCount, level.isLive, level.intervalMs, durationMs),
        thumbXPx = scrubThumbFraction(positionMs, durationMs) * stripWidthPx,
        frameWidthPx = frameWidthPx,
        stripWidthPx = stripWidthPx,
    )
