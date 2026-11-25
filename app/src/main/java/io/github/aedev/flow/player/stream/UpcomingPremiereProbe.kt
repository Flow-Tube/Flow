package io.github.aedev.flow.player.stream

import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.models.YouTubeClient
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** What a playability lookup says about a video that has not started yet. */
data class UpcomingPremiere(
    val isUpcoming: Boolean,
    val scheduledStartMs: Long?,
    val details: UpcomingDetails? = null,
) {
    companion object {
        val NOT_UPCOMING = UpcomingPremiere(isUpcoming = false, scheduledStartMs = null)
    }
}

/**
 * One light look at a video that has not started: whether it plays now, whether it still waits, which
 * broadcast it is, and when YouTube says to ask again.
 */
data class UpcomingPoll(
    val isPlayable: Boolean,
    val isUpcoming: Boolean,
    val broadcastId: String?,
    val pollDelayMs: Long?,
    val heartbeatIntervalMs: Long?,
)

/** The metadata the player endpoint returns even while a stream is offline. */
data class UpcomingDetails(
    val title: String,
    val channelName: String,
    val channelId: String,
    val thumbnailUrl: String,
    val description: String,
)

/**
 * Asks the player endpoint whether a video is a premiere or a scheduled live stream that has not
 * started, for the case where extraction produced nothing playable and the reason matters: an
 * unstarted premiere is a countdown, not an error.
 */
class UpcomingPremiereProbe
    @Inject
    constructor() {
        suspend fun probe(videoId: String): UpcomingPremiere =
            try {
                val response = fetch(videoId) ?: return UpcomingPremiere.NOT_UPCOMING
                val status = response.playabilityStatus
                if (!looksUpcoming(response)) return UpcomingPremiere.NOT_UPCOMING
                val scheduledMs =
                    status.liveStreamability
                        ?.liveStreamabilityRenderer
                        ?.offlineSlate
                        ?.liveStreamOfflineSlateRenderer
                        ?.scheduledStartTime
                        ?.toLongOrNull()
                        ?.times(1000L)
                        // A start time in the past is a stream that already began: no countdown to show.
                        ?.takeIf { it > System.currentTimeMillis() }
                val details =
                    response.videoDetails?.let { video ->
                        UpcomingDetails(
                            title = video.title.orEmpty(),
                            channelName = video.author.orEmpty(),
                            channelId = video.channelId,
                            thumbnailUrl =
                                video.thumbnail
                                    ?.thumbnails
                                    ?.maxByOrNull { it.width ?: 0 }
                                    ?.url
                                    .orEmpty(),
                            description = video.shortDescription.orEmpty(),
                        )
                    }
                UpcomingPremiere(isUpcoming = true, scheduledStartMs = scheduledMs, details = details)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpcomingPremiere.NOT_UPCOMING
            }

        /** One single-client `/player` call for a video counting down; null when it fails. */
        suspend fun poll(videoId: String): UpcomingPoll? =
            try {
                fetch(videoId)?.let(::pollOf)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }

        private suspend fun fetch(videoId: String): PlayerResponse? =
            withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                YouTube.player(videoId, client = YouTubeClient.MOBILE).getOrNull()
            }

        internal companion object {
            private const val PROBE_TIMEOUT_MS = 6_000L

            internal fun pollOf(response: PlayerResponse): UpcomingPoll {
                val renderer = response.playabilityStatus.liveStreamability?.liveStreamabilityRenderer
                return UpcomingPoll(
                    isPlayable = response.playabilityStatus.status.equals("OK", ignoreCase = true) && hasMedia(response),
                    isUpcoming = looksUpcoming(response),
                    broadcastId = renderer?.broadcastId?.takeIf { it.isNotBlank() },
                    pollDelayMs = renderer?.pollDelayMs?.toLongOrNull(),
                    heartbeatIntervalMs = response.heartbeatParams?.intervalMilliseconds?.toLongOrNull(),
                )
            }

            private fun hasMedia(response: PlayerResponse): Boolean {
                val streamingData = response.streamingData
                return !streamingData?.hlsManifestUrl.isNullOrBlank() ||
                    streamingData?.formats?.isNotEmpty() == true ||
                    streamingData?.adaptiveFormats?.isNotEmpty() == true
            }

            private fun looksUpcoming(response: PlayerResponse): Boolean {
                val status = response.playabilityStatus
                val reason = status.reason.orEmpty()
                return !hasMedia(response) && (
                    status.status.equals("LIVE_STREAM_OFFLINE", ignoreCase = true) ||
                        status.liveStreamability != null ||
                        reason.contains("premiere", ignoreCase = true) ||
                        reason.contains("will begin", ignoreCase = true) ||
                        reason.contains("scheduled", ignoreCase = true)
                )
            }
        }
    }
