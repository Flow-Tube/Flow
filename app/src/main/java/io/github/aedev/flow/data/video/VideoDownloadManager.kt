package io.github.aedev.flow.data.video

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.dao.DownloadDao
import io.github.aedev.flow.data.local.entity.DownloadEntity
import io.github.aedev.flow.data.local.entity.DownloadFileType
import io.github.aedev.flow.data.local.entity.DownloadItemEntity
import io.github.aedev.flow.data.local.entity.DownloadItemStatus
import io.github.aedev.flow.data.local.entity.DownloadWithItems
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.video.downloader.request.DownloadRequest
import io.github.aedev.flow.data.video.downloader.work.DownloadStaging
import io.github.aedev.flow.data.video.storage.DownloadDestination
import io.github.aedev.flow.data.video.storage.DownloadFiles
import io.github.aedev.flow.data.video.storage.DownloadLocation
import io.github.aedev.flow.data.video.storage.resolveDownloadDestination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Progress update emitted during active downloads.
 */
data class DownloadProgressUpdate(
    val videoId: String,
    val itemId: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val status: DownloadItemStatus,
    val isMerging: Boolean = false,
) {
    val progress: Float
        get() = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes.toFloat() else 0f
}

/**
 * Legacy compat — wraps DownloadWithItems for backward compatibility with existing UI.
 */
data class DownloadedVideo(
    val video: Video,
    val filePath: String,
    val downloadedAt: Long = System.currentTimeMillis(),
    val fileSize: Long = 0,
    val downloadId: Long = -1,
    val quality: String = "Unknown",
    val isAudioOnly: Boolean = false,
)

/**
 * Manages all video/audio download persistence and file operations.
 * Backed by Room database via DownloadDao.
 */
