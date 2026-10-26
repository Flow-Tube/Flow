package io.github.aedev.flow.ui

import android.net.Uri
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import io.github.aedev.flow.data.shorts.queue.ShortsQueueSource
import io.github.aedev.flow.ui.components.layout.navigation.MediaNavigator
import io.github.aedev.flow.ui.components.settings.SettingsTarget
import io.github.aedev.flow.utils.YouTubeLink

internal const val MUSIC_ARTIST_ROUTE_PATTERN = "artist/{channelId}"
internal const val MUSIC_PLAYLIST_ROUTE_PATTERN = "musicPlaylist/{playlistId}"
internal const val MUSIC_PLAYER_ROUTE_PATTERN = "musicPlayer/{videoId}?list={list}"
internal const val MUSIC_PLAYER_ROUTE_ARG = "videoId"
internal const val MUSIC_PLAYER_LIST_ARG = "list"
internal const val EQUALIZER_ROUTE = "equalizer"

internal fun musicArtistRoute(artistId: String): String? = artistId.trim().takeIf(String::isNotEmpty)?.let { "artist/${Uri.encode(it)}" }

/**
 * Plays a song in the music player from its id alone, the way a YouTube Music link names it, with
 * the playlist the link plays it from as the queue.
 */
internal fun musicPlayerRoute(
    videoId: String,
    playlistId: String? = null,
): String = "musicPlayer/${Uri.encode(videoId)}" + playlistId?.let { "?list=${Uri.encode(it)}" }.orEmpty()

/** Albums and playlists share one page; InnerTube tells them apart by the browse id itself. */
internal fun musicCollectionRoute(collectionId: String): String? =
    collectionId.trim().takeIf(String::isNotEmpty)?.let { "musicPlaylist/${Uri.encode(it)}" }

internal enum class PageOpen { PUSH, STAY, RETURN }

/** A page already on the back stack is returned to, never stacked again; the one on top stays. */
internal fun <T> pageOpen(
    existing: T?,
    current: T?,
): PageOpen =
    when {
        existing == null -> PageOpen.PUSH
        existing == current -> PageOpen.STAY
        else -> PageOpen.RETURN
    }

/**
 * Opens [route], or returns to it when it is already on the back stack. Each stacked page keeps its
 * state in the activity's saved bundle, and a long loop between two channels grew that bundle past
 * what Android can hand over when the app stops (#1338).
 */
internal fun NavController.openPage(route: String) {
    // The public lookup for the exact, argument-filled match that popBackStack(route) pops to.
    val existing = runCatching { getBackStackEntry(route) }.getOrNull()
    when (pageOpen(existing, currentBackStackEntry)) {
        PageOpen.PUSH -> navigate(route)
        PageOpen.STAY -> Unit
        PageOpen.RETURN -> popBackStack(route, inclusive = false)
    }
}

/**
 * The shell's [MediaNavigator]. [beforeNavigate] moves an expanded player out of the way, so a page
 * opened from inside a player sheet is not hidden behind it. [shortsExitRoute] is where a video
 * opened from a Shorts player at the root of the back stack plays, since it cannot play over Shorts.
 */
internal class FlowMediaNavigator(
    private val navController: NavHostController,
    private val beforeNavigate: () -> Unit,
    private val shortsExitRoute: () -> String,
) : MediaNavigator {
    override fun openChannel(channelId: String) {
        if (channelId.isBlank()) return
        beforeNavigate()
        navController.navigateToYoutubeChannel(channelId)
    }

    override fun openArtist(artistId: String) = open(musicArtistRoute(artistId))

    override fun openAlbum(albumId: String) = openCollection(albumId)

    override fun openMusicPlaylist(playlistId: String) = openCollection(playlistId)

    override fun openSettings(target: SettingsTarget) {
        beforeNavigate()
        navController.navigate("settings?target=${target.encode()}")
    }

    override fun openEqualizer() {
        beforeNavigate()
        if (navController.currentBackStackEntry?.destination?.route == EQUALIZER_ROUTE) return
        navController.navigate(EQUALIZER_ROUTE)
    }

    /**
     * A link that starts a video leaves an expanded player where it is: the new video takes it over,
     * and collapsing it first would only bounce it down and back up.
     */
    override fun openLink(link: YouTubeLink): Boolean {
        val destination = linkDestination(link) ?: return false
        when {
            destination is LinkDestination.Video -> {
                openPlayer { navController.navigateToPlayer(destination.videoId) }
            }

            destination is LinkDestination.Page && destination.playsVideo -> {
                openPlayer { navController.navigate(destination.route) }
            }

            destination is LinkDestination.Short -> {
                beforeNavigate()
                navController.openShorts(ShortsQueueSource.SeededFeed(destination.videoId))
            }

            destination is LinkDestination.Page -> {
                beforeNavigate()
                navController.openPage(destination.route)
            }
        }
        return true
    }

    /**
     * The video player stays hidden while a Shorts screen is on top, and the player route hands back
     * to whatever is under it, so every Shorts screen has to go first or the video would play unseen.
     */
    private fun openPlayer(navigate: () -> Unit) {
        while (isShortsOnTop() && navController.previousBackStackEntry != null) navController.popBackStack()
        if (isShortsOnTop()) {
            navController.navigate(shortsExitRoute()) {
                popUpTo(SHORTS_ROUTE_PATTERN) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        navigate()
    }

    private fun isShortsOnTop() = navController.currentBackStackEntry?.destination?.route == SHORTS_ROUTE_PATTERN

    private fun openCollection(id: String) = open(musicCollectionRoute(id))

    private fun open(route: String?) {
        if (route == null) return
        beforeNavigate()
        navController.openPage(route)
    }
}
