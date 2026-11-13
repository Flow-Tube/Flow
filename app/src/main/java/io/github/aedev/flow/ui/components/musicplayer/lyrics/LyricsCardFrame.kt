package io.github.aedev.flow.ui.components.musicplayer.lyrics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

private val BackdropMargin = 24.dp

/**
 * How the card's edges come out in the image. Most chat apps re-save a shared picture as a JPEG,
 * which has no transparency, so rounded corners on a transparent ground turn white there.
 */
internal enum class LyricsCardFrame {
    SQUARE,
    BACKDROP,
    TRANSPARENT,
}

/** The first of [candidates] that doesn't read as the card's own [ground], so the margin shows as a frame. */
internal fun lyricsBackdropColor(
    ground: Color,
    candidates: List<Color>,
): Color = candidates.firstOrNull { !looksAlike(it, ground) } ?: candidates.first()

/** Draws [card] with the corners [frame] asks for; [modifier] sizes and records the whole image. */
@Composable
internal fun LyricsFramedCard(
    frame: LyricsCardFrame,
    backdrop: Color,
    modifier: Modifier = Modifier,
    card: @Composable (Shape) -> Unit,
) {
    when (frame) {
        LyricsCardFrame.SQUARE -> Box(modifier) { card(RectangleShape) }
        LyricsCardFrame.BACKDROP -> Box(modifier.background(backdrop).padding(BackdropMargin)) { card(MaterialTheme.shapes.extraLarge) }
        LyricsCardFrame.TRANSPARENT -> Box(modifier) { card(MaterialTheme.shapes.extraLarge) }
    }
}
