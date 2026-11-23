package io.github.aedev.flow.data.video.downloader.work

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.dao.DownloadDao
import io.github.aedev.flow.data.local.entity.DownloadItemStatus
import io.github.aedev.flow.data.repository.SponsorBlockRepository
import io.github.aedev.flow.data.video.DownloadProgressUpdate
import io.github.aedev.flow.data.video.DownloadStreamPolicy
import io.github.aedev.flow.data.video.OfflineSubtitleStore
import io.github.aedev.flow.data.video.VideoDownloadManager
import io.github.aedev.flow.data.video.downloader.request.DownloadRequest
import io.github.aedev.flow.data.video.downloader.resolve.ResolvedStreams
import io.github.aedev.flow.data.video.downloader.subtitle.DownloadSubtitleFile
import io.github.aedev.flow.data.video.downloader.tags.CoverArtLoader
import io.github.aedev.flow.data.video.downloader.tags.DownloadKind
import io.github.aedev.flow.data.video.downloader.tags.Mp4ProjectionCopier
import io.github.aedev.flow.data.video.downloader.tags.Mp4Remuxer
import io.github.aedev.flow.data.video.downloader.tags.Mp4TagWriter
import io.github.aedev.flow.data.video.downloader.transfer.DownloadTrace
import io.github.aedev.flow.data.video.downloader.transfer.RangeDownloader
import io.github.aedev.flow.data.video.storage.DownloadCovers
import io.github.aedev.flow.data.video.storage.DownloadFiles
import io.github.aedev.flow.data.video.storage.DownloadPlacement
import io.github.aedev.flow.data.video.storage.PlacedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs one download from its stored request to a finished, tagged, checked file in its folder.
 *
 * The queue calls this with a free slot. A cancelled run keeps the blocks it fetched, so a pause or
 * the system stopping the work resumes later; a cancelled download (its row gone) cleans up after
 * itself once its reads have stopped.
 */
