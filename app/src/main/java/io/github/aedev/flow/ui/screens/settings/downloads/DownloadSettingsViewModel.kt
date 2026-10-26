package io.github.aedev.flow.ui.screens.settings.downloads

import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.AutoDownloadMode
import io.github.aedev.flow.data.local.DEFAULT_CONCURRENT_DOWNLOADS
import io.github.aedev.flow.data.local.DownloadDialogStyle
import io.github.aedev.flow.data.local.MusicAudioQuality
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.VideoCodec
import io.github.aedev.flow.data.local.VideoQuality
import io.github.aedev.flow.data.local.entity.DownloadFileType
import io.github.aedev.flow.data.video.DownloadRecoveryScanner
import io.github.aedev.flow.data.video.VideoDownloadManager
import io.github.aedev.flow.data.video.downloader.work.DownloadController
import io.github.aedev.flow.data.video.storage.DownloadDestination
import io.github.aedev.flow.data.video.storage.DownloadFiles
import io.github.aedev.flow.data.video.storage.DownloadLocation
import io.github.aedev.flow.ui.screens.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** Where downloads go: video or music. */
enum class DownloadTarget { VIDEO, MUSIC }

/** Free and total bytes on the volume a download location lives on. */
@Immutable
data class StorageStats(
    val freeBytes: Long,
    val totalBytes: Long,
) {
    val usedFraction: Float get() = if (totalBytes > 0) (totalBytes - freeBytes).toFloat() / totalBytes else 0f
}

/**
 * One download location as the settings page shows it. [chosen] is what the user picked (null for
 * the default), [saveFolder] where files actually go, and [notWritable] that the choice could not be
 * used, so [saveFolder] is a default folder instead.
 */
@Immutable
data class LocationUi(
    val chosen: String?,
    val saveFolder: String,
    val notWritable: Boolean,
    val defaultFolder: String,
)

@Immutable
data class DownloadLocationsUi(
    val video: LocationUi,
    val music: LocationUi,
    val storage: StorageStats?,
)

/** The "Find earlier downloads" row's state once it has been tapped. */
sealed interface EarlierDownloadsSearch {
    data object Running : EarlierDownloadsSearch

    data class Found(
        val count: Int,
    ) : EarlierDownloadsSearch
}

