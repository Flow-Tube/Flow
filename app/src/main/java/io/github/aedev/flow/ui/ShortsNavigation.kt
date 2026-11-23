package io.github.aedev.flow.ui

import android.net.Uri
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.shorts.queue.ShortsQueueSource
import io.github.aedev.flow.data.shorts.queue.openAtVideoId

const val SHORTS_ROUTE_PATTERN = "shorts?src={src}"

const val SHORTS_ROUTE_ARG = "src"

const val SHORTS_ROUTE_KEY = "shorts"

fun NavController.openShorts(
    source: ShortsQueueSource,
    builder: NavOptionsBuilder.() -> Unit = {},
) {
    navigate("shorts?$SHORTS_ROUTE_ARG=${Uri.encode(source.encode())}", builder)
}

/**
 * Clears every Shorts screen off the top before a video opens. The video player stays hidden while
 * a Shorts screen is on top, and the player route hands back to whatever is under it, so a video
 * opened over Shorts would play unseen behind a reel that keeps playing. A Shorts screen at the root
 * of the back stack gives way to [exitRoute].
 */
fun NavController.leaveShortsForPlayer(exitRoute: String) {
    while (isShortsOnTop() && previousBackStackEntry != null) popBackStack()
    if (!isShortsOnTop()) return
    navigate(exitRoute) {
        popUpTo(SHORTS_ROUTE_PATTERN) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun NavController.isShortsOnTop() = currentBackStackEntry?.destination?.route == SHORTS_ROUTE_PATTERN

/**
 * Opens [source] in the Shorts player, or the tapped reel in the video player when the user has
 * switched the Shorts player off. Every Shorts entry point takes this one decision.
 */
internal fun NavHostController.openShortsOrPlayer(
    source: ShortsQueueSource,
    disableShortsPlayer: Boolean,
) {
    val tappedId = source.openAtVideoId
    if (disableShortsPlayer && tappedId != null) navigateToPlayer(tappedId) else openShorts(source)
}

/** A reel goes to the Shorts player unless that is switched off; everything else goes to [playVideo]. */
internal fun NavHostController.openVideoOrShorts(
    video: Video,
    disableShortsPlayer: Boolean,
    playVideo: (Video) -> Unit,
) {
    if (video.isShort && !disableShortsPlayer) openShorts(ShortsQueueSource.SeededFeed(video.id)) else playVideo(video)
}
