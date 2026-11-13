package io.github.aedev.flow.player

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.github.aedev.flow.player.error.PlayerDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "SleepTimer"

/**
 * Shared sleep timer for both music and video playback.
 *
 * Usage:
 *   1. Call [attachToPlayer] when the player becomes active, passing the [Player] instance
 *      and a lambda that invokes the correct pause action for that player.
 *   2. Call [start] with a minute count, or [startEndOfMedia] for end-of-song mode.
 *   3. Call [cancel] to clear all state.
 *   4. Observe [isActive], [pauseAtEndOfMedia], and [triggerTimeMs] in the UI.
 */
object SleepTimerManager {
    // ── Compose-observable state ──────────────────────────────────────────────

    var isActive by mutableStateOf(false)
        private set

    /** True when the timer should fire when the current media item finishes. */
    var pauseAtEndOfMedia by mutableStateOf(false)
        private set

    /** True when the timer should close the app instead of pausing playback. */
    var closeAppOnExpiry by mutableStateOf(false)
        private set

    /** Remembered default for future sleep timers. */
    var preferredCloseAppOnExpiry by mutableStateOf(false)
        private set

    /**
     * Epoch-millisecond timestamp at which the player will be paused.
     * -1 when no countdown is running.
     */
    var triggerTimeMs by mutableLongStateOf(-1L)
        private set

    // ── Internal state ────────────────────────────────────────────────────────

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var timerJob: Job? = null
    private var pauseCallback: (() -> Unit)? = null
    private var exitCallback: (() -> Unit)? = null
    private var currentPlayer: Player? = null

    private val playerListener =
        object : Player.Listener {
            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int,
            ) {
                if (pauseAtEndOfMedia && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    firePause()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && pauseAtEndOfMedia) {
                    firePause()
                }
            }
        }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Attach a player to receive end-of-media events.
     * Must be called whenever the active player changes.
     *
     * @param player   The Media3 [Player] for listening to playback events.
     * @param pauseFn  Lambda that pauses the correct player (music or video).
     */
    fun attachToPlayer(
        player: Player?,
        pauseFn: () -> Unit,
    ) {
        currentPlayer?.removeListener(playerListener)
        currentPlayer = player
        pauseCallback = pauseFn
        player?.addListener(playerListener)
    }

    /** Detach the current player without cancelling the timer. */
    fun detachPlayer() {
        currentPlayer?.removeListener(playerListener)
        currentPlayer = null
    }

    /** Register the callback invoked instead of pausing when [closeAppOnExpiry] is true; the app sets it once. */
    fun attachExitCallback(fn: () -> Unit) {
        exitCallback = fn
    }

    /**
     * Start a countdown timer.
     *
     * @param minutes  Duration in minutes, must be > 0.
     */
    fun start(
        minutes: Int,
        closeApp: Boolean = false,
    ) {
        require(minutes > 0) { "minutes must be positive" }
        clearState()
        closeAppOnExpiry = closeApp
        triggerTimeMs = System.currentTimeMillis() + minutes * 60_000L
        isActive = true
        timerJob =
            scope.launch {
                delay(minutes * 60_000L)
                firePause()
            }
    }

    /** Start end-of-media mode — player pauses (or closes the app) when the current item ends. */
    fun startEndOfMedia(closeApp: Boolean = false) {
        clearState()
        closeAppOnExpiry = closeApp
        pauseAtEndOfMedia = true
        isActive = true
    }

    fun updatePreferredCloseAppOnExpiry(enabled: Boolean) {
        preferredCloseAppOnExpiry = enabled
    }

    /** Cancel the timer and reset all state. */
    fun cancel() {
        clearState()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun firePause() {
        val message = if (closeAppOnExpiry) "sleep timer closed the app" else "sleep timer paused playback"
        Log.w(TAG, message)
        PlayerDiagnostics.logWarning(TAG, message)
        if (closeAppOnExpiry) exitCallback?.invoke() else pauseCallback?.invoke()
        cancel()
    }

    private fun clearState() {
        timerJob?.cancel()
        timerJob = null
        pauseAtEndOfMedia = false
        closeAppOnExpiry = false
        triggerTimeMs = -1L
        isActive = false
    }
}
