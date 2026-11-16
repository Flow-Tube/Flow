package io.github.aedev.flow.ui.components.musicplayer.lyrics

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val LyricsCardWidth = 360.dp
internal const val LYRICS_CARD_EXPORT_WIDTH_PX = 1080

/**
 * The density the card is laid out at, so it is [LYRICS_CARD_EXPORT_WIDTH_PX] wide on every phone.
 * The font scale stays 1: the image is made for other people, so the sender's text size setting
 * doesn't change it. The preview shows it scaled down; the image keeps this size.
 */
internal val LyricsCardExportDensity = Density(LYRICS_CARD_EXPORT_WIDTH_PX / LyricsCardWidth.value, fontScale = 1f)

/** The image's shape: as tall as the card's content, square, or a 9:16 story. */
internal enum class LyricsCardAspect(
    private val heightPerWidth: Float?,
) {
    FIT(null),
    SQUARE(1f),
    STORY(16f / 9f),
    ;

    /** The image height for [width], or null when the content sets it. */
    fun height(width: Dp): Dp? = heightPerWidth?.let { width * it }
}
