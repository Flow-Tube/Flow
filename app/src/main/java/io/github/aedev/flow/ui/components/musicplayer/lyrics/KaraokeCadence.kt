package io.github.aedev.flow.ui.components.musicplayer.lyrics

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText

/** How high the letters of a Cadence line rise at their crest, as a share of the font size. */
internal const val CADENCE_PEAK_EM = 0.11f

/** The colours and fronts a Cadence line paints with, made once per colour change. */
internal class CadenceInk(
    val sung: Color,
    val unsung: Color,
    val forward: FrontBrush,
    val backward: FrontBrush,
    val fadeForward: FrontBrush,
    val fadeBackward: FrontBrush,
    val glows: Array<Shadow>,
) {
    val layered = needsUnsungLayer(unsung)
    val solidUnsung = unsung.copy(alpha = 1f)
}

/** Where a Cadence frame is: the playheads it reads and how far the line's own motion has come. */
internal class CadenceFrame {
    var position = Long.MIN_VALUE
    var liftClock = Long.MIN_VALUE
    var exit = -1f
    var lineLift = 0f
    var peakPx = 0f
    var featherPx = 0f
    var anchor = 0f
    var overscan = 0f
    var glow = false
}

/**
 * One frame of a Cadence line: the fill front as Classic draws it, letters that rise as the front
 * reaches them, held notes and hyphen runs that grow from their baseline while the rest of the row
 * moves over, and, once the next line starts ([CadenceFrame.exit] at or above 0), a fill that
 * completes and a sung colour that fades out. Everything is read from [geometry] and [frame].
 */
internal fun DrawScope.drawCadenceLine(
    geometry: KaraokeGeometry,
    ink: CadenceInk,
    frame: CadenceFrame,
) {
    reflowRows(geometry, frame)
    val exiting = frame.exit >= 0f
    if (exiting) {
        if (ink.layered) {
            withAlphaLayer(ink.unsung.alpha, frame.overscan) {
                for (index in geometry.cells.indices) drawCell(geometry, index, frame, ink.solidUnsung, null)
            }
        } else {
            for (index in geometry.cells.indices) drawCell(geometry, index, frame, ink.unsung, null)
        }
        val litAlpha = exitLitAlpha(frame.exit)
        if (litAlpha <= 0f) return
        withAlphaLayer(litAlpha, frame.overscan) {
            for (index in geometry.cells.indices) {
                val cell = geometry.cells[index]
                val cellProgress = cellFillProgress(cell, fillProgress(geometry, index, frame))
                when {
                    cellProgress >= 1f -> {
                        drawCell(geometry, index, frame, ink.sung, null)
                    }

                    cellProgress > 0f -> {
                        drawCell(
                            geometry,
                            index,
                            frame,
                            ink.sung,
                            front(cell, cellProgress, ink.fadeForward, ink.fadeBackward),
                        )
                    }
                }
            }
        }
        return
    }
    if (ink.layered) {
        withAlphaLayer(ink.unsung.alpha, frame.overscan) {
            for (index in geometry.cells.indices) {
                if (cellFillProgress(geometry.cells[index], fillProgress(geometry, index, frame)) < 1f) {
                    drawCell(geometry, index, frame, ink.solidUnsung, null)
                }
            }
        }
    }
    for (index in geometry.cells.indices) {
        val cell = geometry.cells[index]
        val progress = fillProgress(geometry, index, frame)
        val cellProgress = cellFillProgress(cell, progress)
        if (frame.glow) drawGlow(geometry, index, frame, ink.glows, progress)
        when {
            cellProgress >= 1f -> drawCell(geometry, index, frame, ink.sung, null)
            cellProgress <= 0f -> if (!ink.layered) drawCell(geometry, index, frame, ink.unsung, null)
            else -> drawCell(geometry, index, frame, ink.sung, front(cell, cellProgress, ink.forward, ink.backward))
        }
    }
}

private fun fillProgress(
    geometry: KaraokeGeometry,
    index: Int,
    frame: CadenceFrame,
): Float {
    if (frame.position == Long.MIN_VALUE) return 0f
    val cell = geometry.cells[index]
    val progress = wordFillProgress(geometry, cell, geometry.words.getOrNull(cell.word), frame.position, isCurrent = true)
    return if (frame.exit >= 0f) maxOf(progress, exitFill(frame.exit)) else progress
}

private fun front(
    cell: KaraokeCell,
    cellProgress: Float,
    forward: FrontBrush,
    backward: FrontBrush,
): FrontBrush {
    val brush = if (cell.rtl) backward else forward
    brush.moveTo(
        if (cell.rtl) {
            cell.inkRight - (cell.inkRight - cell.inkLeft) * cellProgress
        } else {
            cell.inkLeft + (cell.inkRight - cell.inkLeft) * cellProgress
        },
    )
    return brush
}

/**
 * Sizes every word for this frame and moves each row's cells so grown words push their neighbours
 * aside instead of overlapping them, keeping the row where its alignment holds it.
 */
