package io.github.aedev.flow.ui.components.musicplayer.full

import io.github.aedev.flow.data.local.MusicPlayerBackgroundStyle

/** How dark the immersive cover goes behind lyrics shown in place, as the scrim's alpha. */
internal const val IN_PLACE_LYRICS_DIM = 0.6f

/** The lyrics button shows lyrics in the player, over the cover, only in the phone column with the immersive background. */
internal fun lyricsShowInPlace(
    background: MusicPlayerBackgroundStyle,
    layout: MusicPlayerLayout,
): Boolean = background == MusicPlayerBackgroundStyle.IMMERSIVE && layout == MusicPlayerLayout.COMPACT

/** Lyrics in place sync and scroll only while someone can see them: not collapsed, not under the sheet or the queue. */
internal fun inPlaceLyricsActive(
    expanded: Boolean,
    inPlace: Boolean,
    sheetOpen: Boolean,
    queueCovers: Boolean,
): Boolean = expanded && inPlace && !sheetOpen && !queueCovers