@Singleton
class DownloadRunner
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val downloadDao: DownloadDao,
        private val downloads: VideoDownloadManager,
        private val transfer: DownloadTransfer,
        private val enricher: DownloadMetadataEnricher,
        private val lyrics: DownloadLyrics,
        private val remuxer: Mp4Remuxer,
        private val tagWriter: Mp4TagWriter,
        private val projectionCopier: Mp4ProjectionCopier,
        private val coverArt: CoverArtLoader,
        private val validator: DownloadValidator,
        private val placement: DownloadPlacement,
        private val notifier: DownloadNotifier,
        private val preferences: PlayerPreferences,
        private val sponsorBlock: SponsorBlockRepository,
        private val subtitles: OfflineSubtitleStore,
        private val subtitleFile: DownloadSubtitleFile,
        private val waits: DownloadWaits,
    ) {
        suspend fun run(videoId: String) {
            val row = downloadDao.getDownloadWithItems(videoId) ?: return
            val item = row.items.firstOrNull() ?: return
            val stored =
                DownloadRequest.decode(row.download.requestJson)
                    ?: LegacyDownloadRequest.from(row).also { LegacyDownloadRequest.discardPartialFiles(row) }
            val staging = DownloadStaging(downloads.stagingDirectory(), videoId)
            downloadDao.updateAllItemsStatus(videoId, DownloadItemStatus.DOWNLOADING)
            waits.stoppedForNetwork = false
            notifier.show(videoId, stored.tags.title, DownloadPhase.Transferring(0L, 0L))
            val trace = DownloadTrace(videoId)
            var outcome = "stopped"
            var threads = 0
            try {
                val request = enricher.enrich(stored)
                threads = request.threads ?: preferences.downloadThreads.first()
                coroutineScope {
                    val cover = async { coverArt.load(request.tags.thumbnailUrl, request.kind) }
                    val withLyrics = async { lyrics.addTo(request) }
                    when (val fetched = transfer.fetch(request, staging, item.id, threads, trace)) {
                        is FetchOutcome.Failed -> {
                            withLyrics.cancel()
                            val wait = waitFor(fetched.reason)?.let { holdOrNull(request, item.id, it, fetched, trace) }
                            outcome = wait?.let { "waiting:${it.reason}:${it.attempt}" } ?: "failed:${fetched.reason}"
                            if (wait == null) fail(request, staging, messageFor(fetched.reason), keepParts = true)
                        }

                        is FetchOutcome.Fetched -> {
                            outcome = finish(withLyrics.await(), staging, item.id, fetched, cover.await(), trace)
                        }
                    }
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { onStopped(videoId, item.id, stored, staging) }
                throw e
            } finally {
                withContext(NonCancellable) { Log.i(TRACE_TAG, trace.line(outcome, settingsOf(threads))) }
            }
        }

        /**
         * Puts a download whose failure should pass on its own back in the queue for a later retry. A
         * run that moved bytes before failing starts the backoff over: the network came back meanwhile.
         */
        private suspend fun holdOrNull(
            request: DownloadRequest,
            itemId: Int,
            reason: DownloadWaitReason,
            failed: FetchOutcome.Failed,
            trace: DownloadTrace,
        ): DownloadWait? {
            if (trace.bytesFetched > 0L) waits.clear(request.videoId)
            val wait = waits.hold(request.videoId, reason) ?: return null
            withContext(NonCancellable) {
                downloadDao.updateAllItemsStatus(request.videoId, DownloadItemStatus.PENDING)
                downloads.emitProgress(
                    DownloadProgressUpdate(
                        request.videoId,
                        itemId,
                        failed.downloadedBytes,
                        failed.totalBytes,
                        DownloadItemStatus.PENDING,
                        waitingFor = reason,
                        storageShortBy = failed.missingBytes,
                    ),
                )
                notifier.show(request.videoId, request.tags.title, DownloadPhase.Waiting(reason, failed.missingBytes))
            }
            return wait
        }

        private suspend fun settingsOf(threads: Int): String =
            "threads=$threads blockKiB=${RangeDownloader.BLOCK_SIZE / 1024} " +
                "concurrent=${preferences.concurrentDownloads.first()} net=${networkKind()}"

        private fun networkKind(): String {
            val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return "unknown"
            val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return NO_NETWORK
            return when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
            }
        }

        private suspend fun finish(
            request: DownloadRequest,
            staging: DownloadStaging,
            itemId: Int,
            fetched: FetchOutcome.Fetched,
            cover: ByteArray?,
            trace: DownloadTrace,
        ): String {
            notifier.show(request.videoId, request.tags.title, DownloadPhase.Finishing)
            downloads.emitProgress(
                DownloadProgressUpdate(request.videoId, itemId, 0L, 0L, DownloadItemStatus.DOWNLOADING, isMerging = true),
            )

            val extension = if (request.wantsAudioOnly) "m4a" else "mp4"
            val output = staging.output(extension)
            val runJob = currentCoroutineContext().job
            val remuxStarted = trace.now()
            val remuxed =
                withContext(Dispatchers.IO) {
                    remuxer.remux(
                        videoPath = fetched.videoPart?.path,
                        audioPath = fetched.audioPart.path,
                        outputPath = output.path,
                        tags = request.tags,
                        isCancelled = { !runJob.isActive },
                    )
                }
            trace.remuxMs = (trace.now() - remuxStarted) / NANOS_PER_MS
            trace.peakStagingBytes = (fetched.videoPart?.length() ?: 0L) + fetched.audioPart.length() + output.length()
            if (remuxed is Mp4Remuxer.Result.Failure) {
                currentCoroutineContext().ensureActive()
                Log.w(TAG, "${request.videoId}: remux failed (${remuxed.reason}): ${remuxed.message}")
                if (remuxed.reason == Mp4Remuxer.Reason.DISK_FULL) {
                    staging.clearOutputs()
                    val partBytes = (fetched.videoPart?.length() ?: 0L) + fetched.audioPart.length()
                    val failed = FetchOutcome.Failed(FetchFailure.DISK_FULL, remuxed.message, partBytes, partBytes)
                    holdOrNull(request, itemId, DownloadWaitReason.STORAGE, failed, trace)?.let { return "waiting:STORAGE:${it.attempt}" }
                }
                fail(request, staging, context.getString(R.string.download_failed_merge), keepParts = false)
                return "failed:REMUX_${remuxed.reason}"
            }
            fetched.videoPart?.let { keepProjection(request.videoId, it, output) }
            try {
                tagWriter.write(output, request.tags, cover)
            } catch (e: IOException) {
                Log.w(TAG, "${request.videoId}: tags not written", e)
            }
            if (!validator.isPlayable(output, expectVideo = !request.wantsAudioOnly, expectedDurationMs = fetched.durationMs)) {
                fail(request, staging, context.getString(R.string.download_failed_unreadable), keepParts = false)
                return "failed:UNREADABLE"
            }
            currentCoroutineContext().ensureActive()
            withContext(Dispatchers.IO) { DownloadFiles.sync(output) }
            val size = output.length()
            val placeStarted = trace.now()
            val placed = placement.place(output, request, extension)
            trace.placeMs = (trace.now() - placeStarted) / NANOS_PER_MS
            if (placed == null) {
                fail(request, staging, context.getString(R.string.download_failed_save), keepParts = true)
                return "failed:PLACE"
            }
            withContext(NonCancellable) {
                commit(request, staging, itemId, placed, extension, size, qualityOf(fetched), cover, fetched.streams)
            }
            return "completed"
        }

        /** A 360° video stays 360°; a failure only costs the projection, never the download (#1967). */
        private suspend fun keepProjection(
            videoId: String,
            videoPart: File,
            output: File,
        ) {
            try {
                val kept = withContext(Dispatchers.IO) { projectionCopier.copy(videoPart.path, output) }
                if (kept) Log.i(TAG, "$videoId: kept the 360° projection")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$videoId: 360° projection not kept", e)
            }
        }

        private suspend fun commit(
            request: DownloadRequest,
            staging: DownloadStaging,
            itemId: Int,
            placed: PlacedFile,
            extension: String,
            size: Long,
            quality: String,
            cover: ByteArray?,
            streams: ResolvedStreams?,
        ) {
            val captions = streams?.captions.orEmpty()
            val swappedTrack =
                streams?.takeIf { it.audioTrackSwapped }?.let {
                    DownloadStreamPolicy.audioLanguageLabel(it.audio) ?: context.getString(R.string.audio_track_original)
                }
            val path = placed.path
            val mimeType = if (request.wantsAudioOnly) "audio/mp4" else "video/mp4"
            downloadDao.completeItem(itemId, path, placed.fileName, extension, mimeType, quality, size)
            cover?.let { DownloadCovers.save(context, request.videoId, it) }?.let { downloadDao.updateThumbnailPath(request.videoId, it) }
            if (!DownloadFiles.isDocument(path)) downloads.scanFile(path, mimeType)
            downloads.emitProgress(DownloadProgressUpdate(request.videoId, itemId, size, size, DownloadItemStatus.COMPLETED))
            notifier.show(request.videoId, request.tags.title, DownloadPhase.Complete(placed.fellBackTo, swappedTrack))
            staging.clear()
            waits.clear(request.videoId)
            if (request.kind != DownloadKind.MUSIC && !request.wantsAudioOnly) {
                saveSponsorBlockSegments(request.videoId)
                runCatching { subtitles.saveForVideo(request.videoId, captions) }.onFailure { Log.w(TAG, "captions not saved", it) }
                runCatching { subtitleFile.writeBeside(request, placed, captions) }.onFailure { Log.w(TAG, "subtitle file not saved", it) }
            }
        }

        /** What the row shows for the stream it holds: "VP9 1080p60" for a video, "128 kbps · Arabic" for a dub's audio. */
        private fun qualityOf(fetched: FetchOutcome.Fetched): String {
            val streams = fetched.streams ?: return ""
            return streams.video?.let { DownloadStreamPolicy.videoQualityLabel(it, context.getString(R.string.download_quality_hdr)) }
                ?: listOfNotNull(
                    "${DownloadStreamPolicy.audioBitrateKbps(streams.audio)} ${context.getString(R.string.kbps)}",
                    streams.audio.audioTrack?.let { DownloadStreamPolicy.audioLanguageLabel(streams.audio) },
                ).joinToString(context.getString(R.string.list_separator_dot))
        }

        /** Only when the user has SponsorBlock on: otherwise no video id is sent to its API at all. */
        private suspend fun saveSponsorBlockSegments(videoId: String) {
            if (!preferences.sponsorBlockEnabled.first()) return
            runCatching { downloads.saveSponsorBlockData(videoId, sponsorBlock.serializeSegments(sponsorBlock.getSegments(videoId))) }
                .onFailure { Log.w(TAG, "SponsorBlock segments not saved for $videoId", it) }
        }

        private suspend fun fail(
            request: DownloadRequest,
            staging: DownloadStaging,
            message: String,
            keepParts: Boolean,
        ) {
            withContext(NonCancellable) {
                waits.clear(request.videoId)
                if (keepParts) staging.clearOutputs() else staging.clear()
                downloadDao.updateAllItemsStatus(request.videoId, DownloadItemStatus.FAILED)
                notifier.show(request.videoId, request.tags.title, DownloadPhase.Failed(message))
            }
        }

        /**
         * A run stopped from outside. Its row says why: paused (keep the blocks), cancelled or gone
         * (delete everything), finished or failed (nothing to undo), or still waiting because the
         * system stopped the work (keep the blocks and wait for the next run).
         */
        private suspend fun onStopped(
            videoId: String,
            itemId: Int,
            request: DownloadRequest,
            staging: DownloadStaging,
        ) {
            when (stopActionFor(downloadDao.getDownloadWithItems(videoId)?.overallStatus)) {
                StopAction.DISCARD -> {
                    staging.clear()
                    notifier.dismiss(videoId)
                }

                StopAction.KEEP_PAUSED -> {
                    notifier.show(videoId, request.tags.title, DownloadPhase.Paused)
                }

                StopAction.NOTHING -> {
                    Unit
                }

                StopAction.WAIT -> {
                    downloadDao.updateAllItemsStatus(videoId, DownloadItemStatus.PENDING)
                    // The system stops the queue when the network goes; say so rather than "Queued".
                    val offline = waits.stoppedForNetwork || networkKind() == NO_NETWORK
                    if (offline) {
                        val saved = downloadDao.getItemById(itemId)
                        downloads.emitProgress(
                            DownloadProgressUpdate(
                                videoId,
                                itemId,
                                saved?.downloadedBytes ?: 0L,
                                saved?.totalBytes ?: 0L,
                                DownloadItemStatus.PENDING,
                                waitingFor = DownloadWaitReason.NETWORK,
                            ),
                        )
                    }
                    notifier.show(
                        videoId,
                        request.tags.title,
                        if (offline) DownloadPhase.Waiting(DownloadWaitReason.NETWORK) else DownloadPhase.Queued,
                    )
                }
            }
        }

        private fun messageFor(reason: FetchFailure): String =
            context.getString(
                when (reason) {
                    FetchFailure.UNAVAILABLE -> R.string.download_failed_unavailable
                    FetchFailure.NO_COMPATIBLE_AUDIO -> R.string.download_failed_no_audio
                    FetchFailure.REFUSED -> R.string.download_failed_refused
                    FetchFailure.LENGTH_UNKNOWN, FetchFailure.NETWORK -> R.string.download_failed_try_again
                    FetchFailure.DISK_FULL -> R.string.download_failed_disk_full
                },
            )

        private companion object {
            const val TAG = "DownloadRunner"
            const val TRACE_TAG = "DownloadTrace"
            const val NO_NETWORK = "none"
            const val NANOS_PER_MS = 1_000_000L
        }
    }

/** The wait a failure earns instead of failing the download, or null when it is final. */
internal fun waitFor(reason: FetchFailure): DownloadWaitReason? =
    when (reason) {
        FetchFailure.NETWORK, FetchFailure.LENGTH_UNKNOWN -> DownloadWaitReason.NETWORK
        FetchFailure.DISK_FULL -> DownloadWaitReason.STORAGE
        FetchFailure.UNAVAILABLE, FetchFailure.NO_COMPATIBLE_AUDIO, FetchFailure.REFUSED -> null
    }

/** What a download stopped from outside leaves behind, by the state its row is in. */
internal enum class StopAction {
    DISCARD,
    KEEP_PAUSED,
    NOTHING,
    WAIT,
}

internal fun stopActionFor(status: DownloadItemStatus?): StopAction =
    when (status) {
        null, DownloadItemStatus.CANCELLED -> StopAction.DISCARD
        DownloadItemStatus.PAUSED -> StopAction.KEEP_PAUSED
        DownloadItemStatus.COMPLETED, DownloadItemStatus.FAILED -> StopAction.NOTHING
        DownloadItemStatus.PENDING, DownloadItemStatus.DOWNLOADING -> StopAction.WAIT
    }
