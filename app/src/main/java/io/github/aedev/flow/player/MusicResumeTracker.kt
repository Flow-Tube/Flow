package io.github.aedev.flow.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.ViewHistory
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Saves where podcasts and other long tracks stop, on the player's own events: a pause, leaving the
 * track, and the service going away. No timer runs, and nothing is written while the setting is off.
 * A video played from a video list in the car (#1657) is saved to the watch history whatever its length,
 * as the video player would save it.
 */
@Singleton
class MusicResumeTracker
    @Inject
    constructor(
        private val viewHistory: ViewHistory,
        private val preferences: PlayerPreferences,
    ) {
        // Outlives the service, so the save from onDestroy still lands.
        private val writes = SupervisorJob()
        private val scope = CoroutineScope(writes + PerformanceDispatcher.diskIO)

        /** Waits for saves still being written, before a profile switch restarts the process. */
        suspend fun awaitWrites() = writes.children.toList().joinAll()

        fun attach(player: Player) {
            player.addListener(
                object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (!isPlaying) saveCurrent(player)
                    }

                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int,
                    ) {
                        val left = oldPosition.mediaItem ?: return
                        if (left.mediaId == newPosition.mediaItem?.mediaId) return
                        val durationMs = left.mediaMetadata.durationMs ?: return
                        val finished = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
                        save(left, if (finished) durationMs else oldPosition.positionMs, durationMs)
                    }
                },
            )
        }

        fun saveCurrent(player: Player) {
            val item = player.currentMediaItem ?: return
            val durationMs = item.mediaMetadata.durationMs ?: player.duration.takeIf { it != C.TIME_UNSET } ?: return
            save(item, player.currentPosition, durationMs)
        }

        private fun save(
            item: MediaItem,
            positionMs: Long,
            durationMs: Long,
        ) {
            val metadata = item.mediaMetadata
            if (item.isVideoListItem) {
                scope.launch {
                    viewHistory.savePlaybackPosition(
                        videoId = item.mediaId,
                        position = positionMs.coerceIn(0L, durationMs),
                        duration = durationMs,
                        title = metadata.title?.toString().orEmpty(),
                        thumbnailUrl = metadata.webArtworkUrl.orEmpty(),
                        channelName = metadata.artist?.toString().orEmpty(),
                    )
                }
                return
            }
            if (!MusicResumePolicy.isLongTrack(durationMs)) return
            scope.launch {
                if (!preferences.musicResumeLongTracks.first()) return@launch
                viewHistory.saveMusicPosition(
                    videoId = item.mediaId,
                    position = positionMs.coerceIn(0L, durationMs),
                    duration = durationMs,
                    title = metadata.title?.toString().orEmpty(),
                    thumbnailUrl = metadata.webArtworkUrl.orEmpty(),
                    channelName = metadata.artist?.toString().orEmpty(),
                )
            }
        }
    }
