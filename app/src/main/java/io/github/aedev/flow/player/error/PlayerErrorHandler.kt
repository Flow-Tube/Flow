package io.github.aedev.flow.player.error

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import io.github.aedev.flow.R
import io.github.aedev.flow.player.config.PlayerConfig
import io.github.aedev.flow.player.state.EnhancedPlayerState
import io.github.aedev.flow.player.stream.ClientGateTracker
import io.github.aedev.flow.player.stream.VideoCodecUtils
import io.github.aedev.flow.utils.potoken.WebPoTokenSession
import kotlinx.coroutines.flow.MutableStateFlow
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Handles player errors and recovery logic.
 *
 * Covers every PlaybackException error code defined in Media3 1.4.x:
 *  - General              : UNSPECIFIED, REMOTE_ERROR, BEHIND_LIVE_WINDOW, TIMEOUT
 *  - IO                   : IO_UNSPECIFIED, IO_NETWORK_*, IO_INVALID_HTTP_CONTENT_TYPE,
 *                           IO_BAD_HTTP_STATUS (incl. 403/410 expired-URL / "YouTube changed data"),
 *                           IO_FILE_NOT_FOUND, IO_NO_PERMISSION, IO_CLEARTEXT_NOT_PERMITTED,
 *                           IO_READ_POSITION_OUT_OF_RANGE
 *  - Parsing              : PARSING_CONTAINER_*, PARSING_MANIFEST_*
 *  - Decoder/Renderer     : DECODING_FAILED, DECODER_INIT_FAILED, DECODER_QUERY_FAILED,
 *                           DECODING_FORMAT_EXCEEDS_CAPABILITIES, DECODING_FORMAT_UNSUPPORTED
 *  - Audio                : AUDIO_TRACK_INIT_FAILED, AUDIO_TRACK_WRITE_FAILED
 *  - DRM                  : all DRM_* sub-codes
 *
 * All errors are forwarded to [PlayerDiagnostics] so users can tap "Copy Logs"
 * in the error overlay and paste the report directly without needing ADB.
 */
