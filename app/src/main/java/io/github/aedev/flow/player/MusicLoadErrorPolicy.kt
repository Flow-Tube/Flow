package io.github.aedev.flow.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import io.github.aedev.flow.player.stream.PlaybackBlock
import io.github.aedev.flow.player.stream.PlaybackBlockedException
import io.github.aedev.flow.player.stream.VideoUnavailableException
import io.github.aedev.flow.utils.MusicResolveTimeoutException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.math.min

/**
 * The music player waits out a dropped connection instead of failing: it keeps buffering and
 * resumes by itself for a few minutes. A failed player releases its decoder, and on some vendor
 * builds a decoder released right after the stall's flush aborts the process inside the platform.
 * A resolve that already walked every client, ran out of time doing so, or was refused to this
 * viewer (country, rate limit, membership) is not retried here: a load retry would only repeat the
 * whole ladder, and the service decides what happens next.
 * Every other error is retried as Media3 does by default.
 */
@UnstableApi
internal class MusicLoadErrorPolicy(
    private val isOffline: () -> Boolean,
) : DefaultLoadErrorHandlingPolicy(MAX_CONNECTION_RETRIES) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val error = loadErrorInfo.exception
        if (isUnretryableResolve(error)) return C.TIME_UNSET
        val connectionLost = countsAsLostConnection(error, loadErrorInfo.loadEventInfo.uri.scheme, isOffline)
        return retryDelayMs(loadErrorInfo.errorCount, connectionLost) ?: super.getRetryDelayMsFor(loadErrorInfo)
    }

    companion object {
        const val MAX_CONNECTION_RETRIES = 60
        private const val RETRY_STEP_MS = 1_000L
        private const val MAX_RETRY_DELAY_MS = 5_000L

        /**
         * The wait before retry [errorCount], [C.TIME_UNSET] to give up, or null to leave the
         * decision to Media3's default.
         */
        fun retryDelayMs(
            errorCount: Int,
            connectionLost: Boolean,
        ): Long? =
            when {
                connectionLost && errorCount <= MAX_CONNECTION_RETRIES -> min(errorCount * RETRY_STEP_MS, MAX_RETRY_DELAY_MS)

                connectionLost -> C.TIME_UNSET

                // The raised retry count is for lost connections only; anything else gives up as before.
                errorCount > DefaultLoadErrorHandlingPolicy.DEFAULT_MIN_LOADABLE_RETRY_COUNT -> C.TIME_UNSET

                else -> null
            }

        fun isUnavailable(error: Throwable): Boolean = causes(error).any { it is VideoUnavailableException }

        fun isUnretryableResolve(error: Throwable): Boolean =
            viewerBlockOf(error) != null ||
                causes(error).any { it is VideoUnavailableException || it is MusicResolveTimeoutException }

        /** Why YouTube refused this viewer the song, when no retry can change the answer. */
        fun viewerBlockOf(error: Throwable): PlaybackBlock? =
            causes(error)
                .filterIsInstance<PlaybackBlockedException>()
                .firstOrNull()
                ?.block
                ?.takeIf { it in ViewerBlocks }

        /**
         * Offline, any read error of a streamed song is the dropped connection. A file on the device
         * does not come back with the network, so it fails as any other error would.
         */
        fun countsAsLostConnection(
            error: Throwable,
            uriScheme: String?,
            isOffline: () -> Boolean,
        ): Boolean = isConnectionLoss(error) || (error is IOException && uriScheme !in DeviceSchemes && isOffline())

        fun isConnectionLoss(error: Throwable): Boolean =
            causes(error).any {
                it is UnknownHostException ||
                    it is ConnectException ||
                    it is NoRouteToHostException ||
                    it is SocketTimeoutException ||
                    it is SocketException
            }

        private fun causes(error: Throwable): Sequence<Throwable> = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH)

        private const val MAX_CAUSE_DEPTH = 8

        private val DeviceSchemes = setOf("file", "content", "android.resource")

        private val ViewerBlocks = setOf(PlaybackBlock.REGION, PlaybackBlock.RATE_LIMITED, PlaybackBlock.MEMBERS_OR_PAID)
    }
}
