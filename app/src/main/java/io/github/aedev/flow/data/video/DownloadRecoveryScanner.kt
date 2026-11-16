package io.github.aedev.flow.data.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.dao.DownloadDao
import io.github.aedev.flow.data.local.entity.DownloadItemStatus
import io.github.aedev.flow.data.video.VideoDownloadManager.Companion.VIDEO_DIR
import io.github.aedev.flow.data.video.downloader.tags.DownloadTagReader
import io.github.aedev.flow.data.video.storage.DownloadCovers
import io.github.aedev.flow.data.video.storage.DownloadFiles
import io.github.aedev.flow.data.video.storage.DownloadTree
import io.github.aedev.flow.data.video.storage.StorageAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds video and audio files in the download folders, and the playlist and album folders inside
 * them, that the database does not know about (after a reinstall or a database wipe, for example)
 * and records them as completed downloads, so they show in Downloads and play offline. A file Flow
 * tagged comes back under its real video id, and a finished download whose file had gone missing
 * is pointed at it. Files already recorded are skipped, so it is safe to run repeatedly.
 */
@Singleton
class DownloadRecoveryScanner
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val downloadDao: DownloadDao,
        private val downloadManager: VideoDownloadManager,
        private val preferences: PlayerPreferences,
        private val tagReader: DownloadTagReader,
    ) {
        private data class Reach(
            val access: StorageAccess,
            val trees: Set<String>,
        )

        /** What the last scan in this process could read. */
        @Volatile
        private var scannedWith: Reach? = null

        /**
         * Whether a scan could find something the last one couldn't: none has run yet, or Flow may now
         * read more shared storage, as after the viewer grants media access or picks a folder again.
         */
        fun needsScan(): Boolean = scannedWith != reach()

        private fun reach() = Reach(StorageAccess.read(context), DownloadFiles.grantedTrees(context))

        /** Records every new file it finds and returns how many that was. */
        suspend fun scanAndRecoverDownloads(): Int =
            withContext(Dispatchers.IO) {
                scannedWith = reach()
                var recovered = 0
                try {
                    val chosenFolders = chosenFolders()
                    val exportedPaths = exportedDocumentPaths()
                    val roots = (chosenFolders.map(::File) + defaultFolders()).distinctBy { it.canonicalPath }

                    for (root in roots) {
                        if (!root.isDirectory) continue
                        root
                            .walkTopDown()
                            .maxDepth(COLLECTION_DEPTH)
                            .filter { it.isFile && RecoveredDownload.isMedia(it.name) }
                            .forEach { file ->
                                val isNew =
                                    recoverIfNew(
                                        file.absolutePath,
                                        file.name,
                                        file.length(),
                                        file.lastModified(),
                                        durationMs = null,
                                        exportedPaths,
                                    )
                                if (isNew) recovered++
                            }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) recovered += scanViaMediaStore(roots, exportedPaths)
                    recovered += scanPickedFolders(exportedPaths)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "scanAndRecoverDownloads failed", e)
                }
                recovered
            }

        private fun defaultFolders(): List<File> =
            buildList {
                listOf(Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_MOVIES, Environment.DIRECTORY_MUSIC).forEach { type ->
                    runCatching { add(File(Environment.getExternalStoragePublicDirectory(type), VIDEO_DIR)) }
                    context.getExternalFilesDir(type)?.let { add(File(it, VIDEO_DIR)) }
                }
            }

        /**
         * The same folders read through the system media index, which works with only
         * READ_MEDIA_VIDEO / READ_MEDIA_AUDIO when the files themselves cannot be listed.
         */
        @RequiresApi(Build.VERSION_CODES.Q)
        private suspend fun scanViaMediaStore(
            roots: List<File>,
            exportedPaths: Set<String>,
        ): Int =
            withContext(Dispatchers.IO) {
                var recovered = 0
                val projection =
                    arrayOf(
                        MediaStore.MediaColumns.DATA,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.SIZE,
                        MediaStore.MediaColumns.DURATION,
                        MediaStore.MediaColumns.DATE_MODIFIED,
                    )
                val prefixes = roots.map { it.canonicalPath + File.separator }
                for (collectionUri in listOf(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)) {
                    try {
                        context.contentResolver.query(collectionUri, projection, null, null, null)?.use { cursor ->
                            val dataIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                            val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                            val durIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
                            val modifiedIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                            while (cursor.moveToNext()) {
                                val filePath = cursor.getString(dataIdx) ?: continue
                                val fileName = cursor.getString(nameIdx) ?: continue
                                if (prefixes.none { filePath.startsWith(it) } || !RecoveredDownload.isMedia(fileName)) continue
                                val isNew =
                                    recoverIfNew(
                                        filePath,
                                        fileName,
                                        cursor.getLong(sizeIdx),
                                        cursor.getLong(modifiedIdx) * 1000,
                                        cursor.getLong(durIdx),
                                        exportedPaths,
                                    )
                                if (isNew) recovered++
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "scanViaMediaStore: query failed for $collectionUri", e)
                    }
                }
                recovered
            }

        /**
         * The picked folders read through their grant, which needs no media access: a reinstalled Flow
         * owns none of the files an earlier install saved, so the media index hides them from it.
         */
        private suspend fun scanPickedFolders(exportedPaths: Set<String>): Int {
            val granted = DownloadFiles.grantedTrees(context)
            var recovered = 0
            for (tree in chosenLocations().mapNotNull { it.treeUri }.distinct().filter { it in granted }) {
                for (file in DownloadTree.files(context, tree, COLLECTION_DEPTH)) {
                    if (!RecoveredDownload.isMedia(file.name)) continue
                    val path = DownloadFiles.documentPath(file.uri)
                    if (path != null && !isNewFile(path, exportedPaths)) continue
                    if (recoverIfNew(file.uri, file.name, file.sizeBytes, file.modifiedAt, durationMs = null, exportedPaths)) recovered++
                }
            }
            return recovered
        }

        private suspend fun chosenLocations() = listOf(preferences.downloadLocation.first(), preferences.musicDownloadLocation.first())

        private suspend fun chosenFolders(): List<String> =
            chosenLocations()
                .mapNotNull { location -> location.path?.takeIf { it.isNotBlank() } ?: location.treeUri?.let(DownloadFiles::treePath) }
                .distinct()

        // A file exported into a picked folder is stored as its document, yet a folder scan meets it by
        // its path; both spellings must count as already recorded.
        private suspend fun exportedDocumentPaths(): Set<String> =
            downloadDao
                .getAllDownloadsWithItemsOnce()
                .flatMap { it.items }
                .mapNotNull { item -> item.filePath.takeIf(DownloadFiles::isDocument)?.let(DownloadFiles::documentPath) }
                .toSet()

        private suspend fun isNewFile(
            filePath: String,
            exportedPaths: Set<String>,
        ): Boolean =
            filePath !in exportedPaths &&
                !downloadDao.existsByFilePath(filePath) &&
                !downloadManager.isBeingDeleted(filePath) &&
                !downloadManager.retryTombstonedDelete(filePath)

        private suspend fun recoverIfNew(
            filePath: String,
            fileName: String,
            sizeBytes: Long,
            createdAt: Long,
            durationMs: Long?,
            exportedPaths: Set<String>,
        ): Boolean {
            if (!isNewFile(filePath, exportedPaths)) return false
            val embedded = tagReader.read(uriOf(filePath))
            val tags = embedded?.flow
            val sourceUrl = embedded?.comment
            val videoId = RecoveredDownload.idFor(filePath, tags, sourceUrl)
            if (RecoveredDownload.knownIdFor(tags, sourceUrl) != null && !mayRecordUnder(videoId)) return false
            val isVideo = RecoveredDownload.VIDEO_EXTENSIONS.contains(fileName.substringAfterLast('.', "").lowercase())
            val probe = probe(filePath, wantsFrame = isVideo && embedded?.cover == null)
            val cover = (embedded?.cover ?: probe.frame)?.let { DownloadCovers.save(context, videoId, it) }
            val (download, item) =
                RecoveredDownload.rows(
                    FoundFile(filePath, fileName, sizeBytes, durationMs?.takeIf { it > 0 } ?: probe.durationMs, createdAt),
                    tags,
                    title = embedded?.title?.takeIf { it.isNotBlank() },
                    artist = embedded?.artist?.takeIf { it.isNotBlank() },
                    coverPath = cover,
                    sourceUrl = sourceUrl,
                )
            downloadDao.replaceDownload(download, listOf(item))
            Log.i(TAG, "Recovered '$fileName' as $videoId")
            return true
        }

        /**
         * A tagged file takes its video id's row only when there is none, or when that row is a
         * finished download whose file is gone; a second copy, or a download in progress, keeps its row.
         */
        private suspend fun mayRecordUnder(videoId: String): Boolean {
            val existing = downloadDao.getDownloadWithItems(videoId) ?: return true
            return existing.overallStatus == DownloadItemStatus.COMPLETED &&
                existing.items.none { DownloadFiles.exists(context, it.filePath) }
        }

        private class Probe(
            val durationMs: Long,
            val frame: ByteArray?,
        )

        private fun probe(
            filePath: String,
            wantsFrame: Boolean,
        ): Probe {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(context, uriOf(filePath))
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val frame =
                    if (wantsFrame) {
                        (
                            retriever.getFrameAtTime(FRAME_AT_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        )?.let { bitmap ->
                            try {
                                ByteArrayOutputStream()
                                    .also {
                                        bitmap.compress(
                                            Bitmap.CompressFormat.JPEG,
                                            FRAME_QUALITY,
                                            it,
                                        )
                                    }.toByteArray()
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    } else {
                        null
                    }
                Probe(duration, frame)
            } catch (e: Exception) {
                Log.w(TAG, "Could not read $filePath: ${e.message}")
                Probe(0L, null)
            } finally {
                runCatching { retriever.release() }
            }
        }

        private fun uriOf(path: String): Uri = if (DownloadFiles.isDocument(path)) Uri.parse(path) else Uri.fromFile(File(path))

        private companion object {
            const val TAG = "DownloadRecoveryScanner"

            // A download folder and the playlist and album folders directly inside it.
            const val COLLECTION_DEPTH = 2
            const val FRAME_AT_US = 1_000_000L
            const val FRAME_QUALITY = 85
        }
    }
