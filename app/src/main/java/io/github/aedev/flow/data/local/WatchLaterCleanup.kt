package io.github.aedev.flow.data.local

import io.github.aedev.flow.data.local.entity.PlaylistVideoCrossRef
import io.github.aedev.flow.data.model.Video
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Takes watched videos out of Watch later when the viewer turned that on (#1060). A video leaves
 * as its progress crosses the watched threshold, and opening Watch later sweeps out any that were
 * watched elsewhere, with an Undo.
 */
@Singleton
class WatchLaterCleanup
    @Inject
    constructor(
        private val playlists: PlaylistRepository,
        private val preferences: PlayerPreferences,
        private val viewHistory: ViewHistory,
    ) {
        suspend fun onProgress(
            videoId: String,
            positionMs: Long,
            durationMs: Long,
        ) {
            if (!preferences.removeWatchedFromWatchLater.first()) return
            if (!preferences.watchedThreshold.first().isWatched(positionMs, durationMs)) return
            if (playlists.isInWatchLater(videoId)) playlists.removeFromWatchLater(videoId)
        }

        /** The viewer marked [videoId] as watched, or it played to its end. */
        suspend fun onFinished(videoId: String) {
            if (!preferences.removeWatchedFromWatchLater.first()) return
            if (playlists.isInWatchLater(videoId)) playlists.removeFromWatchLater(videoId)
        }

        /** Removes the watched ones among [videos] and returns what Undo needs to put them back. */
        suspend fun sweep(videos: List<Video>): List<PlaylistVideoCrossRef> {
            if (videos.isEmpty() || !preferences.removeWatchedFromWatchLater.first()) return emptyList()
            val watched =
                watchedIds(
                    videos,
                    viewHistory.getVideoHistoryFlow().first(),
                    preferences.watchedThreshold.first(),
                    preferences.countImportedAsWatched.first(),
                )
            if (watched.isEmpty()) return emptyList()
            return playlists.takeVideosFromPlaylist(PlaylistRepository.WATCH_LATER_ID, watched)
        }
    }

internal fun watchedIds(
    videos: List<Video>,
    history: List<VideoHistoryEntry>,
    threshold: WatchedThreshold,
    countImported: Boolean = false,
): List<String> {
    val progress = history.associateBy { it.videoId }
    return videos.mapNotNull { video ->
        video.id.takeIf { progress[it]?.let { entry -> threshold.isWatched(entry.position, entry.duration, countImported) } == true }
    }
}
