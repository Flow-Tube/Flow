package io.github.aedev.flow.widget.nowplaying

import android.content.Context
import android.os.SystemClock
import androidx.glance.appwidget.updateAll
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.webArtworkUrl
import io.github.aedev.flow.widget.core.state.NowPlayingSnapshot
import io.github.aedev.flow.widget.core.state.clearNowPlayingSnapshot
import io.github.aedev.flow.widget.core.state.markNowPlayingStopped
import io.github.aedev.flow.widget.core.state.nowPlayingSnapshotFlow
import io.github.aedev.flow.widget.core.state.writeNowPlayingSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the music session to the Now Playing widget: captures a snapshot on player
 * events (event-driven — never polled) and pushes it to the widget's DataStore.
 */
@Singleton
class NowPlayingWidgetPublisher
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var publishJob: Job? = null
        private val progressTicker = NowPlayingProgressTicker(context, scope)

        /** Must be called on the player's application thread (service listener callbacks are). */
        @OptIn(UnstableApi::class)
        fun publish(player: Player) {
            hasPublished = true
            val item = player.currentMediaItem
            val snapshot =
                item?.let {
                    NowPlayingSnapshot(
                        mediaId = it.mediaId,
                        title =
                            it.mediaMetadata.title
                                ?.toString()
                                .orEmpty(),
                        artist =
                            it.mediaMetadata.artist
                                ?.toString()
                                .orEmpty(),
                        artworkUrl = it.mediaMetadata.webArtworkUrl,
                        // Buffering still shows pause, matching the notification's own button.
                        isPlaying = !Util.shouldShowPlayButton(player),
                        isLiked = EnhancedMusicPlayerManager.isLiked.value,
                        positionMs = player.currentPosition.coerceAtLeast(0L),
                        durationMs = player.duration.takeIf { d -> d != C.TIME_UNSET } ?: 0L,
                        capturedAtElapsedMs = SystemClock.elapsedRealtime(),
                        speed = player.playbackParameters.speed,
                    )
                }
            publishJob?.cancel()
            publishJob =
                scope.launch {
                    // Debounce bursts (transition + state + isPlaying often fire together)
                    delay(150)
                    if (snapshot == null) context.clearNowPlayingSnapshot() else context.writeNowPlayingSnapshot(snapshot)
                    updatePlayerWidgets()
                    progressTicker.follow(snapshot)
                }
        }

        /** Service is going away — keep the last track on the widget, but shown paused. */
        fun publishStopped() {
            publishJob?.cancel()
            progressTicker.follow(null)
            publishJob =
                scope.launch {
                    context.markNowPlayingStopped()
                    updatePlayerWidgets()
                }
        }

        companion object {
            /**
             * Whether this process has published. A snapshot that says "playing" but was written by an
             * earlier process outlived a kill that skipped onDestroy, so the widget shows it paused.
             */
            @Volatile
            var hasPublished = false
                private set
        }

        /**
         * Called at app start: a widget left "playing" by a killed process keeps its clock running in the
         * launcher until something re-renders it, so it is re-rendered paused once.
         */
        fun repairStalePlayback() {
            scope.launch {
                val stored = context.nowPlayingSnapshotFlow { true }.first() ?: return@launch
                if (!stored.isPlaying || hasPublished) return@launch
                context.markNowPlayingStopped()
                updatePlayerWidgets()
            }
        }

        private suspend fun updatePlayerWidgets() {
            NowPlayingWidget().updateAll(context)
            io.github.aedev.flow.widget.turntable
                .TurntableWidget()
                .updateAll(context)
        }
    }
