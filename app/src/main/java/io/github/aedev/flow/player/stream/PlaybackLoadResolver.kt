package io.github.aedev.flow.player.stream

import android.util.Log
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.VideoQuality
import io.github.aedev.flow.data.local.ViewHistory
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.repository.SponsorBlockRepository
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.data.video.DownloadedVideo
import io.github.aedev.flow.data.video.VideoDownloadManager
import io.github.aedev.flow.di.IoDispatcher
import io.github.aedev.flow.di.NetworkIoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** The three preference reads every stream resolution needs. */
internal data class StreamPreferences(
    val quality: VideoQuality,
    val audioLanguage: String,
    val codecKey: String,
    val subtitleLanguage: String,
)

/**
 * Turns a video id into something the player screen can play.
 *
 * A finished download wins outright and costs no network; otherwise it runs the InnerTube client
 * ladder, escalates to a SABR session when expired URLs force it, and falls back to a premiere
 * countdown when nothing plays. It owns every network call the load makes and holds no player-screen
 * state: results are handed back as [ResolvedPlayback] steps, in the order the screen has to act on
 * them.
 */
class PlaybackLoadResolver
    @Inject
    constructor(
        private val repository: YouTubeRepository,
        private val viewHistory: ViewHistory,
        private val playerPreferences: PlayerPreferences,
        private val videoDownloadManager: VideoDownloadManager,
        private val sponsorBlockRepository: SponsorBlockRepository,
        @NetworkIoDispatcher private val networkDispatcher: CoroutineDispatcher,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        /**
         * @param scope the caller's load job, which owns the extraction so it ends with that job.
         * @param isCurrent whether the load that started this resolution is still the current one.
         * @param resolveUpcoming the caller's premiere lookup, kept there because the decision reads
         *   the video the screen already has cached.
         */
        suspend fun resolve(
            scope: CoroutineScope,
            request: PlaybackResolutionRequest,
            isCurrent: () -> Boolean,
            resolveUpcoming: suspend (videoId: String, knownUpcoming: Boolean) -> UpcomingPremiere,
            onStep: suspend (ResolvedPlayback) -> Unit,
        ) {
            val videoId = request.videoId

            try {
                val localCopy = localCopyOf(videoId)
                currentCoroutineContext().ensureActive()
                if (localCopy != null) {
                    Log.d(TAG, "Found offline video at ${localCopy.filePath}")
                    val storedSponsorBlockJson = videoDownloadManager.getSponsorBlockData(videoId)
                    if (!isCurrent()) return
                    onStep(
                        ResolvedPlayback.LocalCopyReady(
                            localFilePath = localCopy.filePath,
                            offlineSegments = sponsorBlockRepository.parseSegments(storedSponsorBlockJson),
                            needsSponsorBlockBackfill = storedSponsorBlockJson == null,
                            downloadedVideo = localCopy.video,
                        ),
                    )
                    return
                }

                val innerTubeDeferred =
                    scope.async(networkDispatcher) {
                        extractInnerTube(videoId, forceSabr = request.escalateToSabr, freshStreams = request.freshStreams)
                    }

                // Startup-critical disk reads, resolved in parallel with stream extraction so the
                // playback-preparation path below never blocks on DataStore/DB.
                val savedPositionDeferred =
                    scope.async(ioDispatcher) {
                        request.resumePositionOverrideMs?.takeIf { it > 0L }
                            ?: viewHistory.getPlaybackPosition(videoId).first()
                    }
                val autoplayDeferred = scope.async(ioDispatcher) { playerPreferences.autoplayEnabled.first() }
                val preferences = withContext(ioDispatcher) { readStreamPreferences(request.isWifi) }

                val playbackLoadTimeoutMs = if (request.escalateToSabr) SABR_LOAD_TIMEOUT_MS else LOAD_TIMEOUT_MS
                withTimeout(playbackLoadTimeoutMs) {
                    resolveStreams(
                        request = request,
                        preferences = preferences,
                        innerTubeDeferred = innerTubeDeferred,
                        savedPositionDeferred = savedPositionDeferred,
                        autoplayDeferred = autoplayDeferred,
                        isCurrent = isCurrent,
                        resolveUpcoming = resolveUpcoming,
                        onStep = onStep,
                    )
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Video info load timed out for $videoId", e)
                if (isCurrent()) {
                    onStep(upcomingOrFailure(videoId, PlaybackFailure.TIMEOUT, null, null, resolveUpcoming))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Exception loading video $videoId", e)
                if (isCurrent()) {
                    onStep(upcomingOrFailure(videoId, PlaybackFailure.UNEXPECTED, e, null, resolveUpcoming))
                }
            }
        }

        private suspend fun resolveStreams(
            request: PlaybackResolutionRequest,
            preferences: StreamPreferences,
            innerTubeDeferred: Deferred<InnerTubeVideoStreamExtractor.VideoExtractionResult?>,
            savedPositionDeferred: Deferred<Long>,
            autoplayDeferred: Deferred<Boolean>,
            isCurrent: () -> Boolean,
            resolveUpcoming: suspend (String, Boolean) -> UpcomingPremiere,
            onStep: suspend (ResolvedPlayback) -> Unit,
        ) {
            val videoId = request.videoId
            Log.d(TAG, "Loading video $videoId with preferred quality: ${preferences.quality.label} (isWifi=${request.isWifi})")

            var innerTubeResult = innerTubeDeferred.await()
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) return

            if (request.escalateToSabr && innerTubeResult == null) {
                // The blanket refusal below was written when every fast client was session-gated, so
                // a re-extraction could only hand back the URLs that had just 403'd. The full ladder
                // now steps over a walled VISIONOS to TV_TIZEN (#921), so one retry is a real second
                // chance, and the only thing standing between a device that cannot mint a PoToken
                // (no or broken WebView) and playback that never resumes. Still budgeted per video.
                Log.w(TAG, "Forced-SABR reload for $videoId produced no SABR session — retrying the full client ladder")
                innerTubeResult =
                    withTimeoutOrNull(INNERTUBE_TIMEOUT_MS) {
                        InnerTubeVideoStreamExtractor.extract(videoId, forceSabr = false)
                    }?.takeIf { innerTubeCanStartPlayback(it) }
                currentCoroutineContext().ensureActive()
                if (!isCurrent()) return
            }

            if (request.escalateToSabr && innerTubeResult == null) {
                Log.e(TAG, "Forced-SABR reload for $videoId produced no playable session — giving up on this attempt")
                if (isCurrent()) {
                    onStep(ResolvedPlayback.Failed(PlaybackFailure.EXTRACTION, extractionFailureCause(videoId), relatedVideos = null))
                }
                return
            }

            val liveFromInnerTube =
                innerTubeResult?.isLive == true &&
                    (!innerTubeResult.liveHlsUrl.isNullOrEmpty() || !innerTubeResult.liveDashUrl.isNullOrEmpty())

            // The related lane, the autoplay candidates and the queue all read this one list, and
            // it is filled from the watch response once playback is under way rather than held for
            // here: a load that waited on it would be waiting on a second request.
            val relatedVideos = emptyList<Video>()

            if (liveFromInnerTube && innerTubeResult != null) {
                onStep(ResolvedPlayback.Live(innerTubeResult, relatedVideos))
            } else if (innerTubeResult != null && innerTubeHasPlayableVod(innerTubeResult)) {
                onStep(
                    ResolvedPlayback.VodFromInnerTube(
                        result = innerTubeResult,
                        relatedVideos = relatedVideos,
                        preferredQuality = preferences.quality,
                        preferredAudioLanguage = preferences.audioLanguage,
                        preferredCodecKey = preferences.codecKey,
                        preferredSubtitleLanguage = preferences.subtitleLanguage,
                        resumePositionOverrideMs = request.resumePositionOverrideMs,
                    ),
                )
            } else {
                Log.e(TAG, "InnerTube resolved nothing playable for $videoId and no offline copy found.")
                onStep(
                    upcomingOrFailure(videoId, PlaybackFailure.EXTRACTION, extractionFailureCause(videoId), relatedVideos, resolveUpcoming),
                )
            }
        }

        private suspend fun upcomingOrFailure(
            videoId: String,
            failure: PlaybackFailure,
            cause: Throwable?,
            relatedVideos: List<Video>?,
            resolveUpcoming: suspend (String, Boolean) -> UpcomingPremiere,
        ): ResolvedPlayback {
            val upcoming = resolveUpcoming(videoId, false)
            return if (upcoming.isUpcoming) {
                ResolvedPlayback.Upcoming(relatedVideos.orEmpty(), upcoming.scheduledStartMs, upcoming.details)
            } else {
                ResolvedPlayback.Failed(failure, cause, relatedVideos)
            }
        }

        private fun extractionFailureCause(videoId: String): Throwable? {
            val block = InnerTubeVideoStreamExtractor.blockOf(videoId)
            val refusal = InnerTubeVideoStreamExtractor.refusalOf(videoId)
            return when {
                block != null -> PlaybackBlockedException(block, refusal)
                InnerTubeVideoStreamExtractor.isGone(videoId) || refusal != null -> VideoUnavailableException(videoId, refusal)
                else -> null
            }
        }

        private suspend fun extractInnerTube(
            videoId: String,
            forceSabr: Boolean,
            freshStreams: Boolean,
        ): InnerTubeVideoStreamExtractor.VideoExtractionResult? =
            try {
                when {
                    forceSabr -> {
                        InnerTubeVideoStreamExtractor.extract(videoId, forceSabr = true)
                    }

                    freshStreams -> {
                        InnerTubeVideoStreamExtractor.forgetReusable(videoId)
                        withTimeoutOrNull(INNERTUBE_TIMEOUT_MS) { InnerTubeVideoStreamExtractor.extractForPage(videoId) }
                    }

                    else -> {
                        // Both offers are spent by this first load, so a reload after a refusal extracts fresh.
                        val tapped = PlaybackPrefetcher.take(videoId)
                        (InnerTubeVideoStreamExtractor.takePromoted(videoId) ?: tapped)
                            ?.also { Log.d(TAG, "Load of $videoId took a finished extraction; no new client ladder") }
                            ?: withTimeoutOrNull(INNERTUBE_TIMEOUT_MS) { InnerTubeVideoStreamExtractor.extractForPage(videoId) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "InnerTube extraction failed for $videoId: ${e.message}")
                null
            }

        private suspend fun readStreamPreferences(isWifi: Boolean): StreamPreferences =
            StreamPreferences(
                quality =
                    if (isWifi) {
                        playerPreferences.defaultQualityWifi.first()
                    } else {
                        playerPreferences.defaultQualityCellular.first()
                    },
                audioLanguage = playerPreferences.preferredAudioLanguage.first(),
                codecKey = playerPreferences.videoCodecPriority.first(),
                subtitleLanguage = playerPreferences.preferredSubtitleLanguage.first(),
            )

        private suspend fun localCopyOf(videoId: String): DownloadedVideo? =
            try {
                videoDownloadManager.findLocalCopy(videoId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Local copy lookup failed for $videoId", e)
                null
            }

        internal companion object {
            const val TAG = "PlaybackLoadResolver"
            const val INNERTUBE_TIMEOUT_MS = 25_000L
            const val LOAD_TIMEOUT_MS = 30_000L
            const val SABR_LOAD_TIMEOUT_MS = 120_000L

            /**
             * A SABR session used to count as playable on its own. It is not: the server never
             * sends an init segment, so ExoPlayer cannot sniff the stream and the video fails
             * however long the pipeline waits. Playability is the direct formats, and a result
             * without them should let the ladder move on rather than end the load.
             */
            fun innerTubeHasPlayableVod(result: InnerTubeVideoStreamExtractor.VideoExtractionResult): Boolean {
                if (result.isLive) return false
                val hasVideo = result.videoFormats.any { !it.url.isNullOrEmpty() }
                val hasAudio = result.audioFormats.any { !it.url.isNullOrEmpty() }
                return hasVideo && hasAudio
            }

            fun innerTubeCanStartPlayback(result: InnerTubeVideoStreamExtractor.VideoExtractionResult): Boolean {
                val hasLiveManifest =
                    result.isLive &&
                        (!result.liveHlsUrl.isNullOrEmpty() || !result.liveDashUrl.isNullOrEmpty())
                return hasLiveManifest || innerTubeHasPlayableVod(result)
            }
        }
    }