@UnstableApi
class PlayerErrorHandler(
    private val appContext: Context,
    private val stateFlow: MutableStateFlow<EnhancedPlayerState>,
    private val onReloadStream: (Long, String) -> Unit,
    private val onQualityDowngrade: () -> Unit,
    private val onPlaybackShutdown: () -> Unit,
    private val onStreamExpired: () -> Unit,
    private val onPlaybackAbandoned: () -> Unit,
    private val onDecoderGaveUp: () -> Unit,
    private val rememberPlaybackForRecovery: () -> Unit,
    private val onGatedCodecFallback: (Long) -> Boolean,
    private val getFailedStreamUrls: () -> Set<String>,
    private val markStreamFailed: (String) -> Unit,
    private val incrementStreamErrors: () -> Unit,
    private val getStreamErrorCount: () -> Int,
    private val isAdaptiveQualityEnabled: () -> Boolean,
    private val getManualQualityHeight: () -> Int?,
    private val getCurrentVideoStream: () -> VideoStream?,
    private val getCurrentAudioStream: () -> AudioStream?,
    private val getAllAudioStreams: () -> List<AudioStream>,
    private val setCurrentAudioStream: (AudioStream) -> Unit,
    private val setRecoveryState: () -> Unit,
    private val reloadPlaybackManager: () -> Unit,
    private val isPlayingDeviceFile: () -> Boolean = { false },
) {
    companion object {
        private const val TAG = "PlayerErrorHandler"
    }

    private val denialRecovery =
        StreamDenialRecovery(
            appContext = appContext,
            stateFlow = stateFlow,
            onPlaybackShutdown = onPlaybackShutdown,
            onStreamExpired = onStreamExpired,
            onPlaybackAbandoned = onPlaybackAbandoned,
            onGatedCodecFallback = onGatedCodecFallback,
            markStreamFailed = markStreamFailed,
            getCurrentVideoStream = getCurrentVideoStream,
            getCurrentAudioStream = getCurrentAudioStream,
            setRecoveryState = setRecoveryState,
            reloadPlaybackManager = reloadPlaybackManager,
        )

    private val decoderRecovery = VideoDecoderRecoveryPolicy()

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Handle player errors from ExoPlayer.
     * Returns true if the error was handled gracefully (no user notification needed).
     */
    fun handleError(
        error: PlaybackException,
        player: ExoPlayer?,
    ): Boolean {
        Log.e(TAG, "ExoPlayer - onPlayerError() called with:", error)
        PlayerDiagnostics.logPlaybackError(TAG, error)

        saveStreamProgressState(player)
        var isCatchableException = false

        when (error.errorCode) {
            // ── Live window ────────────────────────────────────────────────
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                isCatchableException = true
                handleBehindLiveWindow(player)
            }

            PlaybackException.ERROR_CODE_TIMEOUT -> {
                PlayerDiagnostics.logWarning(TAG, "Playback timeout — attempting reload")
                setRecoveryState()
                reloadPlaybackManager()
                return true
            }

            PlaybackException.ERROR_CODE_REMOTE_ERROR -> {
                PlayerDiagnostics.logError(TAG, "Remote playback error: ${error.message}")
                stateFlow.value =
                    stateFlow.value.copy(
                        error = appContext.getString(R.string.error_remote_playback),
                        isPlaying = false,
                    )
            }

            // ── IO errors ─────────────────────────────────────────────────

            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                isCatchableException = denialRecovery.handleBadHttpStatus(error, player)
                if (isCatchableException) return true
            }

            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> {
                PlayerDiagnostics.logWarning(TAG, "Invalid HTTP content type — stream URL may be stale")
                denialRecovery.handleStreamExpired("invalid-content-type")
                return true
            }

            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> {
                if (!isPlayingDeviceFile()) {
                    PlayerDiagnostics.logError(TAG, "Stream resource not found — URL may have expired")
                    denialRecovery.handleStreamExpired("file-not-found")
                    return true
                }
                // A file that is gone stays gone; reloading it as an expired stream never ends.
                PlayerDiagnostics.logError(TAG, "Local file not found")
                stateFlow.value =
                    stateFlow.value.copy(
                        error = appContext.getString(R.string.error_local_file_missing),
                        isPlaying = false,
                        isBuffering = false,
                    )
            }

            PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> {
                PlayerDiagnostics.logError(TAG, "IO permission denied for stream URL")
                stateFlow.value =
                    stateFlow.value.copy(
                        error = appContext.getString(R.string.error_playback_permission_denied),
                        isPlaying = false,
                    )
            }

            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> {
                PlayerDiagnostics.logError(TAG, "Cleartext HTTP not permitted — stream uses plain http://")
                stateFlow.value =
                    stateFlow.value.copy(
                        error = appContext.getString(R.string.error_insecure_stream_blocked),
                        isPlaying = false,
                    )
            }

            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> {
                PlayerDiagnostics.logWarning(TAG, "Read position out of range — seeking to safe position")
                player?.let { it.seekTo(maxOf(0L, it.currentPosition - 5000L)) }
                setRecoveryState()
                reloadPlaybackManager()
                return true
            }

            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_UNSPECIFIED,
            -> {
                handleNetworkError(error)
            }

            // ── Parsing / manifest ────────────────────────────────────────

            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            -> {
                handleParsingError(error)
            }

            // ── Decoder / renderer ────────────────────────────────────────

            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> {
                PlayerDiagnostics.logWarning(TAG, "Format exceeds device capabilities — downgrading quality")
                getCurrentVideoStream()?.getContent()?.let { markStreamFailed(it) }
                onQualityDowngrade()
                return true
            }

            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> {
                PlayerDiagnostics.logWarning(TAG, "Decoding format unsupported on this device — downgrading")
                getCurrentVideoStream()?.getContent()?.let { markStreamFailed(it) }
                onQualityDowngrade()
                return true
            }

            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            -> {
                if (handleDecoderError(error, player)) return true
            }

            // ── DRM ────────────────────────────────────────────────────────

            PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
            PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED,
            PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
            PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
            PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
            PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
            -> {
                handleDrmError(error)
            }

            else -> {
                // Covers any future/unknown error codes
                handleUnknownError(error)
            }
        }

        if (!isCatchableException) {
            createErrorNotification(error)
        }

        return isCatchableException
    }

    // ── Specific handlers ────────────────────────────────────────────────────

    private fun handleBehindLiveWindow(player: ExoPlayer?) {
        PlayerDiagnostics.logInfo(TAG, "Behind live window — seeking to live edge and re-preparing")
        player?.seekToDefaultPosition()
        player?.prepare()
        stateFlow.value = stateFlow.value.copy(isBuffering = true, error = null)
    }

    fun resetForNewVideo() {
        denialRecovery.resetExpiryCounter()
        decoderRecovery.reset()
    }

    fun onFirstFrameRendered() {
        decoderRecovery.onFirstFrameRendered()
    }

    fun hasGivenUp(): Boolean = denialRecovery.hasGivenUp()

    private fun handleParsingError(error: PlaybackException) {
        Log.e(TAG, "Source validation error: ${error.errorCode} - ${error.message}")
        PlayerDiagnostics.logError(TAG, "Parsing error (${error.errorCode}): ${error.message}", error.cause)

        val errorMessage = error.message ?: ""
        val causeMessage = error.cause?.message ?: ""
        val fullErrorInfo = "$errorMessage $causeMessage"

        // UnrecognizedInputFormatException → mark stream and try alternative
        if (fullErrorInfo.contains("UnrecognizedInputFormatException", ignoreCase = true) ||
            error.cause?.javaClass?.simpleName == "UnrecognizedInputFormatException"
        ) {
            Log.w(TAG, "Unrecognized format error — trying alternative stream format")
            getCurrentVideoStream()?.getContent()?.let { markStreamFailed(it) }
            onQualityDowngrade()
            return
        }

        // NAL corruption / ParserException
        val videoContent = getCurrentVideoStream()?.getContent()
        if ((
                fullErrorInfo.contains("NAL", ignoreCase = true) ||
                    error.cause is androidx.media3.common.ParserException
            ) &&
            videoContent != null
        ) {
            markStreamFailed(videoContent)
            incrementStreamErrors()
            Log.w(TAG, "Corrupted stream (NAL/Parser): $videoContent — error count: ${getStreamErrorCount()}")
            if (getStreamErrorCount() >= PlayerConfig.MAX_STREAM_ERRORS) {
                if (isAdaptiveQualityEnabled()) {
                    onQualityDowngrade()
                } else {
                    onReloadStream(0L, "manual-quality-parser-error")
                }
                return
            }
        }

        setRecoveryState()
        reloadPlaybackManager()
    }

    private fun handleNetworkError(error: PlaybackException) {
        val errorMessage = error.message ?: ""
        val causeMessage = error.cause?.message ?: ""
        val fullErrorInfo = "$errorMessage $causeMessage"

        PlayerDiagnostics.logError(
            TAG,
            "Network/IO error (${error.errorCode}): ${errorMessage.take(120)}",
            error.cause,
        )

        // Parser error mis-classified as IO error
        if (fullErrorInfo.contains("NAL", ignoreCase = true) ||
            fullErrorInfo.contains("ParserException", ignoreCase = true) ||
            error.cause is androidx.media3.common.ParserException
        ) {
            Log.e(TAG, "Parser error in IO path: $fullErrorInfo")
            val videoContent = getCurrentVideoStream()?.getContent()
            if (videoContent != null) {
                markStreamFailed(videoContent)
                incrementStreamErrors()
                if (getStreamErrorCount() >= PlayerConfig.MAX_STREAM_ERRORS) {
                    if (isAdaptiveQualityEnabled()) {
                        onQualityDowngrade()
                    } else {
                        onReloadStream(0L, "manual-quality-parser-io")
                    }
                    return
                }
            }
            setRecoveryState()
            reloadPlaybackManager()
            return
        }

        // YouTube "data changed" keyword in error message
        if (fullErrorInfo.contains("YouTube data changed", ignoreCase = true) ||
            fullErrorInfo.contains("data changed", ignoreCase = true) ||
            (fullErrorInfo.contains("403") || fullErrorInfo.contains("410"))
        ) {
            PlayerDiagnostics.logWarning(TAG, "YouTube data-changed pattern detected in IO error — triggering extractor reload")
            // A 403 that arrives as a generic IO error is the same refusal, so it earns the same
            // verdict — otherwise the client that was just enforced escapes demotion on this path.
            denialRecovery.handleIoStreamDenial(
                isForbidden = fullErrorInfo.contains("403") || fullErrorInfo.contains("410"),
                httpCode = if (fullErrorInfo.contains("410")) 410 else 403,
            )
            return
        }

        // Standard network error
        Log.w(TAG, "Network error (${error.errorCode}): ${error.message}")
        val videoContent = getCurrentVideoStream()?.getContent()
        if (videoContent != null) {
            incrementStreamErrors()
            if (getStreamErrorCount() >= PlayerConfig.MAX_STREAM_ERRORS) {
                if (isAdaptiveQualityEnabled()) {
                    markStreamFailed(videoContent)
                    onQualityDowngrade()
                } else {
                    onReloadStream(0L, "manual-quality-network")
                }
                return
            }
        }

        setRecoveryState()
        reloadPlaybackManager()
    }

    /** Returns true when the error was recovered in place and needs no notification. */
    private fun handleDecoderError(
        error: PlaybackException,
        player: ExoPlayer?,
    ): Boolean {
        Log.e(TAG, "Decoder/renderer error: ${error.errorCode}")
        PlayerDiagnostics.logError(TAG, "Decoder error (${error.errorCode}): ${error.message}", error.cause)

        val isAudioError =
            error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ||
                error.message?.contains("AudioRenderer", ignoreCase = true) == true

        if (player != null &&
            decoderRecovery.onDecoderError(error.errorCode, error.isFormatHandled()) ==
            VideoDecoderRecoveryPolicy.Decision.REPREPARE
        ) {
            Log.w(TAG, "Decoder error — re-preparing in place at ${player.currentPosition}ms")
            PlayerDiagnostics.logWarning(TAG, "Decoder failed (${error.errorCode}); re-preparing in place")
            setRecoveryState()
            player.prepare()
            return true
        }

        val failedAudio = getCurrentAudioStream()?.takeIf { it.getContent() != null }
        if (isAudioError && failedAudio != null) {
            markStreamFailed(failedAudio.getContent())
            val alternativeAudio = AudioRecoveryPick.pick(getAllAudioStreams(), failedAudio, getFailedStreamUrls())
            if (alternativeAudio != null) {
                setCurrentAudioStream(alternativeAudio)
                Log.d(TAG, "Switching to alternative audio: ${alternativeAudio.format?.mimeType}")
                PlayerDiagnostics.logInfo(TAG, "Switched to backup audio track: ${alternativeAudio.format?.mimeType}")
                setRecoveryState()
                reloadPlaybackManager()
                return false
            }
        }

        Log.e(TAG, "Decoder error — no alternatives available, stopping playback")
        // A device fault, not a broken stream: keep the place so Play, Retry and a quality change resume it.
        rememberPlaybackForRecovery()
        onPlaybackShutdown()
        stateFlow.value =
            stateFlow.value.copy(
                error = appContext.getString(R.string.error_playback_device, error.message.orEmpty()),
                isPlaying = false,
            )
        onDecoderGaveUp()
        return false
    }

    private fun PlaybackException.isFormatHandled(): Boolean {
        val exo = this as? ExoPlaybackException ?: return true
        return exo.type != ExoPlaybackException.TYPE_RENDERER || exo.rendererFormatSupport == C.FORMAT_HANDLED
    }

    private fun handleDrmError(error: PlaybackException) {
        Log.e(TAG, "DRM error: ${error.errorCode}")
        PlayerDiagnostics.logError(TAG, "DRM error (${error.errorCode}): ${error.message}")
        onPlaybackShutdown()
        stateFlow.value =
            stateFlow.value.copy(
                error = appContext.getString(R.string.error_content_protection, error.message.orEmpty()),
                isPlaying = false,
            )
    }

    private fun handleUnknownError(error: PlaybackException) {
        Log.w(TAG, "Unhandled error code ${error.errorCode} — attempting generic recovery: ${error.message}")
        PlayerDiagnostics.logError(
            TAG,
            "Unknown error code=${error.errorCode} msg=${error.message?.take(120)}",
            error.cause,
        )
        setRecoveryState()
        reloadPlaybackManager()
    }

    // ── State helpers ────────────────────────────────────────────────────────

    private fun saveStreamProgressState(player: ExoPlayer?) {
        player?.let {
            PlayerDiagnostics.log(TAG, "Saving progress: position=${it.currentPosition}ms state=${it.playbackState}")
        }
    }

    private fun createErrorNotification(error: PlaybackException) {
        Log.e(TAG, "Player error (notify): ${error.message}")
    }

    // ── Public utility methods ────────────────────────────────────────────────

    fun setRecovery() {
        Log.d(TAG, "Setting recovery state")
        PlayerDiagnostics.logInfo(TAG, "Recovery state set")
        stateFlow.value =
            stateFlow.value.copy(
                isBuffering = true,
                error = null,
                recoveryAttempted = true,
            )
    }

    fun handlePlaybackShutdown(player: ExoPlayer?) {
        Log.w(TAG, "Playback shutdown initiated")
        PlayerDiagnostics.logWarning(TAG, "Playback shutdown initiated")
        try {
            player?.stop()
            player?.clearMediaItems()
            stateFlow.value =
                stateFlow.value.copy(
                    isPlaying = false,
                    isBuffering = false,
                    error = appContext.getString(R.string.error_playback_stopped),
                )
        } catch (e: Exception) {
            Log.e(TAG, "Error during playback shutdown", e)
        }
    }

    /**
     * Called by [PlaybackRefocusEffect] when the player is stuck with duration=0
     * after a screen-off/on cycle.
     * Returns true if a recovery action was taken.
     */
    fun handleRefocusStuck(
        player: ExoPlayer?,
        videoId: String?,
    ): Boolean {
        val pbState = player?.playbackState ?: return false
        val duration = player.duration
        val position = player.currentPosition

        PlayerDiagnostics.logRefocusGlitch(
            TAG,
            "videoId=$videoId pbState=$pbState dur=$duration pos=$position " +
                "playWhenReady=${player.playWhenReady} isPlaying=${player.isPlaying}",
        )

        return when {
            pbState == Player.STATE_IDLE && videoId != null -> {
                Log.w(TAG, "Refocus: player IDLE — calling prepare()")
                PlayerDiagnostics.logWarning(TAG, "Refocus recovery: player IDLE → prepare()")
                player.prepare()
                if (player.playWhenReady) player.play()
                true
            }

            pbState == Player.STATE_READY && duration <= 0L && position < 1000L -> {
                Log.w(TAG, "Refocus: ghost READY state (dur=$duration, pos=$position) — requesting extractor reload")
                PlayerDiagnostics.logWarning(TAG, "Refocus: ghost READY state → extractor reload")
                onStreamExpired()
                true
            }

            pbState == Player.STATE_ENDED && position < 5000L -> {
                Log.w(TAG, "Refocus: false STATE_ENDED at pos=$position — seek(0) + play()")
                PlayerDiagnostics.logWarning(TAG, "Refocus: false STATE_ENDED → seek(0) + play()")
                player.seekTo(0)
                player.prepare()
                player.play()
                true
            }

            else -> {
                false
            }
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────
}
