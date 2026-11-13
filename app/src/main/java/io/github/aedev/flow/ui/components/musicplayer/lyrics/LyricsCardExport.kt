package io.github.aedev.flow.ui.components.musicplayer.lyrics

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

internal val LyricsCardWidth = 360.dp
internal const val LYRICS_CARD_EXPORT_WIDTH_PX = 1080

/**
 * The density the card is laid out at, so it is [LYRICS_CARD_EXPORT_WIDTH_PX] wide on every phone.
 * The font scale stays 1: the image is made for other people, so the sender's text size setting
 * doesn't change it. The preview shows it scaled down; the image keeps this size.
 */
internal val LyricsCardExportDensity = Density(LYRICS_CARD_EXPORT_WIDTH_PX / LyricsCardWidth.value, fontScale = 1f)
