package io.github.aedev.flow.ui

import android.content.Intent
import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.innertube.pages.isMix
import io.github.aedev.flow.utils.YouTubeLink

/** Where a YouTube link opened from another app, or pasted into search, takes the user. */
internal sealed interface LinkDestination {
    data class Video(
        val videoId: String,
    ) : LinkDestination

    data class Short(
        val videoId: String,
    ) : LinkDestination

    /** [playsVideo] marks a route that starts a video and leaves, rather than a page to browse. */
    data class Page(
        val route: String,
        val playsVideo: Boolean = false,
    ) : LinkDestination
}

/** The link text of a VIEW or shared-text intent, or null for every other intent. */
internal fun linkTextOf(intent: Intent): String? =
    when (intent.action) {
        Intent.ACTION_VIEW -> intent.dataString
        Intent.ACTION_SEND -> intent.takeIf { it.type == "text/plain" }?.getStringExtra(Intent.EXTRA_TEXT)
        else -> null
    }

/**
 * Null for a link Flow has no page for: a search, and a mix, which YouTube builds around a video
 * and cannot be browsed on its own.
 */
internal fun linkDestination(link: YouTubeLink): LinkDestination? =
    when (link) {
        is YouTubeLink.Video -> videoDestination(link)
        is YouTubeLink.Short -> LinkDestination.Short(link.id)
        else -> pageRoute(link)?.let(LinkDestination::Page)
    }

/**
 * A video linked from inside a playlist plays with that playlist as its queue. The account's own
 * lists cannot be read without the account, and a mix link means its video, so those play alone.
 */
private fun videoDestination(link: YouTubeLink.Video): LinkDestination {
    val playlistId = link.playlistId?.takeUnless { it in ACCOUNT_LISTS }
    return when {
        link.isMusic -> LinkDestination.Page(musicPlayerRoute(link.id, playlistId))
        playlistId == null || isMix(playlistId) -> LinkDestination.Video(link.id)
        else -> LinkDestination.Page(linkedPlaylistRoute(playlistId, link.id), playsVideo = true)
    }
}

private fun pageRoute(link: YouTubeLink): String? =
    when (link) {
        is YouTubeLink.Video, is YouTubeLink.Short, is YouTubeLink.Search -> null
        is YouTubeLink.Playlist -> playlistRoute(link)
        is YouTubeLink.Album -> musicCollectionRoute(link.browseId)
        is YouTubeLink.Channel -> if (link.isMusic) musicArtistRoute(link.id) else youtubeChannelRoute(link.id)
        is YouTubeLink.ChannelHandle -> youtubeChannelRoute(link.handle)
        is YouTubeLink.LegacyChannel -> youtubeChannelRoute(link.url)
    }

private fun playlistRoute(link: YouTubeLink.Playlist): String? =
    when {
        link.id == "LL" -> "playlist/${PlaylistRepository.LIKED_VIDEOS_ID}"
        link.id == "WL" -> "playlist/${PlaylistRepository.WATCH_LATER_ID}"
        link.id == "LM" -> musicCollectionRoute(PlaylistRepository.LIKED_MUSIC_ID)
        isMix(link.id) -> null
        link.isMusic -> musicCollectionRoute(link.id)
        else -> "playlist/${link.id}"
    }

private val ACCOUNT_LISTS = setOf("LL", "WL", "LM")
