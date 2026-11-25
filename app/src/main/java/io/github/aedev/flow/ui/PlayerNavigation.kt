package io.github.aedev.flow.ui

import androidx.navigation.NavHostController
import io.github.aedev.flow.player.stream.PlaybackPrefetcher

/**
 * Opens the player for [videoId], warming stream extraction before navigating.
 *
 * The extraction started here is joined, or once finished handed over, by the player's own load
 * path, so the only effect is that navigation, composition and player setup overlap with the
 * network work instead of queuing behind it. Use this for every user-initiated open of the player
 * screen.
 */
internal fun NavHostController.navigateToPlayer(videoId: String) {
    PlaybackPrefetcher.prefetch(videoId)
    navigate("player/$videoId")
}
