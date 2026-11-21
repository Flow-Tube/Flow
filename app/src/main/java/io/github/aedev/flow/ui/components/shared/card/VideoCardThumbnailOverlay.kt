package io.github.aedev.flow.ui.components.shared.card

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Something a feed draws over a card's thumbnail, above its badges, given the card's video id; most
 * calls draw nothing. Unset everywhere except where a feed provides it around its cards.
 */
val LocalVideoCardThumbnailOverlay = staticCompositionLocalOf<(@Composable BoxScope.(videoId: String) -> Unit)?> { null }