private fun reflowRows(
    geometry: KaraokeGeometry,
    frame: CadenceFrame,
) {
    val settle = if (frame.exit >= 0f) 1f - frame.exit else 1f
    for (index in geometry.words.indices) {
        val word = geometry.words[index]
        val grown =
            if (frame.position == Long.MIN_VALUE) {
                1f
            } else {
                heldNoteSwell(frame.position, word.startMs, word.endMs) * crescendoScale(frame.position, word)
            }
        geometry.wordScale[index] = 1f + (grown - 1f) * settle
    }
    for (row in geometry.rowOrder.indices) {
        val order = geometry.rowOrder[row]
        var pushed = 0f
        order.forEach { index ->
            val cell = geometry.cells[index]
            val scale = geometry.wordScale.getOrElse(cell.word) { 1f }
            val extra = (cell.inkRight - cell.inkLeft) * (scale - 1f)
            geometry.cellScale[index] = scale
            geometry.cellDx[index] = pushed + extra / 2f
            pushed += extra
        }
        val shift = -pushed * frame.anchor
        order.forEach { geometry.cellDx[it] += shift }
    }
}

/** How high a span of a word stands, from its share [from] to [to] of the word, in pixels. */
private fun liftPx(
    word: KaraokeWord?,
    from: Float,
    to: Float,
    frame: CadenceFrame,
): Float {
    if (word == null || frame.liftClock == Long.MIN_VALUE || frame.lineLift == 0f) return 0f
    val feather = frame.featherPx / 2f / word.inkWidth
    return rippleLift(frame.liftClock, word.startMs, word.endMs, from, to, feather) * frame.peakPx * frame.lineLift
}

private fun DrawScope.drawCell(
    geometry: KaraokeGeometry,
    index: Int,
    frame: CadenceFrame,
    color: Color,
    brush: FrontBrush?,
) {
    val cell = geometry.cells[index]
    val row = geometry.rows[cell.row]
    val word = geometry.words.getOrNull(cell.word)
    val grow = geometry.cellScale[index]
    val start = cell.startFraction
    val end = cell.startFraction + cell.widthFraction
    val letters = cell.letters
    val perLetter =
        letters != null &&
            word != null &&
            frame.liftClock != Long.MIN_VALUE &&
            frame.liftClock >= rippleTouchMs(word.startMs, word.endMs, start, frame.featherPx / 2f / word.inkWidth) &&
            frame.liftClock < rippleSettledMs(word.startMs, word.endMs, end, frame.featherPx / 2f / word.inkWidth)
    withTransform({
        translate(geometry.cellDx[index], row.dy)
        if (grow != 1f) scale(grow, grow, Offset((cell.inkLeft + cell.inkRight) / 2f, row.layout.firstBaseline))
    }) {
        if (perLetter) {
            val front = brush?.x ?: 0f
            letters!!.forEach { letter ->
                val lift = liftPx(word, letter.from, letter.to, frame)
                translate(left = letter.dx, top = letter.dy - lift) {
                    brush?.moveTo(front - letter.dx)
                    paint(letter.layout, color, brush)
                }
            }
        } else {
            val lift = liftPx(word, start, end, frame)
            clipRect(left = cell.clipLeft, top = -frame.overscan, right = cell.clipRight, bottom = size.height + frame.overscan) {
                translate(top = -lift) { paint(row.layout, color, brush) }
            }
        }
    }
}

private fun DrawScope.paint(
    layout: TextLayoutResult,
    color: Color,
    brush: FrontBrush?,
) {
    if (brush == null) {
        drawText(layout, color = color)
    } else {
        // The layout's paint keeps the alpha of the last colour drawn with it.
        drawText(layout, brush = brush, alpha = 1f)
    }
}

private fun DrawScope.drawGlow(
    geometry: KaraokeGeometry,
    index: Int,
    frame: CadenceFrame,
    glows: Array<Shadow>,
    progress: Float,
) {
    val cell = geometry.cells[index]
    val glowLayout = cell.glow ?: return
    val level = heldNoteGlowLevel(progress)
    if (level < 0) return
    val row = geometry.rows[cell.row]
    val lift = liftPx(geometry.words.getOrNull(cell.word), cell.startFraction, cell.startFraction + cell.widthFraction, frame)
    val grow = geometry.cellScale[index]
    withTransform({
        translate(geometry.cellDx[index], row.dy)
        if (grow != 1f) scale(grow, grow, Offset((cell.inkLeft + cell.inkRight) / 2f, row.layout.firstBaseline))
    }) {
        translate(
            left = cell.inkLeft - glowLayout.getBoundingBox(0).left,
            top = row.layout.firstBaseline - glowLayout.firstBaseline - lift,
        ) {
            // The word alone can shape differently from its row, so only its blur is drawn.
            drawText(glowLayout, color = Color.Transparent, shadow = glows[level])
        }
    }
}