@HiltViewModel
class DownloadSettingsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val preferences: PlayerPreferences,
        private val downloadManager: VideoDownloadManager,
        private val downloadController: DownloadController,
        private val recoveryScanner: DownloadRecoveryScanner,
    ) : SettingsViewModel() {
        private val refreshTick = MutableStateFlow(0)

        val quickQuality = preferences.defaultDownloadQuality.asState(VideoQuality.Q_720P)
        val codec = preferences.defaultDownloadCodec.asState(VideoCodec.AUTO)
        val musicQuality = preferences.musicDownloadQuality.asState(MusicAudioQuality.HIGH)
        val menuStyle = preferences.downloadDialogStyle.asState(DownloadDialogStyle.FULL)
        val askEveryTime = preferences.downloadAskEveryTime.asState(true)
        val storageCard = preferences.downloadsStorageCard.asState(true)
        val confirmDelete = preferences.downloadsConfirmDelete.asState(true)
        val wifiOnly = preferences.downloadOverWifiOnly.asState(false)
        val autoDownloadLikes = preferences.autoDownloadLikedMusic.asState(false)
        val autoDownloadOpened = preferences.autoDownloadOpenedVideos.asState(AutoDownloadMode.OFF)
        val subtitleFile = preferences.downloadSubtitleFile.asState(false)
        val threads = preferences.downloadThreads.asState(DEFAULT_THREADS)
        val concurrentDownloads = preferences.concurrentDownloads.asState(DEFAULT_CONCURRENT_DOWNLOADS)
        val retagStatus = downloadController.retagStatus.asState(null)

        init {
            downloadController.scheduleRetagOnce()
        }

        /** Both locations resolved the way a download resolves them, off the main thread. */
        val locations =
            combine(preferences.downloadLocation, preferences.musicDownloadLocation, refreshTick) { video, music, _ ->
                val videoUi = locationUi(DownloadFileType.VIDEO, chosen = video, fallback = DownloadLocation.DEFAULT)
                DownloadLocationsUi(
                    video = videoUi,
                    music = locationUi(DownloadFileType.AUDIO, chosen = DownloadLocation.forDownload(true, video, music), fallback = video),
                    storage = statsFor(video.path ?: videoUi.saveFolder),
                )
            }.flowOn(Dispatchers.IO)
                .asState(null)

        /** Null until the row is tapped. */
        private val _earlierDownloads = MutableStateFlow<EarlierDownloadsSearch?>(null)
        val earlierDownloads: StateFlow<EarlierDownloadsSearch?> = _earlierDownloads.asStateFlow()

        fun findEarlierDownloads() {
            if (_earlierDownloads.value == EarlierDownloadsSearch.Running) return
            _earlierDownloads.value = EarlierDownloadsSearch.Running
            viewModelScope.launch { _earlierDownloads.value = EarlierDownloadsSearch.Found(recoveryScanner.scanAndRecoverDownloads()) }
        }

        /** Reads the folders again, as storage access may have changed while the page was away. */
        fun refresh() = refreshTick.update { it + 1 }

        fun downloadsPath(): String? =
            runCatching {
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    APP_FOLDER,
                ).absolutePath
            }.getOrNull()

        fun internalPath(): String = File(context.filesDir, INTERNAL_FOLDER).absolutePath

        /** Saves [location] for [target], creating its folder first; null goes back to the default. */
        fun setLocation(
            target: DownloadTarget,
            location: DownloadLocation?,
        ) = write {
            val next = location ?: DownloadLocation.DEFAULT
            val video = preferences.downloadLocation.first()
            val music = preferences.musicDownloadLocation.first()
            val (previous, other) = if (target == DownloadTarget.MUSIC) music to video else video to music
            next.path?.let { path -> withContext(Dispatchers.IO) { runCatching { File(path).mkdirs() } } }
            if (target == DownloadTarget.MUSIC) preferences.setMusicDownloadLocation(next) else preferences.setDownloadLocation(next)
            previous.treeUri
                ?.takeIf { it != next.treeUri && it != other.treeUri }
                ?.let { DownloadFiles.releaseTree(context, it) }
        }

        fun setQuickQuality(value: VideoQuality) = write { preferences.setDefaultDownloadQuality(value) }

        fun setCodec(value: VideoCodec) = write { preferences.setDefaultDownloadCodec(value) }

        fun setMusicQuality(value: MusicAudioQuality) = write { preferences.setMusicDownloadQuality(value) }

        fun setMenuStyle(value: DownloadDialogStyle) = write { preferences.setDownloadDialogStyle(value) }

        fun setAskEveryTime(value: Boolean) = write { preferences.setDownloadAskEveryTime(value) }

        fun setStorageCard(value: Boolean) = write { preferences.setDownloadsStorageCard(value) }

        fun setConfirmDelete(value: Boolean) = write { preferences.setDownloadsConfirmDelete(value) }

        // Queued downloads wait on the network the setting allowed when they were queued, so it is re-applied.
        fun setAutoDownloadLikes(value: Boolean) = write { preferences.setAutoDownloadLikedMusic(value) }

        fun setAutoDownloadOpened(value: AutoDownloadMode) = write { preferences.setAutoDownloadOpenedVideos(value) }

        fun setWifiOnly(value: Boolean) =
            write {
                preferences.setDownloadOverWifiOnly(value)
                downloadController.applyNetworkPolicy()
            }

        fun setSubtitleFile(value: Boolean) = write { preferences.setDownloadSubtitleFile(value) }

        fun setThreads(value: Int) = write { preferences.setDownloadThreads(value) }

        fun setConcurrentDownloads(value: Int) = write { preferences.setConcurrentDownloads(value) }

        private fun locationUi(
            fileType: DownloadFileType,
            chosen: DownloadLocation,
            fallback: DownloadLocation,
        ): LocationUi {
            val destination = downloadManager.resolveDestination(fileType, chosen)
            return LocationUi(
                chosen = chosen.takeIf { it != fallback }?.let(::pickedFolder),
                saveFolder = folderOf(destination, chosen),
                notWritable = destination.fellBack,
                defaultFolder = folderOf(downloadManager.resolveDestination(fileType, fallback), fallback),
            )
        }

        private fun folderOf(
            destination: DownloadDestination,
            location: DownloadLocation,
        ): String = destination.exportTreeUri?.let { pickedFolder(location) } ?: destination.directory.absolutePath

        private fun pickedFolder(location: DownloadLocation): String? =
            location.path?.takeIf { it.isNotBlank() } ?: location.treeUri?.let { DownloadFiles.treePath(it) ?: it }

        private fun statsFor(path: String): StorageStats? =
            runCatching {
                val directory = File(path).apply { if (!exists()) mkdirs() }
                val stat = StatFs(directory.path)
                StorageStats(freeBytes = stat.availableBytes, totalBytes = stat.totalBytes)
            }.getOrNull()

        companion object {
            const val DEFAULT_THREADS = 3
            private const val APP_FOLDER = "Flow"
            private const val INTERNAL_FOLDER = "downloads"
        }
    }
