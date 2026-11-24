package io.github.aedev.flow.service

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.player.musicAudioUri
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Puts the start of the next song in the music cache once the current one is playing, so Next has it
 * to start on (#2182). Media3's own preload of the next song waits until the current song stops
 * loading, which with the whole song loaded (and again after every seek) is often after the press, and
 * when let run beside it, it takes about a megabyte at a time and starves the song playing on a slow
 * network. This reads a few seconds' worth, once per song, through the player's own data source, so the
 * stream is resolved and the bytes sit under the key the session reads.
 */
@OptIn(UnstableApi::class)
internal class NextSongWarmup(
    private val dataSources: DataSource.Factory,
    private val scope: CoroutineScope,
    private val savesData: () -> Boolean,
    private val dispatcher: CoroutineDispatcher = PerformanceDispatcher.networkIO,
) {
    private var job: Job? = null
    private var videoId: String? = null

    private val listener =
        object : Player.Listener {
            override fun onEvents(
                player: Player,
                events: Player.Events,
            ) {
                val changed =
                    events.containsAny(
                        Player.EVENT_PLAYBACK_STATE_CHANGED,
                        Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_TIMELINE_CHANGED,
                    )
                if (!changed || player.playbackState != Player.STATE_READY) return
                val next = player.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: return
                warm(player.getMediaItemAt(next).mediaId)
            }
        }

    fun attach(player: Player) = player.addListener(listener)

    /** Reads the head of [videoId] unless it was the last song read. Called on the main thread. */
    fun warm(videoId: String) {
        if (videoId.isBlank() || LocalMediaIds.isLocal(videoId) || savesData()) return
        if (this.videoId == videoId) return
        job?.cancel()
        this.videoId = videoId
        job =
            scope.launch(dispatcher) {
                try {
                    readHead(videoId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.d(TAG, "warm-up of $videoId stopped: ${e.javaClass.simpleName}")
                }
            }
    }

    private suspend fun readHead(videoId: String) {
        val source = dataSources.createDataSource()
        val spec =
            DataSpec
                .Builder()
                .setUri(musicAudioUri(videoId))
                .setKey(videoId)
                .setPosition(0)
                .setLength(HEAD_BYTES)
                .build()
        try {
            source.open(spec)
            val buffer = ByteArray(READ_BYTES)
            var read = 0L
            while (read < HEAD_BYTES) {
                coroutineContext.ensureActive()
                val count = source.read(buffer, 0, buffer.size)
                if (count == C.RESULT_END_OF_INPUT) break
                read += count
            }
        } finally {
            source.close()
        }
    }

    private companion object {
        const val TAG = "NextSongWarmup"

        // About six seconds of a 160 kbps stream: enough for the session to start on while it loads the rest.
        const val HEAD_BYTES = 128L * 1024
        const val READ_BYTES = 16 * 1024
    }
}
