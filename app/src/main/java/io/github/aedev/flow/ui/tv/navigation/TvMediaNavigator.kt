package io.github.aedev.flow.ui.tv.navigation

import androidx.navigation.NavHostController
import io.github.aedev.flow.ui.components.layout.navigation.MediaNavigator
import io.github.aedev.flow.utils.YouTubeLink
import java.net.URLDecoder

/**
 * The TV shell's [MediaNavigator], so a card's channel, a menu's "Go to artist" and a link in a
 * description open the TV page for it. [beforeNavigate] closes a full-screen player, which would
 * otherwise cover the page. A player a link opened at launch covers a shell that has never set its
 * graph, so its page goes to [navigateWhenReady]. TV has no equalizer page, so that request does nothing.
 */
internal class TvMediaNavigator(
    private val navController: NavHostController,
    private val beforeNavigate: () -> Unit,
    private val playVideo: (videoId: String) -> Unit,
    private val navigateWhenReady: (route: String) -> Unit,
) : MediaNavigator {
    override fun openChannel(channelId: String) = open(channelId, TvRoutes.CHANNEL, TvRoutes.CHANNEL_ARG, TvRoutes::channel)

    override fun openArtist(artistId: String) = open(artistId, TvRoutes.MUSIC_ARTIST, TvRoutes.MUSIC_ARTIST_ARG, TvRoutes::musicArtist)

    override fun openAlbum(albumId: String) = openCollection(albumId)

    override fun openMusicPlaylist(playlistId: String) = openCollection(playlistId)

    override fun openEqualizer() = Unit

    override fun openLink(link: YouTubeLink): Boolean {
        when (link) {
            is YouTubeLink.Video -> {
                playVideo(link.id)
            }

            is YouTubeLink.Short -> {
                playVideo(link.id)
            }

            is YouTubeLink.Playlist -> {
                if (link.isMusic) {
                    openMusicPlaylist(
                        link.id,
                    )
                } else {
                    open(link.id, TvRoutes.PLAYLIST, TvRoutes.PLAYLIST_ARG, TvRoutes::playlist)
                }
            }

            is YouTubeLink.Album -> {
                openAlbum(link.browseId)
            }

            is YouTubeLink.Channel -> {
                if (link.isMusic) openArtist(link.id) else openChannel(link.id)
            }

            is YouTubeLink.ChannelHandle -> {
                openChannel("https://www.youtube.com/${link.handle}")
            }

            is YouTubeLink.LegacyChannel -> {
                openChannel(link.url)
            }

            is YouTubeLink.Search -> {
                return false
            }
        }
        return true
    }

    private fun openCollection(id: String) = open(id, TvRoutes.MUSIC_COLLECTION, TvRoutes.MUSIC_COLLECTION_ARG, TvRoutes::musicCollection)

    /** Opens [route] for [id] unless that page is already on top, so a repeat tap does not stack it twice. */
    private fun open(
        id: String,
        pattern: String,
        argName: String,
        route: (String) -> String,
    ) {
        val key = id.trim().takeIf(String::isNotEmpty) ?: return
        beforeNavigate()
        val current = navController.currentBackStackEntry ?: return navigateWhenReady(route(key))
        val openId = current.arguments?.getString(argName)?.let { URLDecoder.decode(it, "UTF-8") }
        if (current.destination.route == pattern && openId == key) return
        navController.navigate(route(key))
    }
}
