package io.github.aedev.flow.player.error

import android.content.Context
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.aedev.flow.R
import io.github.aedev.flow.player.state.EnhancedPlayerState
import io.github.aedev.flow.player.stream.ClientGateTracker
import io.github.aedev.flow.player.stream.VideoCodecUtils
import io.github.aedev.flow.player.stream.model.AudioStream
import io.github.aedev.flow.player.stream.model.VideoStream
import io.github.aedev.flow.utils.potoken.WebPoTokenSession
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Everything that happens when a stream URL is refused rather than broken: reading the cause out of
 * the failing URL, saying so, demoting the client or re-attesting, and bounding the reload loop.
 *
 * Split out of [PlayerErrorHandler] so the recovery can be reasoned about — and exercised — on its
 * own, without standing up the twenty collaborators the rest of that class needs.
 */
@UnstableApi
internal class StreamDenialRecovery(
    private val appContext: Context,
    private val stateFlow: MutableStateFlow<EnhancedPlayerState>,
    private val onPlaybackShutdown: () -> Unit,
    private val onStreamExpired: () -> Unit,
    private val onPlaybackAbandoned: () -> Unit,
    private val onGatedCodecFallback: (Long) -> Boolean,
    private val markStreamFailed: (String) -> Unit,
    private val getCurrentVideoStream: () -> VideoStream?,
    private val getCurrentAudioStream: () -> AudioStream?,
    private val setRecoveryState: () -> Unit,
    private val reloadPlaybackManager: () -> Unit,
) {
    private companion object {
        // Deliberately the old tag: these lines are what a user's diagnostics dump is read for.
        const val TAG = "PlayerErrorHandler"
        const val MAX_CONSECUTIVE_EXPIRY = 3
        const val EXPIRY_DEBOUNCE_MS = 1500L
    }

    private val expiryRetryLimiter =
        StreamExpiryRetryLimiter(
            maxConsecutiveFailures = MAX_CONSECUTIVE_EXPIRY,
            debounceMs = EXPIRY_DEBOUNCE_MS,
        )

    fun resetExpiryCounter() {
        expiryRetryLimiter.reset()
    }

    /** A 403/410 that reached the player as a generic IO error still earns the same verdict. */
    fun handleIoStreamDenial(
        isForbidden: Boolean,
        httpCode: Int,
    ) {
        val context = buildFailureContext("data-changed-in-io")
        if (isForbidden) reportDenial(context, httpCode)
        handleStreamExpired(context)
    }

    fun hasGivenUp(): Boolean = expiryRetryLimiter.hasGivenUp()

    /**
     * HTTP non-2xx response.
     *
     * 403/410 used to be reported as an expired URL unconditionally. They are not the same thing:
     * the failing URL's own `expire` and `pot` parameters say which of them happened, and the
     * recoveries differ — a passed deadline is fixed by re-minting under the same client, while an
     * enforced attestation is not fixed by re-minting at all. See [StreamDenialClassifier].
     */
    fun handleBadHttpStatus(
        error: PlaybackException,
        player: ExoPlayer?,
    ): Boolean {
        val httpCode = extractHttpStatusCode(error)
        val context = buildFailureContext("http-$httpCode", httpCode)
        val urlFragment = error.cause?.message?.take(120) ?: ""
        PlayerDiagnostics.logError(TAG, "HTTP $httpCode stream context: ${context.toLogString()}")
        PlayerDiagnostics.logError(
            TAG,
            "HTTP $httpCode error. ${StreamDenialClassifier.describeExpiry(context.url)} url_fragment=$urlFragment",
        )
        return when (httpCode) {
            403, 410 -> {
                if (onGatedCodecFallback(player?.currentPosition ?: 0L)) {
                    Log.w(TAG, "HTTP $httpCode on AV1 — switched to a compatible codec at the same resolution")
                    PlayerDiagnostics.logWarning(TAG, "AV1 stream CDN-gated (HTTP $httpCode) — switched to a compatible codec")
                    resetExpiryCounter()
                } else {
                    getCurrentVideoStream()?.content?.let { markStreamFailed(it) }
                    PlayerDiagnostics.logWarning(TAG, "Failed stream variant: ${context.toLogString()}")
                    reportDenial(context, httpCode)
                    handleStreamExpired(context)
                }
                true
            }

            404 -> {
                getCurrentVideoStream()?.content?.let { markStreamFailed(it) }
                PlayerDiagnostics.logError(TAG, "HTTP 404 — stream resource not found")
                handleStreamExpired(buildFailureContext("http-404", 404))
                true
            }

            429 -> {
                PlayerDiagnostics.logWarning(TAG, "HTTP 429 — rate limited, retrying after delay")
                setRecoveryState()
                reloadPlaybackManager()
                true
            }

            in 500..599 -> {
                PlayerDiagnostics.logWarning(TAG, "HTTP $httpCode server error — retrying")
                setRecoveryState()
                reloadPlaybackManager()
                true
            }

            else -> {
                PlayerDiagnostics.logError(TAG, "Unhandled HTTP status $httpCode")
                false
            }
        }
    }

    /**
     * Says what actually happened, and records an enforced client so the extractor's ladder can
     * step over it on the next video instead of walking into the same refusal every time.
     */
    private fun reportDenial(
        context: StreamFailureContext,
        httpCode: Int,
    ) {
        val expiry = StreamDenialClassifier.describeExpiry(context.url)
        when (context.denialKind) {
            StreamDenialKind.URL_EXPIRED -> {
                Log.w(TAG, "HTTP $httpCode — stream URL expired. Triggering extractor reload.")
                PlayerDiagnostics.logWarning(
                    TAG,
                    "stream URL expired (HTTP $httpCode, $expiry) — requesting fresh stream info",
                )
            }

            StreamDenialKind.ATTESTATION_GATED -> {
                ClientGateTracker.reportGated(context.client)
                Log.w(TAG, "HTTP $httpCode — ${context.client} gated (URL still valid, no PO Token). Demoting the client.")
                PlayerDiagnostics.logWarning(
                    TAG,
                    "stream DENIED (HTTP $httpCode, $expiry, pot=false, client=${context.client}) — " +
                        "attestation enforced, demoting this client and escalating",
                )
            }

            StreamDenialKind.TOKEN_REJECTED -> {
                WebPoTokenSession.reportTokenRejected()
                val demoted = ClientGateTracker.reportRefused(context.client)
                Log.w(TAG, "HTTP $httpCode — PO Token refused for ${context.client} (URL still valid). demoted=$demoted")
                PlayerDiagnostics.logWarning(
                    TAG,
                    "stream DENIED (HTTP $httpCode, $expiry, pot=true, client=${context.client}) — " +
                        if (demoted) "token refused twice, demoting this client" else "token refused, re-attesting",
                )
            }

            else -> {
                Log.w(TAG, "HTTP $httpCode on a URL with no readable deadline — treating as a reload.")
                PlayerDiagnostics.logWarning(TAG, "stream DENIED (HTTP $httpCode, $expiry) — cause unreadable, reloading")
            }
        }
    }

    fun handleStreamExpired(reason: String) {
        handleStreamExpired(buildFailureContext(reason))
    }

    private fun handleStreamExpired(context: StreamFailureContext) {
        when (val decision = expiryRetryLimiter.record(context)) {
            StreamExpiryRetryLimiter.Decision.AlreadyAbandoned -> {
                Log.w(TAG, "Stream expiry on an already-abandoned variant - ignoring. ${context.toLogString()}")
                return
            }

            StreamExpiryRetryLimiter.Decision.Debounced -> {
                Log.w(TAG, "Stream expiry within debounce window - coalescing into the in-flight reload. ${context.toLogString()}")
                return
            }

            is StreamExpiryRetryLimiter.Decision.GiveUp -> {
                Log.e(
                    TAG,
                    "Stream denial limit reached (${decision.attempts}/${decision.limit}) - stopping playback. ${context.toLogString()}",
                )
                PlayerDiagnostics.logError(TAG, "Giving up after ${decision.attempts} stream denials. ${context.toLogString()}")
                onPlaybackShutdown()
                stateFlow.value =
                    stateFlow.value.copy(
                        isBuffering = false,
                        isPlaying = false,
                        error = appContext.getString(R.string.error_expiring_stream_urls),
                        recoveryAttempted = true,
                    )
                onPlaybackAbandoned()
                return
            }

            is StreamExpiryRetryLimiter.Decision.Retry -> {
                Log.w(
                    TAG,
                    "Stream denied - requesting full extractor reload (attempt ${decision.attempt}/${decision.limit}). ${context.toLogString()}",
                )
            }
        }

        stateFlow.value =
            stateFlow.value.copy(
                isBuffering = true,
                error = null,
                recoveryAttempted = true,
            )
        onStreamExpired()
    }

    fun buildFailureContext(
        reason: String,
        httpCode: Int? = null,
    ): StreamFailureContext {
        val video = getCurrentVideoStream()
        val audio = getCurrentAudioStream()
        val url = video?.content
        return StreamFailureContext(
            reason = reason,
            httpCode = httpCode,
            url = url,
            denialKind = StreamDenialClassifier.classify(url),
            client = StreamDenialClassifier.clientOf(url),
            videoHeight = video?.let(VideoCodecUtils::qualityHeightFromStream),
            videoCodec = video?.let(VideoCodecUtils::codecKeyFromStream),
            videoItag =
                video?.itagItem?.id?.toString()
                    ?: runCatching { video?.id }.getOrNull()?.takeIf { !it.isNullOrBlank() },
            videoMimeType = video?.format?.mimeType,
            audioItag =
                audio?.itagItem?.id?.toString()
                    ?: runCatching { audio?.id }.getOrNull()?.takeIf { !it.isNullOrBlank() },
            audioMimeType = audio?.format?.mimeType,
        )
    }

    /**
     * Try to read the HTTP status code from the exception chain.
     */
    private fun extractHttpStatusCode(error: PlaybackException): Int {
        try {
            var cause: Throwable? = error.cause
            while (cause != null) {
                val field =
                    runCatching { cause!!.javaClass.getField("responseCode") }.getOrNull()
                        ?: runCatching { cause!!.javaClass.getDeclaredField("responseCode") }.getOrNull()
                if (field != null) {
                    field.isAccessible = true
                    return (field.get(cause) as? Int) ?: 0
                }
                cause = cause.cause
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not extract HTTP code: ${e.message}")
        }
        return 0
    }
}
