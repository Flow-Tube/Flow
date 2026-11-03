package io.github.aedev.flow.service.auto

import io.github.aedev.flow.data.local.LikedVideosRepository
import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.data.local.ViewHistory
import io.github.aedev.flow.data.model.PlaylistInfo
import io.github.aedev.flow.data.model.toMusicTrack
import io.github.aedev.flow.data.music.DownloadManager
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.music.model.audioMusicOnly
import io.github.aedev.flow.data.music.model.toMusicTrack
import io.github.aedev.flow.data.newmusic.InnertubeMusicService
import io.github.aedev.flow.data.recommendation.music.MusicBrainEngine
import io.github.aedev.flow.data.recommendation.music.onRepeatShelf
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.PlaybackResumePolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import io.github.aedev.flow.data.music.PlaylistRepository as MusicHistoryRepository

/**
 * What the car lists, read from what the app already stores. Browsing makes no network call; only a
 * search does, once per query within [SEARCH_TTL_MS].
 */
@Singleton
class AutoLibrary
    @Inject
    constructor(
        private val musicBrain: MusicBrainEngine,
        private val liked: LikedVideosRepository,
        private val downloads: DownloadManager,
        private val musicHistory: MusicHistoryRepository,
        private val playlists: PlaylistRepository,
        private val viewHistory: ViewHistory,
    ) {
        private data class CachedSearch(
            val at: Long,
            val tracks: List<MusicTrack>,
        )

        private val searchLock = Mutex()
        private val searches = LinkedHashMap<String, CachedSearch>()

        suspend fun playlists(): List<PlaylistInfo> = playlists.getMusicPlaylistsFlow().first()

        /** The user's own and saved video playlists that hold something to play. */
        suspend fun videoPlaylists(): List<PlaylistInfo> = playlists.getAllPlaylistsFlow().first().filter { it.videoCount > 0 }

        /** Where a video from a video list was left, so the car carries on from there; 0 to start over. */
        suspend fun resumePositionMs(videoId: String): Long =
            PlaybackResumePolicy.resolveStartPosition(
                savedPosition = viewHistory.getSavedPosition(videoId),
                durationMs = viewHistory.getSavedDuration(videoId),
                resumeAllowed = true,
            )

        /** The songs under [parentId], or null when it is not a list of songs. */
        suspend fun tracks(parentId: String): List<MusicTrack>? {
            (AutoBrowseTree.playlistIdOf(parentId) ?: AutoBrowseTree.videoPlaylistIdOf(parentId))?.let { id ->
                return playlists.getPlaylistVideosFlow(id).first().map { it.toMusicTrack() }
            }
            AutoBrowseTree.searchQueryOf(parentId)?.let { return search(it) }
            return when (parentId) {
                AutoBrowseTree.ON_REPEAT -> musicBrain.onRepeatShelf()
                AutoBrowseTree.RIGHT_NOW -> musicBrain.timeOfDayTracks(MIX_SIZE).audioMusicOnly()
                AutoBrowseTree.REDISCOVER -> musicBrain.rediscoverTracks(MIX_SIZE).audioMusicOnly()
                AutoBrowseTree.LIKED -> liked.getLikedMusicFlow().first().map { it.toMusicTrack() }
                AutoBrowseTree.DOWNLOADS -> downloads.downloadedTracks.first().map { it.track }
                AutoBrowseTree.RECENT -> musicHistory.history.first()
                AutoBrowseTree.WATCH_LATER -> playlists.getVideoOnlyWatchLaterFlow().first().map { it.toMusicTrack() }
                AutoBrowseTree.QUEUE -> EnhancedMusicPlayerManager.queue.value
                else -> null
            }
        }

        /** Songs for [query], from the cache when the same words were searched a moment ago. */
        suspend fun search(query: String): List<MusicTrack> {
            val key = query.trim().lowercase()
            if (key.isEmpty()) return emptyList()
            return searchLock.withLock {
                val now = System.currentTimeMillis()
                searches[key]?.takeIf { now - it.at < SEARCH_TTL_MS }?.let { return@withLock it.tracks }
                val tracks = InnertubeMusicService.searchMusic(query.trim())
                searches[key] = CachedSearch(now, tracks)
                while (searches.size > MAX_CACHED_SEARCHES) searches.remove(searches.keys.first())
                tracks
            }
        }

        private companion object {
            const val MIX_SIZE = 30
            const val SEARCH_TTL_MS = 10 * 60 * 1000L
            const val MAX_CACHED_SEARCHES = 8
        }
    }
