package io.github.aedev.flow.sync2.live

import android.content.Context
import android.media.AudioManager
import androidx.annotation.MainThread
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.di.ApplicationScope
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.player.PlaybackTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** A handed-off video that hasn't loaded by then didn't start. */
private const val START_MS = 20_000L

/** Music is the player in use while it plays, or when it holds a track and video isn't playing. */
internal fun musicInUse(): Boolean =
    EnhancedMusicPlayerManager.currentTrack.value != null &&
        (EnhancedMusicPlayerManager.playerState.value.isPlaying || !GlobalPlayerState.playerState.value.isPlaying)

/**
 * What a handoff or a remote control does to this device's players (spec/live.md, Playing on another
 * device), on the main thread where they live. A video opens through the app shell, as a tapped link would.
 */
@Singleton
class LocalPlayback
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val choice: RemoteChoice,
        private val details: TrackDetails,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        private val video = EnhancedPlayerManager.getInstance()
        private val videos = MutableSharedFlow<String>(extraBufferCapacity = 1)
        private val songs = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        private var filling: Job? = null

        /** Videos a handoff asks the app to open. */
        val openVideo: SharedFlow<String> = videos.asSharedFlow()

        /** Music a handoff started here, which the app shows as it shows a song started on this device. */
        val musicStarted: SharedFlow<Unit> = songs.asSharedFlow()

        /**
         * Starts [play]'s session here; returns why it couldn't, or null once it started. [known] is the
         * sender's latest `now`, whose title and channel or artist name the video it hands over. Playback
         * is here again, so this device stops driving another one.
         */
        suspend fun start(
            play: LiveMessage.Play,
            known: LiveMessage.Now? = null,
        ): String? {
            choice.clear()
            val about = known?.takeIf { it.video == play.video }
            return if (play.music) startMusic(play, about) else startVideo(play, about)
        }

        private suspend fun startMusic(
            play: LiveMessage.Play,
            about: LiveMessage.Now?,
        ): String? {
            val ids = play.queue.ifEmpty { listOf(play.video) }
            val tracks = ids.map { id -> details.knownSong(id) ?: songOf(id, about) }
            val index = tracks.indexOfFirst { it.videoId == play.video }.takeIf { it >= 0 } ?: return "invalid"
            withContext(Dispatchers.Main) {
                PlaybackTarget.here {
                    // The music service resolves the stream from the id, as when a track is tapped.
                    EnhancedMusicPlayerManager.playTrack(tracks[index], "music://${play.video}", tracks, index, play.pos)
                    if (play.state != LiveMessage.PLAYING) EnhancedMusicPlayerManager.pause()
                }
            }
            songs.tryEmit(Unit)
            fillSongs(ids)
            return null
        }

        private suspend fun startVideo(
            play: LiveMessage.Play,
            about: LiveMessage.Now?,
        ): String? {
            withContext(Dispatchers.Main) { PlaybackTarget.here { EnhancedMusicPlayerManager.pause() } }
            if (!videos.tryEmit(play.video)) return "busy"
            withTimeoutOrNull(START_MS) {
                GlobalPlayerState.playerState.first { it.currentVideoId == play.video && it.isPrepared }
            } ?: return "unavailable"
            withContext(Dispatchers.Main) {
                // The sender's queue goes around the video already playing, which goes on where it is.
                if (play.queue.size > 1) video.setQueue(play.queue.map { videoOf(it, about) }, play.index, startPlayback = false)
                video.seekTo(play.pos)
                if (play.state != LiveMessage.PLAYING) video.pause()
            }
            fillVideos()
            return null
        }

        /**
         * Lets go once another device has taken over: music pauses and keeps its mini player, which
         * then drives that device; a video closes, so the remote mini player takes its place.
         */
        suspend fun release() =
            withContext(Dispatchers.Main) {
                if (musicInUse()) {
                    EnhancedMusicPlayerManager.pause()
                } else {
                    video.stop()
                    GlobalPlayerState.hideMiniPlayer()
                }
            }

        /** Applies [cmd] to the player in use; returns why it couldn't, or null. */
        suspend fun command(cmd: LiveMessage.Cmd): String? = withContext(Dispatchers.Main) { PlaybackTarget.here { apply(cmd) } }

        @MainThread
        private fun apply(cmd: LiveMessage.Cmd): String? {
            val music = musicInUse()
            when (cmd.cmd) {
                "play" -> if (music) EnhancedMusicPlayerManager.play() else video.play()
                "pause" -> if (music) EnhancedMusicPlayerManager.pause() else video.pause()
                "seek" -> seek(music, cmd.pos ?: return "invalid")
                "next" -> if (music) EnhancedMusicPlayerManager.playNext() else video.playNext()
                "previous" -> if (music) EnhancedMusicPlayerManager.playPrevious() else video.playPrevious()
                "volume" -> setVolume(cmd.vol ?: return "invalid")
                "queue" -> return queue(music, cmd.queue ?: return "invalid")
                else -> return "invalid"
            }
            return null
        }

        @MainThread
        private fun seek(
            music: Boolean,
            pos: Long,
        ) {
            if (music) EnhancedMusicPlayerManager.seekTo(pos) else video.seekTo(pos)
        }

        /**
         * Replaces the queue around what plays, without restarting it. A song stays and the rest goes
         * around or after it; a video not in the new queue gives way to its start.
         */
        @MainThread
        private fun queue(
            music: Boolean,
            ids: List<String>,
        ): String? {
            if (music) {
                if (!EnhancedMusicPlayerManager.replaceQueue(ids.map { details.knownSong(it) ?: songOf(it, null) })) return "unavailable"
                fillSongs(ids)
                return null
            }
            val index = ids.indexOf(GlobalPlayerState.currentVideo.value?.id)
            video.setQueue(ids.map { videoOf(it, null) }, index.coerceAtLeast(0), startPlayback = index < 0)
            fillVideos()
            return null
        }

        /** Names the songs of a queue another device handed over by id, once the music service knows them. */
        private fun fillSongs(ids: List<String>) {
            if (ids.all { details.knownSong(it) != null }) return
            scope.launch {
                val found = details.songs(ids)
                withContext(Dispatchers.Main) { PlaybackTarget.here { EnhancedMusicPlayerManager.fillDetails(found) } }
            }
        }

        /**
         * Names each video of a handed-over queue as it comes up to play, with the one after it, from
         * the video details lookup.
         */
        private fun fillVideos() {
            filling?.cancel()
            filling =
                scope.launch {
                    video.currentQueueIndexState.collect { index ->
                        val queue = video.queueVideos.value
                        val unnamed = (index..index + 1).filter { queue.getOrNull(it)?.title?.isEmpty() == true }
                        if (unnamed.isEmpty()) return@collect
                        val named = unnamed.mapNotNull { i -> details.video(queue[i].id)?.let { i to it } }.toMap()
                        withContext(Dispatchers.Main) {
                            // Only the same queue, still at the same video, takes the names.
                            if (video.queueVideos.value == queue && video.currentQueueIndexState.value == index) {
                                video.setQueue(queue.mapIndexed { i, v -> named[i] ?: v }, index, startPlayback = false)
                            }
                        }
                    }
                }
        }

        private fun setVolume(percent: Int) {
            val audio = context.getSystemService(AudioManager::class.java) ?: return
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, percent.coerceIn(0, 100) * max / 100, 0)
        }
    }

private fun thumbnail(id: String) = "https://i.ytimg.com/vi/$id/hqdefault.jpg"

/** A song known by its id, and by [about]'s title and artist when it is the one [about] plays. */
internal fun songOf(
    id: String,
    about: LiveMessage.Now?,
): MusicTrack {
    val known = about?.takeIf { it.video == id }
    return MusicTrack(id, known?.title.orEmpty(), known?.by.orEmpty(), thumbnail(id), ((known?.dur ?: 0) / 1000).toInt())
}

/** A video known by its id, and by [about]'s title and channel when it is the one [about] plays. */
private fun videoOf(
    id: String,
    about: LiveMessage.Now?,
): Video {
    val known = about?.takeIf { it.video == id }
    return Video(id, known?.title.orEmpty(), known?.by.orEmpty(), "", thumbnail(id), 0, 0, uploadDate = "")
}
