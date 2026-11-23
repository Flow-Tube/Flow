package io.github.aedev.flow.ui.screens.playlists

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.newmusic.InnertubeMusicService
import io.github.aedev.flow.data.repository.PlaylistQueueSources
import io.github.aedev.flow.data.repository.RemotePlaylistPage
import io.github.aedev.flow.data.repository.YouTubePlaylistRepository
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.data.repository.walkPlaylistPages
import io.github.aedev.flow.player.queue.QueuePageSource
import javax.inject.Inject

/** A playlist a link plays from, positioned at the video the link names. */
internal data class LinkedQueue<T>(
    val items: List<T>,
    val startIndex: Int,
    val title: String?,
    val more: QueuePageSource? = null,
)

/**
 * Reads the playlist a `watch?v=…&list=…` link plays its video from. It loads nothing until asked,
 * so the route that plays the link can own one freely.
 */
@HiltViewModel
internal class LinkedPlaylistViewModel
    @Inject
    constructor(
        private val playlists: YouTubePlaylistRepository,
        private val repository: YouTubeRepository,
        private val sources: PlaylistQueueSources,
    ) : ViewModel() {
        /** Null when the playlist cannot be read or the video is not in it, so the video plays alone. */
        suspend fun videoQueue(
            playlistId: String,
            startVideoId: String,
        ): LinkedQueue<Video>? {
            playlists.cachedComplete(playlistId)?.let { cached ->
                return linkedQueue(cached.videos, startVideoId, cached.title, Video::id)
            }
            // The watch request the player makes for the video anyway carries the playlist around it.
            repository
                .watchPlaylistWindow(
                    startVideoId,
                    playlistId,
                )?.takeIf { it.videos.getOrNull(it.currentIndex)?.id == startVideoId }
                ?.let { window ->
                    return LinkedQueue(
                        items = window.videos,
                        startIndex = window.currentIndex,
                        title = window.title,
                        more = window.continuation?.let { sources.watchPanel(playlistId, it) },
                    )
                }
            val first = playlists.firstPage(playlistId) ?: return null
            return findLinkedVideoQueue(first, startVideoId) { token -> playlists.nextPage(playlistId, token) }
        }

        suspend fun musicQueue(
            videoId: String,
            playlistId: String,
        ): LinkedQueue<MusicTrack>? =
            linkedQueue(InnertubeMusicService.fetchQueue(playlistId = playlistId), videoId, title = null, MusicTrack::videoId)
    }

internal fun <T> linkedQueue(
    items: List<T>,
    startId: String,
    title: String?,
    idOf: (T) -> String,
): LinkedQueue<T>? = items.indexOfFirst { idOf(it) == startId }.takeIf { it >= 0 }?.let { LinkedQueue(items, it, title) }

/**
 * Walks the playlist a page at a time until the linked video turns up, so a link deep into a long
 * playlist still finds it, and stops after [maxPages] rather than reading thousands of videos
 * before anything plays.
 */
internal suspend fun findLinkedVideoQueue(
    first: RemotePlaylistPage,
    startVideoId: String,
    maxPages: Int = LINKED_PLAYLIST_MAX_PAGES,
    nextPage: suspend (continuation: String) -> Pair<List<Video>, String?>?,
): LinkedQueue<Video>? {
    val walk =
        walkPlaylistPages(
            firstVideos = first.videos,
            firstToken = first.continuation,
            maxPages = maxPages,
            nextPage = nextPage,
            keepGoing = { videos -> videos.none { it.id == startVideoId } },
        )
    return linkedQueue(walk.videos, startVideoId, first.title, Video::id)
}

private const val LINKED_PLAYLIST_MAX_PAGES = 10
