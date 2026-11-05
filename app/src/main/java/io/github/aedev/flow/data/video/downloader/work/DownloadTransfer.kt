package io.github.aedev.flow.data.video.downloader.work

import android.util.Log
import io.github.aedev.flow.data.local.entity.DownloadItemStatus
import io.github.aedev.flow.data.video.DownloadProgressUpdate
import io.github.aedev.flow.data.video.VideoDownloadManager
import io.github.aedev.flow.data.video.downloader.request.DownloadRequest
import io.github.aedev.flow.data.video.downloader.resolve.DownloadStreamResolver
import io.github.aedev.flow.data.video.downloader.resolve.ResolveOutcome
import io.github.aedev.flow.data.video.downloader.resolve.ResolvedStreams
import io.github.aedev.flow.data.video.downloader.transfer.RangeDownloader
import io.github.aedev.flow.data.video.downloader.transfer.StreamRole
import io.github.aedev.flow.data.video.downloader.transfer.TransferFailure
import io.github.aedev.flow.data.video.downloader.transfer.TransferJob
import io.github.aedev.flow.data.video.downloader.transfer.TransferResult
import io.github.aedev.flow.data.video.downloader.transfer.TransferState
import io.github.aedev.flow.data.video.downloader.transfer.TransferStream
import io.github.aedev.flow.innertube.models.YouTubeClient
import io.github.aedev.flow.player.error.StreamDenialClassifier
import io.github.aedev.flow.player.sabr.integration.SabrDownloadEngine
import io.github.aedev.flow.player.stream.ClientGateTracker
import io.github.aedev.flow.player.stream.InnerTubeVideoStreamExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Why a download's bytes could not be fetched. */
enum class FetchFailure {
    UNAVAILABLE,
    NO_COMPATIBLE_AUDIO,
    REFUSED,
    LENGTH_UNKNOWN,
    NETWORK,
    DISK_FULL,
}

sealed interface FetchOutcome {
    data class Fetched(
        val videoPart: File?,
        val audioPart: File,
        val durationMs: Long,
        /** The streams it came from, for the quality the row shows; null after a SABR fallback. */
        val streams: ResolvedStreams? = null,
    ) : FetchOutcome

    data class Failed(
        val reason: FetchFailure,
        val detail: String? = null,
    ) : FetchOutcome
}

/**
 * Gets a request's streams into staging. A refused URL is read the way playback reads it: an
 * expired one is simply re-minted and the transfer continues where it stopped; an unattested or
 * token-refused client is reported to [ClientGateTracker] so the next extraction skips it. A stream
 * refused twice is dropped for another one, and SABR is tried only once direct URLs are exhausted.
 */