@Singleton
class VideoDownloadManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val downloadDao: DownloadDao,
        private val offlineSubtitleStore: OfflineSubtitleStore,
        private val preferences: PlayerPreferences,
    ) {
        companion object {
            private const val TAG = "VideoDownloadManager"
            const val VIDEO_DIR = "Flow"
            const val AUDIO_DIR = "Flow"
            private const val STAGING_DIR = "staging"
            private const val INTERNAL_DIR = "downloads"

            /**
             * Legacy bridge — callers that still use getInstance() will get a crash
             * with a clear message telling them to switch to DI.
             */
            @Deprecated("Use Hilt injection instead", level = DeprecationLevel.ERROR)
            fun getInstance(context: Context): VideoDownloadManager =
                throw UnsupportedOperationException(
                    "VideoDownloadManager is now Hilt-managed. Use @Inject instead of getInstance().",
                )
        }

        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** In-memory guard: paths whose DB entry was deleted but whose file deletion is in-flight. */
        private val recentlyDeletedPaths: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /**
         * Persistent tombstone: file paths deleted from the DB by the user but whose physical
         * file could not be removed (e.g. Android 11+ scoped storage issue, file in use).
         * Prevents scanAndRecoverDownloads() from re-inserting them across app restarts.
         */
        private val tombstonePrefs by lazy {
            context.getSharedPreferences("flow_file_tombstones", Context.MODE_PRIVATE)
        }

        private val _undeletable = MutableSharedFlow<List<String>>(extraBufferCapacity = 8)

        /** Files a delete left on disk because another install owns them; the screen asks the system instead. */
        val undeletable: SharedFlow<List<String>> = _undeletable.asSharedFlow()

        // Progress updates emitted by FlowDownloadService
        private val _progressUpdates = MutableSharedFlow<DownloadProgressUpdate>(extraBufferCapacity = 64)
        val progressUpdates: SharedFlow<DownloadProgressUpdate> = _progressUpdates.asSharedFlow()

        fun emitProgress(update: DownloadProgressUpdate) {
            _progressUpdates.tryEmit(update)
        }

        // ===== Directory Management =====

        /** Check if the app has All Files Access (MANAGE_EXTERNAL_STORAGE) on Android 11+ */
        fun hasAllFilesAccess(): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                // Pre-Android 11: WRITE_EXTERNAL_STORAGE would be needed but
                // we just use app-private dirs which need no permissions
                false
            }

        /**
         * Tell the Android system to scan the newly downloaded file.
         * This adds the file into the system's MediaStore index so it's instantly
         * visible to external gallery and media player apps, without needing a duplicate copy.
         */
        fun scanFile(
            filePath: String,
            mimeType: String = "video/mp4",
        ) {
            try {
                val file = File(filePath)
                if (!file.exists()) {
                    Log.e(TAG, "scanFile: File does not exist: $filePath")
                    return
                }

                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(file.absolutePath),
                    arrayOf(mimeType),
                ) { path, uri ->
                    Log.d(TAG, "scanFile: Scanned $path: -> uri=$uri")
                }
            } catch (e: Exception) {
                Log.e(TAG, "scanFile failed", e)
            }
        }

        /** App-private working space for downloads in progress, never visible to other apps. */
        fun stagingDirectory(): File = File(context.getExternalFilesDir(null) ?: context.filesDir, STAGING_DIR).apply { mkdirs() }

        /** The folder the user chose for a music or a video download. */
        suspend fun savedLocation(isMusic: Boolean): DownloadLocation =
            DownloadLocation.forDownload(
                isMusic = isMusic,
                video = preferences.downloadLocation.first(),
                music = preferences.musicDownloadLocation.first(),
            )

        /** Where a [fileType] download to [location] is written. Touches the disk: call it off the main thread. */
        fun resolveDestination(
            fileType: DownloadFileType,
            location: DownloadLocation = DownloadLocation.DEFAULT,
        ): DownloadDestination =
            resolveDownloadDestination(
                chosen = location,
                defaults = defaultDirectories(fileType),
                staging = stagingDirectory(),
                isUsableDirectory = ::isUsableDirectory,
                hasTreeAccess = { DownloadFiles.hasTreeAccess(context, it) },
            )

        private fun defaultDirectories(fileType: DownloadFileType): List<File> =
            buildList {
                if (fileType == DownloadFileType.AUDIO) {
                    publicDirectory(Environment.DIRECTORY_MUSIC, AUDIO_DIR)?.let(::add)
                    publicDirectory(Environment.DIRECTORY_DOWNLOADS, AUDIO_DIR)?.let(::add)
                    context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.let { add(File(it, AUDIO_DIR)) }
                } else {
                    publicDirectory(Environment.DIRECTORY_DOWNLOADS, VIDEO_DIR)?.let(::add)
                    if (hasAllFilesAccess()) publicDirectory(Environment.DIRECTORY_MOVIES, VIDEO_DIR)?.let(::add)
                    context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.let { add(File(it, VIDEO_DIR)) }
                }
                add(File(context.filesDir, INTERNAL_DIR))
            }

        private fun publicDirectory(
            type: String,
            folder: String,
        ): File? = runCatching { File(Environment.getExternalStoragePublicDirectory(type), folder) }.getOrNull()

        private fun isUsableDirectory(directory: File): Boolean =
            runCatching { (directory.mkdirs() || directory.isDirectory) && directory.canWrite() }.getOrDefault(false)

        // ===== Database Operations =====

        /** All downloads with their items */
        val allDownloads: Flow<List<DownloadWithItems>> = downloadDao.getAllDownloadsWithItems()

        /** Only video downloads */
        val videoDownloads: Flow<List<DownloadWithItems>> = downloadDao.getVideoDownloads()

        /** Only audio-only downloads */
        val audioOnlyDownloads: Flow<List<DownloadWithItems>> = downloadDao.getAudioOnlyDownloads()

        /** Active (in-progress/pending/paused) downloads */
        val activeDownloads: Flow<List<DownloadWithItems>> = downloadDao.getActiveDownloads()

        /**
         * Legacy compatibility — exposes only COMPLETED downloads as DownloadedVideo list.
         * Used by DownloadsScreen and VideoPlayerViewModel for offline playback.
         * Only includes downloads with at least one COMPLETED item and an existing file.
         */
        val downloadedVideos: Flow<List<DownloadedVideo>>
            get() =
                allDownloads
                    .map { list ->
                        list
                            .filter { dwi ->
                                dwi.overallStatus == DownloadItemStatus.COMPLETED &&
                                    !dwi.isAudioOnly &&
                                    dwi.primaryFilePath?.let { DownloadFiles.exists(context, it) } == true
                            }.map { toDownloadedVideo(it) }
                    }.flowOn(Dispatchers.IO)

        /** Save a new download with its items, replacing the file rows of an earlier attempt. */
        suspend fun saveDownload(
            video: Video,
            items: List<DownloadItemEntity>,
        ) {
            downloadDao.replaceDownload(
                DownloadEntity(
                    videoId = video.id,
                    title = video.title,
                    uploader = video.channelName,
                    duration = video.duration.toLong(),
                    thumbnailUrl = video.thumbnailUrl,
                    createdAt = System.currentTimeMillis(),
                ),
                items,
            )
        }

        /** Insert a download item and return its generated ID */
        suspend fun insertItem(item: DownloadItemEntity): Int = downloadDao.insertItem(item).toInt()

        /** Update download progress */
        suspend fun updateProgress(
            itemId: Int,
            downloadedBytes: Long,
            status: DownloadItemStatus,
        ) {
            downloadDao.updateProgress(itemId, downloadedBytes, status)
        }

        /** Update download item with full info including totalBytes */
        suspend fun updateItemFull(
            itemId: Int,
            downloadedBytes: Long,
            totalBytes: Long,
            status: DownloadItemStatus,
        ) {
            downloadDao.updateItemFull(itemId, downloadedBytes, totalBytes, status)
        }

        /** Update item status */
        suspend fun updateStatus(
            itemId: Int,
            status: DownloadItemStatus,
        ) {
            downloadDao.updateStatus(itemId, status)
        }

        /** Update all items for a video */
        suspend fun updateAllItemsStatus(
            videoId: String,
            status: DownloadItemStatus,
        ) {
            downloadDao.updateAllItemsStatus(videoId, status)
        }

        /** Check if a video is downloaded */
        suspend fun isDownloaded(videoId: String): Boolean = downloadDao.isDownloaded(videoId)

        /** Persist SponsorBlock segments JSON for a downloaded video. */
        suspend fun saveSponsorBlockData(
            videoId: String,
            json: String,
        ) {
            downloadDao.updateSponsorBlockData(videoId, json)
        }

        /** Retrieve the stored SponsorBlock segments JSON, or null if not available. */
        suspend fun getSponsorBlockData(videoId: String): String? = downloadDao.getSponsorBlockData(videoId)

        /** Get download with items */
        suspend fun getDownloadWithItems(videoId: String): DownloadWithItems? = downloadDao.getDownloadWithItems(videoId)

        /** The finished video download of [videoId] when its file is still on disk. */
        suspend fun findLocalCopy(videoId: String): DownloadedVideo? =
            withContext(Dispatchers.IO) {
                downloadDao
                    .getDownloadWithItems(videoId)
                    ?.takeIf { it.overallStatus == DownloadItemStatus.COMPLETED && !it.isAudioOnly }
                    ?.takeIf { download -> download.primaryFilePath?.let { DownloadFiles.exists(context, it) } == true }
                    ?.let(::toDownloadedVideo)
            }

        /** Path of the finished video download of [videoId] when its file is still on disk. */
        suspend fun localCopyPath(videoId: String): String? = findLocalCopy(videoId)?.filePath

        /** Points a finished item at where its file ended up, such as a document in a picked folder. */
        suspend fun updateItemLocation(
            itemId: Int,
            filePath: String,
            fileName: String,
        ) {
            downloadDao.updateItemLocation(itemId, filePath, fileName)
        }

        /**
         * Removes [videoId]'s row and partial files before returning, unlike [deleteDownload], so a
         * retry that writes to the same paths can't have its new file deleted behind it.
         */
        suspend fun discardForRetry(videoId: String) =
            withContext(Dispatchers.IO) {
                val download = downloadDao.getDownloadWithItems(videoId) ?: return@withContext
                val filePaths = download.items.flatMap { artifactPathsFor(it.filePath) }.distinct()
                downloadDao.deleteDownload(videoId)
                filePaths.forEach { deleteFileFromDisk(it) }
            }

        /** Delete download and its files from disk.
         *
         * Based on NewPipe's deletion order:
         *  1. Collect file paths                    (before DB removal)
         *  2. Guard paths against scanner re-insert (ConcurrentHashMap set)
         *  3. Delete DB entry                       (UI disappears instantly via Room Flow)
         *  4. Delete files in app-scoped ioScope    (immune to ViewModel back-press cancellation)
         *  5. Notify MediaScanner each file is gone (removes stale index on Android 10+)
         */
        suspend fun deleteDownload(videoId: String): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    val download =
                        downloadDao.getDownloadWithItems(videoId)
                            ?: return@withContext false

                    val subtitleFile = DownloadRequest.decode(download.download.requestJson)?.subtitleFile
                    val filePaths = (download.items.flatMap { artifactPathsFor(it.filePath) } + listOfNotNull(subtitleFile)).distinct()
                    val thumbPath = download.download.thumbnailPath

                    recentlyDeletedPaths.addAll(filePaths)

                    downloadDao.deleteDownload(videoId)
                    // Before returning: a re-download queued right after reuses these staging names.
                    DownloadStaging(stagingDirectory(), videoId).clear()

                    ioScope.launch {
                        val left = mutableListOf<String>()
                        filePaths.forEach { path ->
                            val fileGone = deleteFileFromDisk(path)
                            if (fileGone) {
                                recentlyDeletedPaths.remove(path)
                                tombstonePrefs.edit().remove(path).apply()
                                if (!DownloadFiles.isDocument(path)) MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
                                Log.d(TAG, "Deleted: $path")
                            } else {
                                tombstonePrefs.edit().putBoolean(path, true).apply()
                                Log.w(TAG, "File not deleted (kept in guard + tombstoned): $path")
                                if (!DownloadFiles.isDocument(path)) left += path
                            }
                        }
                        if (left.isNotEmpty()) _undeletable.tryEmit(left)
                        thumbPath?.let { tp ->
                            try {
                                File(tp).takeIf { it.exists() }?.delete()
                            } catch (_: Exception) {
                            }
                        }
                        offlineSubtitleStore.delete(videoId)
                    }
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete download: $videoId", e)
                    false
                }
            }

        /**
         * Delete every non-completed download row and its partial artifacts.
         * Completed downloads are never touched.
         */
        suspend fun deleteIncompleteDownloads(): Int =
            withContext(Dispatchers.IO) {
                val incomplete =
                    downloadDao
                        .getAllDownloadsWithItemsOnce()
                        .filter { download ->
                            download.items.isEmpty() ||
                                download.items.any { it.status != DownloadItemStatus.COMPLETED }
                        }

                var deleted = 0
                incomplete.forEach { download ->
                    if (deleteDownload(download.download.videoId)) deleted++
                }
                deleted
            }

        private fun artifactPathsFor(path: String): List<String> =
            if (DownloadFiles.isDocument(path)) listOf(path) else listOf(path, "$path.video.tmp", "$path.audio.tmp")

        /**
         * Delete a single file from disk.
         * Returns true if the file is confirmed gone (never existed, successfully deleted,
         * or removed via MediaStore fallback on Android Q+).
         */
        private fun deleteFileFromDisk(path: String): Boolean {
            if (DownloadFiles.isDocument(path)) return DownloadFiles.deleteDocument(context, path)
            val file = File(path)
            if (!file.exists()) return true
            if (file.delete()) return true

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val contentUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    context.contentResolver
                        .query(
                            contentUri,
                            arrayOf(MediaStore.MediaColumns._ID),
                            "${MediaStore.MediaColumns.DATA} = ?",
                            arrayOf(path),
                            null,
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val id = cursor.getLong(0)
                                val fileUri = ContentUris.withAppendedId(contentUri, id)
                                if (context.contentResolver.delete(fileUri, null, null) > 0) {
                                    return !file.exists()
                                }
                            }
                        }
                } catch (e: Exception) {
                    Log.w(TAG, "MediaStore delete fallback failed for: $path", e)
                }
            }

            return !file.exists()
        }

        /** Legacy compat — same as deleteDownload */
        suspend fun removeDownloadedVideo(videoId: String) {
            deleteDownload(videoId)
        }

        /** Get total storage used by downloads */
        suspend fun getTotalDownloadSize(): Long = downloadDao.getTotalDownloadSize()

        /** Legacy compatibility: convert DownloadWithItems to DownloadedVideo for existing UI */
        fun toDownloadedVideo(dwi: DownloadWithItems): DownloadedVideo =
            DownloadedVideo(
                video =
                    Video(
                        id = dwi.download.videoId,
                        title = dwi.download.title,
                        channelName = dwi.download.uploader,
                        channelId = dwi.download.channelId,
                        thumbnailUrl = dwi.download.thumbnailPath?.let { "file://$it" } ?: dwi.download.thumbnailUrl,
                        duration = dwi.download.duration.toInt(),
                        viewCount = dwi.download.viewCount,
                        uploadDate = "",
                        description = dwi.download.description,
                        likeCount = dwi.download.likeCount,
                    ),
                filePath = dwi.primaryFilePath ?: "",
                downloadedAt = dwi.download.createdAt,
                fileSize = dwi.totalSize,
                downloadId = dwi.download.createdAt,
                quality = dwi.items.firstOrNull()?.quality ?: "Unknown",
                isAudioOnly = dwi.isAudioOnly,
            )

        /** Generate a safe filename from title and quality.
         *
         * Supports international characters (Arabic, Chinese, Japanese, etc.) by using
         * Unicode-aware character classes instead of ASCII-only ranges.
         * Only characters that are illegal in filenames on Android/FAT filesystems
         * are replaced with underscores.
         */
        fun generateFileName(
            title: String,
            quality: String,
            extension: String = "mp4",
        ): String {
            val safeTitle =
                title
                    .replace(Regex("[^\\p{L}\\p{M}\\p{N}\\s._-]"), "_")
                    .replace(Regex("\\s+"), " ")
                    .replace(Regex("_+"), "_")
                    .trim('_', ' ')
                    .take(100)
                    .ifEmpty { "video" }
            return "${safeTitle}_$quality.$extension"
        }

        /** Whether [path] belongs to a download being deleted, which a folder scan must not bring back. */
        internal fun isBeingDeleted(path: String): Boolean = path in recentlyDeletedPaths

        /**
         * True when [path] is a file the user deleted but that could not be removed at the time. The
         * removal is retried, and the file is never recovered as a download.
         */
        internal fun retryTombstonedDelete(path: String): Boolean {
            if (!tombstonePrefs.contains(path)) return false
            if (deleteFileFromDisk(path)) tombstonePrefs.edit().remove(path).apply()
            return true
        }
    }
