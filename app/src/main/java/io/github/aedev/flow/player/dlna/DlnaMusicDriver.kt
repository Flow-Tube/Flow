package io.github.aedev.flow.player.dlna

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.dao.DownloadDao
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.newmusic.InnertubeMusicService
import io.github.aedev.flow.data.video.storage.DownloadFiles
import io.github.aedev.flow.di.ApplicationScope
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.Followed
import io.github.aedev.flow.player.PlaybackTarget
import io.github.aedev.flow.player.RemotePlayer
import io.github.aedev.flow.player.RepeatMode
import io.github.aedev.flow.player.datasource.GoogleVideoRequestPolicy
import io.github.aedev.flow.player.error.StreamDenialClassifier
import io.github.aedev.flow.sync2.live.LiveMessage
import io.github.aedev.flow.sync2.live.LocalPlayback
import io.github.aedev.flow.utils.MusicPlayerUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Plays the music queue on a DLNA renderer (a TV, Kodi, a speaker) as a "Play on" target: every
 * playback action in the app, the notification, lock screen, headset and car goes to the renderer,
 * and the music player shows its song, paused here. The renderer plays one song at a time, so the
 * phone sends the next one when a song ends.
 */
@Singleton
class DlnaMusicDriver
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @ApplicationScope private val scope: CoroutineScope,
        private val downloads: DownloadDao,
        private val localPlayback: LocalPlayback,
    ) : RemotePlayer {
        private val target = MutableStateFlow<DlnaDevice?>(null)

        /** The renderer the music plays on, while it does. */
        val device: StateFlow<DlnaDevice?> = target.asStateFlow()

        /** Whether the music player's device picker is open. */
        val pickerOpen = MutableStateFlow(false)

        private var tracks: List<MusicTrack> = emptyList()
        private var current = -1
        private var followed = Followed(playing = false, position = 0, at = 0)
        private var lastPositionMs = 0L
        private var expectedEndAt: Long? = null
        private var ended = false
        private var misses = 0
        private var watching: Job? = null
        private val checks = MutableStateFlow(0)

        @Volatile private var loading: Job? = null

        override val playing: Boolean get() = followed.playing

        override val queue: List<String> get() = tracks.map { it.videoId }

        override val index: Int get() = current

        /** Moves the music to [renderer]: this device pauses, and the queue goes on there from this song and position. */
        @MainThread
        fun start(renderer: DlnaDevice) {
            val track = EnhancedMusicPlayerManager.currentTrack.value ?: return
            val list = EnhancedMusicPlayerManager.queue.value.ifEmpty { listOf(track) }
            val at = list.indexOfFirst { it.videoId == track.videoId }.coerceAtLeast(0)
            val position = EnhancedMusicPlayerManager.playerState.value.position
            PlaybackTarget.here { EnhancedMusicPlayerManager.pause() }
            PlaybackTarget.remote?.takeIf { it !== this }?.release()
            PlaybackTarget.remote = this
            target.value = renderer
            tracks = list
            load(at, position)
            watch()
        }

        /** Stops the renderer and keeps the queue here, paused where the renderer was. */
        @MainThread
        fun stopCasting() {
            if (target.value == null) return
            val list = tracks
            val at = current
            val position = followed.positionAt(System.currentTimeMillis())
            release()
            list.getOrNull(at)?.let { EnhancedMusicPlayerManager.follow(list, at, position, durationMs(it)) }
        }

        override fun command(
            cmd: String,
            pos: Long?,
        ) {
            val renderer = target.value ?: return
            val position = followed.positionAt(System.currentTimeMillis())
            when (cmd) {
                "play" -> {
                    if (ended) return load(current)
                    report(true, position)
                    send { it.play(renderer) }
                }

                "pause" -> {
                    report(false, position)
                    send { it.pause(renderer) }
                }

                "next" -> {
                    CastQueuePolicy.next(current, tracks.size, RepeatMode.ALL.takeIf { repeatsAll() } ?: RepeatMode.OFF)?.let { load(it) }
                }

                "previous" -> {
                    if (position > RESTART_MS || current <= 0) {
                        seek(renderer, 0)
                    } else {
                        load(current - 1)
                    }
                }

                "seek" -> {
                    pos?.let { seek(renderer, it) }
                }
            }
        }

        override fun play(
            queue: List<String>,
            index: Int,
            music: Boolean,
        ) {
            if (queue.getOrNull(index) == null) return
            if (!music) {
                // A video plays here, where the video player is.
                release()
                val play = LiveMessage.Play("", queue[index], 0, false, null, queue, index, LiveMessage.PLAYING)
                scope.launch { localPlayback.start(play) }
                return
            }
            scope.launch {
                val list = tracksFor(queue)
                withContext(Dispatchers.Main) {
                    if (target.value == null) return@withContext
                    tracks = list
                    load(index)
                }
            }
        }

        override fun setQueue(ids: List<String>) {
            if (ids == queue) return
            val playingId = tracks.getOrNull(current)?.videoId
            scope.launch {
                val list = tracksFor(ids)
                withContext(Dispatchers.Main) {
                    if (target.value == null || list.isEmpty()) return@withContext
                    tracks = list
                    val at = list.indexOfFirst { it.videoId == playingId }
                    if (at >= 0) {
                        current = at
                        showTrack()
                    } else {
                        load(current.coerceIn(0, list.lastIndex))
                    }
                }
            }
        }

        override fun release() {
            if (target.value == null) return
            detach()
            DlnaCastManager.stop()
        }

        @MainThread
        private fun detach() {
            loading?.cancel()
            watching?.cancel()
            if (PlaybackTarget.remote === this) PlaybackTarget.remote = null
            PlaybackTarget.followed.value = null
            target.value = null
            tracks = emptyList()
            current = -1
            ended = false
        }

        @MainThread
        private fun load(
            index: Int,
            positionMs: Long = 0,
        ) {
            val renderer = target.value ?: return
            val track = tracks.getOrNull(index) ?: return
            current = index
            ended = false
            lastPositionMs = positionMs
            report(true, positionMs)
            showTrack()
            loading?.cancel()
            loading =
                scope.launch {
                    val source = withContext(Dispatchers.IO) { runCatching { source(track, renderer) }.getOrNull() }
                    if (source == null) {
                        toast(R.string.cast_song_unavailable, track.title)
                        withContext(Dispatchers.Main) { skipFrom(index) }
                        return@launch
                    }
                    try {
                        DlnaCastManager.castSong(renderer, source.url, source.contentType, castItem(track))
                        if (positionMs >=
                            RESTART_MS
                        ) {
                            withContext(Dispatchers.IO) { DlnaCastManager.transport.seek(renderer, positionMs / 1000) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Casting ${track.videoId} failed: ${e.message}")
                    }
                    withContext(Dispatchers.Main) {
                        report(true, positionMs)
                        checks.value++
                    }
                }
        }

        /** A song that can't be cast is passed over; past the end of the queue the renderer stops. */
        @MainThread
        private fun skipFrom(index: Int) {
            if (index + 1 < tracks.size) {
                load(index + 1)
            } else {
                ended = true
                report(false, 0)
            }
        }

        private fun seek(
            renderer: DlnaDevice,
            positionMs: Long,
        ) {
            report(followed.playing, positionMs)
            send { it.seek(renderer, positionMs / 1000) }
        }

        private fun send(action: (DlnaTransport) -> Unit) {
            scope.launch(Dispatchers.IO) {
                runCatching { action(DlnaCastManager.transport) }.onFailure { Log.w(TAG, "Renderer action failed: ${it.message}") }
                checks.value++
            }
        }

        /** The music player shows the renderer's song and queue, loaded but stopped here. */
        @MainThread
        private fun showTrack() {
            val track = tracks.getOrNull(current) ?: return
            EnhancedMusicPlayerManager.follow(tracks, current, followed.positionAt(System.currentTimeMillis()), durationMs(track))
        }

        /** The session here reports the renderer as [playing] at [positionMs], moving on by itself while it plays. */
        @MainThread
        private fun report(
            playing: Boolean,
            positionMs: Long,
        ) {
            val now = System.currentTimeMillis()
            val duration = tracks.getOrNull(current)?.let(::durationMs) ?: 0L
            followed = Followed(playing, positionMs, now)
            expectedEndAt = if (playing && duration > 0) now + duration - positionMs else null
            PlaybackTarget.followed.value = followed
        }

        /** Asks the renderer how it is doing, every second while the player shows progress, otherwise when the song should end. */
        private fun watch() {
            watching?.cancel()
            watching =
                scope.launch {
                    launch {
                        // Another cast, or Stop in the video player's cast dialog, took the renderer.
                        DlnaCastManager.castsSong.dropWhile { !it }.first { !it }
                        withContext(Dispatchers.Main) { detach() }
                    }
                    combine(
                        target,
                        EnhancedMusicPlayerManager.preciseProgressWanted,
                        checks,
                    ) { renderer, watched, _ -> renderer to watched }
                        .collectLatest { (renderer, watched) ->
                            renderer ?: return@collectLatest
                            delay(SETTLE_MS)
                            while (loading?.isActive != true) {
                                val status = withContext(Dispatchers.IO) { DlnaCastManager.status(renderer) }
                                val wait = withContext(Dispatchers.Main) { apply(status, watched) } ?: return@collectLatest
                                delay(wait)
                            }
                        }
                }
        }

        /** Takes in what the renderer said; returns when to ask again, or null to wait for the next action. */
        @MainThread
        private fun apply(
            status: CastStatus?,
            watched: Boolean,
        ): Long? {
            val renderer = target.value ?: return null
            if (status == null) {
                if (++misses < MAX_MISSES) return MISS_RETRY_MS
                toast(R.string.cast_connection_lost, renderer.friendlyName)
                stopCasting()
                return null
            }
            misses = 0
            val track = tracks.getOrNull(current) ?: return null
            val duration = status.durationMs?.takeIf { it > 0 } ?: durationMs(track)
            val now = System.currentTimeMillis()
            if (!ended && CastQueuePolicy.finishedSong(status, lastPositionMs, duration, expectedEndAt, now)) {
                val next = CastQueuePolicy.next(current, tracks.size, EnhancedMusicPlayerManager.repeatMode.value)
                if (next != null) {
                    load(next)
                } else {
                    ended = true
                    report(false, 0)
                }
                return null
            }
            val playing = CastQueuePolicy.isPlaying(status.state)
            val position = status.positionMs ?: followed.positionAt(now)
            if (playing) lastPositionMs = position
            if (playing != followed.playing || abs(position - followed.positionAt(now)) > DRIFT_MS) report(playing, position)
            return CastQueuePolicy.nextCheckMs(playing, position, duration, watched)
        }

        private fun repeatsAll() = EnhancedMusicPlayerManager.repeatMode.value == RepeatMode.ALL

        private suspend fun tracksFor(ids: List<String>): List<MusicTrack> {
            val known = (tracks + EnhancedMusicPlayerManager.queue.value).associateBy { it.videoId }
            val missing = ids.filterNot { it in known || LocalMediaIds.isLocal(it) }.distinct()
            val fetched =
                if (missing.isEmpty()) {
                    emptyMap()
                } else {
                    runCatching {
                        InnertubeMusicService.fetchQueue(
                            videoIds = missing,
                        )
                    }.getOrDefault(emptyList()).associateBy { it.videoId }
                }
            return ids.map { id -> known[id] ?: fetched[id] ?: MusicTrack(id, "", "", "", 0) }
        }

        private class CastSource(
            val url: String,
            val contentType: String,
        )

        /** Where the renderer fetches [track] through the proxy here: the file on this device, or the AAC stream. */
        private suspend fun source(
            track: MusicTrack,
            renderer: DlnaDevice,
        ): CastSource? {
            val host = DlnaCastManager.hostFor(renderer)
            val id = track.videoId
            if (LocalMediaIds.isLocal(id)) return LocalMediaIds.audioUri(id)?.let { local(it, host) }
            downloadedAac(id)?.let { return local(it, host) }
            val playback = MusicPlayerUtils.playerResponseForPlayback(id).getOrNull() ?: return null
            val format = CastQueuePolicy.castFormat(playback.audioFormats, playback.format) ?: return null
            val url = format.url ?: return null
            val client = StreamDenialClassifier.clientOf(url)
            val headers =
                GoogleVideoRequestPolicy.headers(client) +
                    ("User-Agent" to GoogleVideoRequestPolicy.userAgent(client, playback.usedClient.userAgent))
            return CastSource(StreamProxyServer.getInstance().registerStream(url, host, AAC, headers), AAC)
        }

        private fun local(
            uri: Uri,
            host: String,
        ): CastSource {
            val type = typeOf(uri) ?: AAC
            return CastSource(StreamProxyServer.getInstance().registerLocal(context.contentResolver, uri, host, type), type)
        }

        /** The downloaded file of [id] when it is AAC; an opus download is streamed instead, as renderers often can't play it. */
        private fun downloadedAac(id: String): Uri? {
            val path =
                runCatching { downloads.completedAudioPathBlocking(id) }.getOrNull()?.takeIf { DownloadFiles.exists(context, it) }
                    ?: return null
            val uri = if (DownloadFiles.isDocument(path)) path.toUri() else Uri.fromFile(File(path))
            return uri.takeIf { typeOf(it) in AAC_TYPES }
        }

        private fun typeOf(uri: Uri): String? =
            runCatching { context.contentResolver.getType(uri) }.getOrNull()
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(uri.toString()).lowercase())

        private fun castItem(track: MusicTrack) =
            CastItem(
                title = track.title,
                artist = track.artist,
                album = track.album,
                artUrl = track.highResThumbnailUrl.takeIf { it.startsWith("http") },
                durationSeconds = track.duration.toLong(),
                music = true,
            )

        private fun durationMs(track: MusicTrack) = track.duration * 1000L

        private fun toast(
            @StringRes message: Int,
            arg: String,
        ) {
            scope.launch(Dispatchers.Main) { Toast.makeText(context, context.getString(message, arg), Toast.LENGTH_SHORT).show() }
        }

        private companion object {
            const val TAG = "DlnaMusicDriver"
            const val AAC = "audio/mp4"
            val AAC_TYPES = setOf("audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac", "audio/mp4a-latm")
            const val RESTART_MS = 3_000L
            const val SETTLE_MS = 1_000L
            const val DRIFT_MS = 2_000L
            const val MISS_RETRY_MS = 5_000L
            const val MAX_MISSES = 3
        }
    }