@Singleton
class DownloadTransfer
    @Inject
    constructor(
        private val resolver: DownloadStreamResolver,
        private val rangeDownloader: RangeDownloader,
        private val downloads: VideoDownloadManager,
        private val notifier: DownloadNotifier,
    ) {
        internal suspend fun fetch(
            request: DownloadRequest,
            staging: DownloadStaging,
            itemId: Int,
            threads: Int,
        ): FetchOutcome {
            var avoid = emptySet<Int>()
            var acceptOtherTrack = false
            val refusals = mutableMapOf<Int, Int>()
            repeat(MAX_RESOLVES) {
                val streams =
                    when (val resolved = resolver.resolve(request, avoid, acceptOtherTrack)) {
                        is ResolveOutcome.Resolved -> {
                            resolved.streams
                        }

                        ResolveOutcome.Unavailable -> {
                            return FetchOutcome.Failed(FetchFailure.UNAVAILABLE)
                        }

                        ResolveOutcome.NoCompatibleAudio -> {
                            return FetchOutcome.Failed(FetchFailure.NO_COMPATIBLE_AUDIO)
                        }

                        ResolveOutcome.AudioTrackMissing -> {
                            val sabr = viaSabr(request, staging, itemId, requireChosenTrack = true)
                            if (sabr is FetchOutcome.Fetched) return sabr
                            currentCoroutineContext().ensureActive()
                            acceptOtherTrack = true
                            return@repeat
                        }
                    }
                val video = streams.video
                val parts =
                    listOfNotNull(
                        video?.let {
                            TransferStream(
                                StreamRole.VIDEO,
                                it.url.orEmpty(),
                                staging.videoPart,
                                it.itag,
                                it.contentLength ?: 0L,
                            )
                        },
                        TransferStream(
                            StreamRole.AUDIO,
                            streams.audio.url.orEmpty(),
                            staging.audioPart,
                            streams.audio.itag,
                            streams.audio.contentLength ?: 0L,
                            streams.audio.audioTrack?.id,
                        ),
                    )
                if (TransferState.read(staging.state)?.restoreInto(parts) == true) Log.d(TAG, "${request.videoId}: resuming saved blocks")
                val job = TransferJob(request.videoId, parts, threads, YouTubeClient.USER_AGENT_WEB)
                val result =
                    try {
                        reportingProgress(
                            request,
                            itemId,
                            { job.downloadedBytes },
                            { job.totalBytes },
                            { saveState(staging, job) },
                            StallLimit(DIRECT_STALL_TICKS) { job.fail(TransferResult.Stalled) },
                        ) {
                            rangeDownloader.run(job)
                        }
                    } finally {
                        saveState(staging, job)
                    }
                when (result) {
                    TransferResult.Completed -> {
                        return FetchOutcome.Fetched(video?.let { staging.videoPart }, staging.audioPart, streams.durationMs, streams)
                    }

                    TransferResult.Stopped -> {
                        currentCoroutineContext().ensureActive()
                        return FetchOutcome.Failed(FetchFailure.NETWORK)
                    }

                    TransferResult.Stalled -> {
                        Log.w(TAG, "${request.videoId}: no bytes for ${DIRECT_STALL_TICKS}s, resolving again")
                    }

                    is TransferResult.Denied -> {
                        val itag = StreamDenialClassifier.itagOf(result.url)?.toIntOrNull()
                        ClientGateTracker.reportDenied(result.url)
                        if (itag != null && (refusals.merge(itag, 1, Int::plus) ?: 0) >= 2) avoid = avoid + itag
                    }

                    is TransferResult.Failed -> {
                        return FetchOutcome.Failed(
                            when (result.reason) {
                                TransferFailure.DISK_FULL -> FetchFailure.DISK_FULL
                                TransferFailure.LENGTH_UNKNOWN -> FetchFailure.LENGTH_UNKNOWN
                                TransferFailure.BLOCK_FAILED -> FetchFailure.NETWORK
                            },
                            result.detail,
                        )
                    }
                }
            }
            return viaSabr(request, staging, itemId)
        }

        /**
         * The last resort once every direct URL was refused. What it writes still has to pass the
         * runner's remux and playability checks, so a session that never sends an init segment ends
         * as a failure rather than a finished file that cannot play.
         */
        private suspend fun viaSabr(
            request: DownloadRequest,
            staging: DownloadStaging,
            itemId: Int,
            requireChosenTrack: Boolean = false,
        ): FetchOutcome {
            staging.clearParts()
            val info =
                try {
                    InnerTubeVideoStreamExtractor.resolveSabrDownload(
                        videoId = request.videoId,
                        targetHeight = if (request.wantsAudioOnly) 0 else request.targetHeight ?: 0,
                        preferredCodec = request.videoCodec,
                        audioTrackId = request.audioTrackId,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "${request.videoId}: SABR resolve failed", e)
                    null
                } ?: return FetchOutcome.Failed(FetchFailure.REFUSED)
            if (requireChosenTrack && info.audioTrackId != request.audioTrackId) return FetchOutcome.Failed(FetchFailure.REFUSED)
            val engine = SabrDownloadEngine()
            var estimated = 0L
            val done =
                try {
                    reportingProgress(
                        request,
                        itemId,
                        { engine.downloadedVideoBytes.get() + engine.downloadedAudioBytes.get() },
                        { estimated },
                        null,
                        StallLimit(SABR_STALL_TICKS) {
                            Log.w(TAG, "${request.videoId}: SABR sent nothing new for ${SABR_STALL_TICKS}s")
                            engine.cancel()
                        },
                    ) {
                        engine.download(
                            streamingUrl = info.streamingUrl,
                            videoId = request.videoId,
                            audioItag = info.audioItag,
                            audioLmt = info.audioLmt,
                            videoItag = info.videoItag,
                            videoLmt = info.videoLmt,
                            audioXtags = info.audioXtags,
                            videoXtags = info.videoXtags,
                            audioTrackId = info.audioTrackId,
                            poToken = info.poToken,
                            visitorId = info.visitorId,
                            ustreamerConfig = info.ustreamerConfig,
                            durationMs = info.durationMs,
                            videoOutputPath = staging.videoPart.path,
                            audioOutputPath = staging.audioPart.path,
                            audioOnly = request.wantsAudioOnly,
                        ) { _, total -> if (total > 0) estimated = total }
                    }
                } catch (e: CancellationException) {
                    engine.cancel()
                    throw e
                }
            if (!done) return FetchOutcome.Failed(FetchFailure.REFUSED)
            return FetchOutcome.Fetched(staging.videoPart.takeUnless { request.wantsAudioOnly }, staging.audioPart, info.durationMs)
        }

        private suspend fun <T> reportingProgress(
            request: DownloadRequest,
            itemId: Int,
            downloaded: () -> Long,
            total: () -> Long,
            persist: (() -> Unit)?,
            stall: StallLimit,
            block: suspend () -> T,
        ): T =
            coroutineScope {
                val ticker =
                    launch {
                        var ticks = 0
                        while (isActive) {
                            delay(PROGRESS_INTERVAL_MS)
                            val done = downloaded()
                            val all = total()
                            if (stall.stillAt(done)) stall.onStall()
                            downloads.emitProgress(
                                DownloadProgressUpdate(request.videoId, itemId, done, all, DownloadItemStatus.DOWNLOADING),
                            )
                            notifier.show(request.videoId, request.tags.title, DownloadPhase.Transferring(done, all))
                            if (++ticks % PERSIST_EVERY_TICKS == 0) persist?.invoke()
                        }
                    }
                try {
                    block()
                } finally {
                    ticker.cancel()
                }
            }

        private fun saveState(
            staging: DownloadStaging,
            job: TransferJob,
        ) {
            runCatching { TransferState.write(staging.state, TransferState.of(job)) }
                .onFailure { Log.w(TAG, "${job.videoId}: could not save resume state", it) }
        }

        private companion object {
            const val TAG = "DownloadTransfer"
            const val MAX_RESOLVES = 4
            const val PROGRESS_INTERVAL_MS = 1_000L
            const val PERSIST_EVERY_TICKS = 5
            const val DIRECT_STALL_TICKS = 45
            const val SABR_STALL_TICKS = 60
        }
    }
