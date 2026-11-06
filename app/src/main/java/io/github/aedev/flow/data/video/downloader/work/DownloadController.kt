package io.github.aedev.flow.data.video.downloader.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.dao.DownloadDao
import io.github.aedev.flow.data.local.entity.DownloadEntity
import io.github.aedev.flow.data.local.entity.DownloadFileType
import io.github.aedev.flow.data.local.entity.DownloadItemEntity
import io.github.aedev.flow.data.local.entity.DownloadItemStatus
import io.github.aedev.flow.data.video.VideoDownloadManager
import io.github.aedev.flow.data.video.downloader.request.DownloadRequest
import io.github.aedev.flow.data.video.downloader.request.StoredArtist
import io.github.aedev.flow.data.video.downloader.tags.displayArtist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** What happened to a request handed to [DownloadController.enqueue]. */
enum class EnqueueOutcome {
    QUEUED,
    ALREADY_DOWNLOADED,
    ALREADY_QUEUED,
}

/**
 * The one way into the download queue. Every request is written to Room first, as a pending row,
 * and the queue work is then made sure to run: the row is what survives the app being killed, and
 * what the queue drains.
 */
@Singleton
class DownloadController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val downloadDao: DownloadDao,
        private val downloads: VideoDownloadManager,
        private val preferences: PlayerPreferences,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Queues [request]. A finished download is kept unless [replaceExisting], in which case it is
         * removed first so the new file does not leave the old one behind untracked. A running download
         * is never replaced: its job shares staging files with this video and only stops for a pause or
         * a cancel. One still waiting has no job yet, so [replaceExisting] swaps in a different request.
         */
        suspend fun enqueue(
            request: DownloadRequest,
            replaceExisting: Boolean = false,
        ): EnqueueOutcome {
            val existing = downloadDao.getDownloadWithItems(request.videoId)
            when (existing?.overallStatus) {
                DownloadItemStatus.COMPLETED -> {
                    if (!replaceExisting) return EnqueueOutcome.ALREADY_DOWNLOADED
                    downloads.deleteDownload(request.videoId)
                }

                DownloadItemStatus.PENDING -> {
                    if (!replaceExisting || DownloadRequest.decode(existing.download.requestJson) == request) {
                        return EnqueueOutcome.ALREADY_QUEUED
                    }
                }

                DownloadItemStatus.DOWNLOADING -> {
                    return EnqueueOutcome.ALREADY_QUEUED
                }

                else -> {}
            }
            downloadDao.replaceDownload(rowFor(request), listOf(pendingItemFor(request)))
            kick()
            return EnqueueOutcome.QUEUED
        }

        /** [enqueue] from a caller that should not wait, such as a dialog about to close. */
        fun submit(
            request: DownloadRequest,
            replaceExisting: Boolean = false,
            onResult: (EnqueueOutcome) -> Unit = {},
        ): Job = scope.launch { onResult(enqueue(request, replaceExisting)) }

        fun pause(videoId: String) = setStatus(videoId, DownloadItemStatus.PAUSED)

        fun resume(videoId: String) = setStatus(videoId, DownloadItemStatus.PENDING)

        /** Starts a failed download again from its stored request; blocks already on disk are reused. */
        fun retry(videoId: String) = setStatus(videoId, DownloadItemStatus.PENDING)

        /**
         * Stops [videoId] and removes it with everything it wrote. The queue sees the row go and stops
         * the transfer; the runner deletes its own staging files once its reads have stopped.
         */
        fun cancel(videoId: String): Job =
            scope.launch {
                downloadDao.updateAllItemsStatus(videoId, DownloadItemStatus.CANCELLED)
                downloads.deleteDownload(videoId)
            }

        /** Makes sure the queue runs when anything is waiting in it, e.g. at app start. */
        fun ensureQueueRunning(): Job =
            scope.launch {
                if (downloadDao.hasQueuedDownloads()) kick()
            }

        /** Queues the one pass that tags older downloads, unless it has already run. */
        fun scheduleRetagOnce(): Job = scope.launch { DownloadRetagWorker.scheduleOnce(context, preferences) }

        val retagStatus: Flow<RetagStatus> by lazy {
            combine(
                WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(DownloadRetagWorker.UNIQUE_NAME).map { it.firstOrNull() },
                preferences.downloadRetagResult,
                RetagStatus::of,
            )
        }

        /** Runs the queue work unless it is running already, with the network the settings allow. */
        suspend fun kick() {
            val wifiOnly = preferences.downloadOverWifiOnly.first()
            val work =
                OneTimeWorkRequestBuilder<DownloadQueueWorker>()
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                            .build(),
                    ).addTag(DownloadQueueWorker.TAG)
                    .build()
            WorkManager.getInstance(context).enqueueUniqueWork(DownloadQueueWorker.UNIQUE_NAME, ExistingWorkPolicy.KEEP, work)
        }

        /** A changed Wi-Fi-only setting has to reach work that is already waiting on the old constraint. */
        fun applyNetworkPolicy(): Job =
            scope.launch {
                WorkManager.getInstance(context).cancelUniqueWork(DownloadQueueWorker.UNIQUE_NAME)
                if (downloadDao.hasQueuedDownloads()) kick()
            }

        private fun setStatus(
            videoId: String,
            status: DownloadItemStatus,
        ): Job =
            scope.launch {
                downloadDao.updateAllItemsStatus(videoId, status)
                if (status == DownloadItemStatus.PENDING) kick()
            }

        private fun rowFor(request: DownloadRequest): DownloadEntity {
            val tags = request.tags
            return DownloadEntity(
                videoId = request.videoId,
                title = tags.title,
                uploader = tags.displayArtist().orEmpty(),
                duration = request.durationSeconds.toLong(),
                thumbnailUrl = tags.thumbnailUrl.orEmpty(),
                createdAt = System.currentTimeMillis(),
                kind = tags.kind,
                channelId = tags.channelId.orEmpty(),
                description = tags.description.orEmpty(),
                releaseDate = tags.releaseDate,
                viewCount = tags.viewCount ?: 0L,
                likeCount = tags.likeCount ?: 0L,
                album = tags.album,
                albumId = tags.albumId,
                artistsJson = request.artists.takeIf { it.isNotEmpty() }?.let(StoredArtist::encode),
                trackNumber = tags.trackNumber,
                requestJson = request.encode(),
            )
        }

        // Until it finishes, a download's only file is its staging placeholder, which no other download can share.
        private fun pendingItemFor(request: DownloadRequest): DownloadItemEntity {
            val placeholder = File(downloads.stagingDirectory(), "${request.videoId}.pending")
            return DownloadItemEntity(
                videoId = request.videoId,
                fileType = if (request.wantsAudioOnly) DownloadFileType.AUDIO else DownloadFileType.VIDEO,
                fileName = placeholder.name,
                filePath = placeholder.absolutePath,
                format = if (request.wantsAudioOnly) "m4a" else "mp4",
                mimeType = if (request.wantsAudioOnly) "audio/mp4" else "video/mp4",
                status = DownloadItemStatus.PENDING,
            )
        }
    }
