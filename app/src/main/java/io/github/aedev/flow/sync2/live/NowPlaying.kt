package io.github.aedev.flow.sync2.live

import android.content.Context
import android.media.AudioManager
import androidx.annotation.MainThread
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.profile.ActiveProfile
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.player.PlaybackTarget
import io.github.aedev.flow.sync2.data.LinkedDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Resend `now` this often while playing, so devices that joined late catch up. */
private const val REPEAT_MS = 30_000L

/**
 * What this device plays, told to the group as `now` (spec/live.md, Playing on another device): when
 * the video, state, queue or volume changes, after every seek, and every 30 seconds while playing.
 */
@Singleton
class NowPlaying
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val live: LiveChannel,
        private val db: LinkedDatabase,
    ) {
        private val video = EnhancedPlayerManager.getInstance()
        private val lock = Mutex()
        private var last: LiveMessage.Now? = null

        /** Whether either player is playing, which keeps live running in the background. */
        val playing: Flow<Boolean> =
            combine(GlobalPlayerState.playerState, EnhancedMusicPlayerManager.playerState) { video, music ->
                video.isPlaying || music.isPlaying
            }.distinctUntilChanged()

        /** Each time what plays here changes: the video or song, playing or paused, the queue, or following another device. */
        val changes: Flow<Unit> =
            merge(
                GlobalPlayerState.currentVideo.map { it?.id },
                GlobalPlayerState.playerState.map { it.isPlaying },
                video.currentQueueIndexState,
                EnhancedMusicPlayerManager.currentTrack.map { it?.videoId },
                EnhancedMusicPlayerManager.playerState.map { it.isPlaying },
                EnhancedMusicPlayerManager.queue.map { it.size },
                PlaybackTarget.followed.map { it != null },
            ).map { }

        /** Sends `now` until cancelled. */
        suspend fun run() =
            coroutineScope {
                launch {
                    merge(
                        changes.map { false },
                        // A seek always goes out, however short.
                        merge(video.seekEvent, EnhancedMusicPlayerManager.seekEvent).map { true },
                    ).collect { publish(force = it) }
                }
                while (true) {
                    delay(REPEAT_MS)
                    // The players' state flows tell without touching a player, which only the main thread may.
                    val playing = GlobalPlayerState.playerState.value.isPlaying || EnhancedMusicPlayerManager.playerState.value.isPlaying
                    if (playing && PlaybackTarget.followed.value == null) {
                        publish(force = true)
                    }
                }
            }

        private suspend fun publish(force: Boolean) {
            lock.withLock {
                val now = snapshot()
                val previous = last
                if (!force && previous != null && !changed(previous, now)) return
                if (live.send(now) != null) last = now
            }
        }

        /** Anything but the position, which moves on by itself; nothing loaded counts once. */
        private fun changed(
            previous: LiveMessage.Now,
            now: LiveMessage.Now,
        ): Boolean = previous.copy(pos = now.pos) != now

        /** What plays here now, read on the main thread where the players live; the only way to read them. */
        suspend fun snapshot(): LiveMessage.Now =
            withContext(Dispatchers.Main.immediate) {
                readOnMain()
            }.copy(
                link =
                    db
                        .groups()
                        .group()
                        ?.link
                        ?.takeIf { ActiveProfile.get(context).isDefault },
            )

        /** Reads both players; ExoPlayer and the Media3 controller throw anywhere but the main thread. */
        @MainThread
        private fun readOnMain(): LiveMessage.Now {
            // Music shown here only follows the device this one drives; nothing is loaded here.
            if (PlaybackTarget.followed.value != null) return LiveMessage.Now(video = null)
            val track = EnhancedMusicPlayerManager.currentTrack.value
            val music = EnhancedMusicPlayerManager.playerState.value
            val vol = volume()
            if (track != null && musicInUse()) {
                val queue = EnhancedMusicPlayerManager.queue.value
                return LiveMessage.Now(
                    video = track.videoId,
                    title = track.title,
                    by = track.artist,
                    music = true,
                    pos = music.position,
                    dur = music.duration,
                    state = if (music.isPlaying) LiveMessage.PLAYING else LiveMessage.PAUSED,
                    vol = vol,
                    queue = queue.take(LiveMessage.QUEUE_LIMIT).map { it.videoId },
                    index = queue.indexOfFirst { it.videoId == track.videoId }.coerceAtLeast(0),
                )
            }
            val current = GlobalPlayerState.currentVideo.value ?: return LiveMessage.Now(video = null)
            val queue = video.queueVideos.value
            return LiveMessage.Now(
                video = current.id,
                title = current.title,
                by = current.channelName,
                pos = video.getCurrentPosition(),
                dur = video.getDuration().coerceAtLeast(0),
                state = if (GlobalPlayerState.playerState.value.isPlaying) LiveMessage.PLAYING else LiveMessage.PAUSED,
                vol = vol,
                queue = queue.take(LiveMessage.QUEUE_LIMIT).map { it.id },
                index = video.currentQueueIndexState.value.coerceAtLeast(0),
            )
        }

        /** The media volume, 0 to 100. */
        private fun volume(): Int {
            val audio = context.getSystemService(AudioManager::class.java) ?: return 100
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            return audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
        }
    }
