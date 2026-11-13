package io.github.aedev.flow.player

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import io.github.aedev.flow.player.error.PlayerDiagnostics

private const val TAG = "MusicPause"
private const val CAUSE_WINDOW_MS = 2_000L

/**
 * Says in the diagnostics report why the music stopped playing. A pause from a headset button, an
 * audio focus loss, a Bluetooth drop or Flow's own mute pause all look the same to the user: the
 * music just stops, and none of them is an error, so nothing else records them (#1973).
 */
@UnstableApi
internal class MusicPauseLog(
    private val player: Player,
) : AnalyticsListener {
    private var cause: String? = null
    private var causeAtMs = 0L

    /**
     * Who asked for the pause about to happen: a media button, a controller's package, or one of
     * Flow's own reasons. A media button arrives first and then as a command from Flow's own session,
     * so a later, vaguer cause doesn't replace one noted a moment before unless [replace] says so.
     */
    fun noteCause(
        cause: String,
        replace: Boolean = true,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (!replace && this.cause != null && now - causeAtMs <= CAUSE_WINDOW_MS) return
        this.cause = cause
        causeAtMs = now
    }

    override fun onPlayWhenReadyChanged(
        eventTime: AnalyticsListener.EventTime,
        playWhenReady: Boolean,
        reason: Int,
    ) {
        val by = cause?.takeIf { SystemClock.elapsedRealtime() - causeAtMs <= CAUSE_WINDOW_MS }
        cause = null
        if (playWhenReady) return
        val sender = by?.let { " by $it" }.orEmpty()
        warn("music ${player.currentMediaItem?.mediaId} paused at ${player.currentPosition}ms: ${playWhenReadyReasonName(reason)}$sender")
    }

    override fun onPlaybackSuppressionReasonChanged(
        eventTime: AnalyticsListener.EventTime,
        playbackSuppressionReason: Int,
    ) {
        if (playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE) return
        warn("music ${player.currentMediaItem?.mediaId} held silent: ${suppressionReasonName(playbackSuppressionReason)}")
    }

    private fun warn(message: String) {
        Log.w(TAG, message)
        PlayerDiagnostics.logWarning(TAG, message)
    }
}

internal fun playWhenReadyReasonName(reason: Int): String =
    when (reason) {
        Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "user request"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "audio focus lost"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "audio output disconnected"
        Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "remote player"
        Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "end of song"
        Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> "silenced too long"
        else -> "reason $reason"
    }

internal fun suppressionReasonName(reason: Int): String =
    when (reason) {
        Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS -> "audio focus lost for a moment"
        Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT -> "unsuitable audio output"
        Player.PLAYBACK_SUPPRESSION_REASON_SCRUBBING -> "scrubbing"
        else -> "reason $reason"
    }
