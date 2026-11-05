package io.github.aedev.flow.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import io.github.aedev.flow.data.localmedia.LocalLyricsReader
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.localmedia.lrcOffsetMs
import io.github.aedev.flow.data.lyrics.LyricsEntry
import io.github.aedev.flow.data.lyrics.LyricsHelper
import io.github.aedev.flow.data.lyrics.LyricsUtils
import io.github.aedev.flow.data.lyrics.ShownLyrics
import io.github.aedev.flow.player.PlaybackTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the music session's display title on the current lyric line. It waits for each next line
 * rather than polling, and stops with playback or, unless a car is showing it, with the screen.
 * Main thread only.
 */
internal class SessionLyrics(
    private val context: Context,
    private val player: Player,
    private val sessionPlayer: SessionPlayer,
    private val scope: CoroutineScope,
    private val lyricsHelper: LyricsHelper,
    private val localLyrics: LocalLyricsReader,
    lockScreenSetting: Flow<Boolean>,
) {
    private class Fetched(
        val videoId: String,
        val entries: List<LyricsEntry>,
        val offsetMs: Long,
    )

    private var fetched: Fetched? = null
    private var shown: ShownLyrics? = null
    private var timeline: SessionLyricTimeline? = null
    private var pendingLine: Job? = null

    private var isEnabled = false

    private val listener =
        object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = update()

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = update()

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) = update()

            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int,
            ) = rebuild()
        }

    private val screenReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) = update()
        }

    init {
        scope.launch { PlaybackTarget.followed.collect { update() } }
        scope.launch { lyricsHelper.shown.collect(::setShown) }
        scope.launch {
            lockScreenSetting.collect { enabled ->
                setEnabled(enabled)
                if (enabled) player.currentMediaItem?.let(::load)
            }
        }
    }

    /** Warms the lyrics cache for the player screen, and keeps the same lyrics for the session. */
    fun load(item: MediaItem) {
        val videoId = item.mediaId
        if (videoId.isBlank()) return
        if (LocalMediaIds.isLocal(videoId)) {
            if (isEnabled) loadDeviceLyrics(videoId)
            return
        }
        val title = item.mediaMetadata.title?.toString()
        val artist = item.mediaMetadata.artist?.toString()
        if (title.isNullOrBlank() || artist.isNullOrBlank()) return
        loadOffMain {
            val durationSeconds = ((item.mediaMetadata.durationMs ?: 0L) / MILLIS_PER_SECOND).toInt()
            val entries = lyricsHelper.getLyrics(videoId, title, artist, durationSeconds, null)?.first.orEmpty()
            Fetched(videoId, entries.takeIf(lyricsHelper::entriesAreSynced).orEmpty(), offsetMs = 0L)
        }
    }

    private fun loadDeviceLyrics(videoId: String) {
        loadOffMain {
            val text = localLyrics.read(videoId)?.text ?: return@loadOffMain null
            val entries = LyricsUtils.parseLyrics(text).takeIf(lyricsHelper::entriesAreSynced).orEmpty()
            Fetched(videoId, entries, lrcOffsetMs(text))
        }
    }

    private fun loadOffMain(read: suspend () -> Fetched?) {
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { read() } ?: return@launch
                fetched = result
                rebuild()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Lyrics load failed: ${e.message}")
            }
        }
    }

    private fun setEnabled(value: Boolean) {
        if (isEnabled == value) return
        isEnabled = value
        if (value) {
            player.addListener(listener)
            val filter = IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) }
            ContextCompat.registerReceiver(context, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            rebuild()
        } else {
            stopListening()
            update()
        }
    }

    /** What the player screen is showing, which wins so a chosen source or a nudged offset applies here too. */
    private fun setShown(value: ShownLyrics?) {
        shown = value
        rebuild()
    }

    /** Re-reads whether a car is connected, which keeps the line moving with the screen off. */
    fun carChanged() = update()

    fun release() {
        if (isEnabled) stopListening()
        isEnabled = false
        pendingLine?.cancel()
    }

    private fun stopListening() {
        player.removeListener(listener)
        runCatching { context.unregisterReceiver(screenReceiver) }
    }

    private fun rebuild() {
        val videoId = player.currentMediaItem?.mediaId
        val onScreen = shown?.takeIf { it.videoId == videoId }
        val source = fetched?.takeIf { it.videoId == videoId }
        val entries = onScreen?.entries?.takeIf { it.isNotEmpty() } ?: source?.entries.orEmpty()
        timeline = if (entries.isEmpty()) null else SessionLyricTimeline(entries, onScreen?.syncOffsetMs ?: source?.offsetMs ?: 0L)
        update()
    }

    private fun update() {
        pendingLine?.cancel()
        val current = timeline
        if (!isEnabled || current == null || PlaybackTarget.followed.value != null || !anyoneCanSee()) {
            sessionPlayer.setLyricLine(null)
            return
        }
        val position = player.currentPosition
        sessionPlayer.setLyricLine(current.lineAt(position))
        if (!player.isPlaying) return
        val next = current.nextChangeAfter(position) ?: return
        val waitMs = ((next - position) / player.playbackParameters.speed).toLong().coerceAtLeast(MIN_WAIT_MS)
        pendingLine =
            scope.launch {
                delay(waitMs)
                update()
            }
    }

    private fun anyoneCanSee(): Boolean =
        MusicServiceState.isCarConnected || context.getSystemService(PowerManager::class.java)?.isInteractive != false

    private companion object {
        const val TAG = "SessionLyrics"
        const val MIN_WAIT_MS = 50L
        const val MILLIS_PER_SECOND = 1000L
    }
}
