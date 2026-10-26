package io.github.aedev.flow.ui.screens.library

import android.content.Context
import android.content.IntentSender
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.dao.DownloadCollectionSummary
import io.github.aedev.flow.data.local.entity.DownloadFileType
import io.github.aedev.flow.data.local.entity.DownloadItemStatus
import io.github.aedev.flow.data.local.entity.DownloadWithItems
import io.github.aedev.flow.data.music.DownloadedTrack
import io.github.aedev.flow.data.video.DownloadProgressUpdate
import io.github.aedev.flow.data.video.DownloadRecoveryScanner
import io.github.aedev.flow.data.video.DownloadedVideo
import io.github.aedev.flow.data.video.VideoDownloadManager
import io.github.aedev.flow.data.video.downloader.collection.DownloadedCollections
import io.github.aedev.flow.data.video.downloader.work.DownloadController
import io.github.aedev.flow.data.video.storage.deleteRequestFor
import io.github.aedev.flow.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import io.github.aedev.flow.data.music.DownloadManager as MusicDownloadManager

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DownloadsViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val videoDownloadManager: VideoDownloadManager,
        private val recoveryScanner: DownloadRecoveryScanner,
        private val musicDownloadManager: MusicDownloadManager,
        private val downloadController: DownloadController,
        private val collections: DownloadedCollections,
        preferences: PlayerPreferences,
        @param:ApplicationScope private val applicationScope: CoroutineScope,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(DownloadsUiState())
        val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

        // Hidden from the lists while their deletion runs; each id drops out once the row is gone.
        private val pendingDeleteIds = MutableStateFlow<Set<String>>(emptySet())

        // Off the lists but still on disk while the Undo snackbar shows, each id marked true for a song.
        private val awaitingUndo = MutableStateFlow<Map<String, Boolean>>(emptyMap())
        private val hiddenIds = combine(pendingDeleteIds, awaitingUndo) { pending, undoable -> pending + undoable.keys }
        private val _deleted = MutableSharedFlow<Int>(extraBufferCapacity = 1)

        /** How many downloads a delete just took off the lists, for the screen to offer Undo. */
        val deleted: SharedFlow<Int> = _deleted.asSharedFlow()

        val showStorageCard: StateFlow<Boolean> =
            preferences.downloadsStorageCard.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
        val confirmDelete: StateFlow<Boolean> =
            preferences.downloadsConfirmDelete.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
        private val allVideos = MutableStateFlow<List<DownloadedVideo>>(emptyList())
        private val allTracks = MutableStateFlow<List<DownloadedTrack>>(emptyList())
        private val query = MutableStateFlow("")
        private val sort = MutableStateFlow(DownloadSort.NEWEST)

        // Each pull to refresh reads the lists again, so a file deleted outside Flow drops out and
        // the free space is measured again, not only when the database changes.
        private val refreshTick = MutableStateFlow(0)

        init {
            observeDownloads()
            downloadController.ensureQueueRunning()
            downloadController.scheduleRetagOnce()
            scanIfMoreIsReadable()
        }

        fun setQuery(value: String) {
            query.value = value
            _uiState.update { it.copy(query = value) }
        }

        fun setSort(value: DownloadSort) {
            sort.value = value
            _uiState.update { it.copy(sort = value) }
        }

        private fun observeDownloads() {
            viewModelScope.launch {
                combine(refreshTick.flatMapLatest { musicDownloadManager.downloadedTracks }, hiddenIds) { tracks, pending ->
                    tracks.filter { it.track.videoId !in pending }
                }.collect { allTracks.value = it }
            }
            viewModelScope.launch {
                combine(refreshTick.flatMapLatest { videoDownloadManager.downloadedVideos }, hiddenIds) { videos, pending ->
                    videos.filter { it.video.id !in pending }
                }.collect { allVideos.value = it }
            }
            viewModelScope.launch {
                combine(allVideos, allTracks, query, sort) { videos, tracks, text, order ->
                    ShownDownloads(
                        videos = videos.filterAndSort(text, order),
                        tracks = tracks.filterAndSortTracks(text, order),
                        videoCount = videos.size,
                        musicCount = tracks.size,
                    )
                }.collect { shown ->
                    _uiState.update {
                        it.copy(
                            downloadedVideos = shown.videos,
                            downloadedMusic = shown.tracks,
                            totalVideoCount = shown.videoCount,
                            totalMusicCount = shown.musicCount,
                        )
                    }
                }
            }
            viewModelScope.launch {
                combine(allVideos, allTracks, refreshTick) { videos, tracks, _ ->
                    videos.sumOf { it.fileSize } to
                        tracks.sumOf { it.fileSize }
                }.collect { (videoBytes, musicBytes) ->
                    val free = freeBytes()
                    _uiState.update { it.copy(storage = DownloadStorage(videoBytes, musicBytes, free)) }
                }
            }
            viewModelScope.launch {
                combine(videoDownloadManager.allDownloads, pendingDeleteIds) { downloads, pending -> downloads to pending }
                    .collect { (downloads, pending) ->
                        val present = downloads.mapTo(HashSet()) { it.download.videoId }
                        if (!present.containsAll(pending)) pendingDeleteIds.update { it.intersect(present) }
                        val incomplete =
                            downloads.filter { it.download.videoId !in pending && it.overallStatus != DownloadItemStatus.COMPLETED }
                        _uiState.update { state ->
                            val activeIds = incomplete.mapTo(HashSet()) { it.download.videoId }
                            state.copy(
                                incompleteVideoDownloads = incomplete.filterNot { it.isAudioOnly },
                                incompleteMusicDownloads = incomplete.filter { it.isAudioOnly },
                                mergingVideoIds = state.mergingVideoIds.intersect(activeIds),
                                progress = state.progress.filterKeys { it in activeIds },
                            )
                        }
                    }
            }
            viewModelScope.launch {
                collections.summaries.collect { summaries ->
                    val (music, videos) = summaries.partition { it.collection.kind.isMusic }
                    _uiState.update { it.copy(videoCollections = videos, musicCollections = music) }
                }
            }
            viewModelScope.launch {
                videoDownloadManager.progressUpdates.collect { update ->
                    _uiState.update { state ->
                        state.copy(
                            progress = state.progress + (update.videoId to update),
                            mergingVideoIds =
                                if (update.isMerging) state.mergingVideoIds + update.videoId else state.mergingVideoIds - update.videoId,
                        )
                    }
                }
            }
        }

        /** Free space on the volume downloads are actually saved to, which may be a card or a picked folder. */
        private suspend fun freeBytes(): Long =
            withContext(Dispatchers.IO) {
                runCatching {
                    val folder =
                        videoDownloadManager.resolveDestination(
                            DownloadFileType.VIDEO,
                            videoDownloadManager.savedLocation(isMusic = false),
                        )
                    StatFs(folder.directory.path).availableBytes
                }.getOrDefault(0L)
            }

        /**
         * Takes [ids] off the lists at once; their files go when [commitDeletes] runs, unless
         * [undoDelete] comes first. A delete still waiting on Undo is committed before this one.
         */
        fun deleteDownloads(ids: Set<String>) {
            if (ids.isEmpty()) return
            commitDeletes()
            val musicIds = allTracks.value.mapTo(HashSet()) { it.track.videoId }
            awaitingUndo.value = ids.associateWith { it in musicIds }
            _deleted.tryEmit(ids.size)
        }

        fun undoDelete() {
            awaitingUndo.value = emptyMap()
        }

        /** Deletes what waits on Undo: when its snackbar goes, when the screen goes, or before the next delete. */
        fun commitDeletes() {
            val waiting = awaitingUndo.value
            if (waiting.isEmpty()) return
            pendingDeleteIds.update { it + waiting.keys }
            awaitingUndo.value = emptyMap()
            waiting.forEach { (id, isMusic) -> if (isMusic) deleteMusicDownload(id) else deleteVideoDownload(id) }
        }

        /** Cancels downloads still in the queue, with no Undo: the transfer stops at once. */
        fun cancelDownloads(ids: Set<String>) {
            pendingDeleteIds.update { it + ids }
            ids.forEach(::deleteVideoDownload)
        }

        override fun onCleared() {
            commitDeletes()
        }

        /** A finished download is deleted here; one still in the queue is cancelled, which stops it first. */
        private fun deleteVideoDownload(videoId: String) {
            applicationScope.launch {
                val download = videoDownloadManager.getDownloadWithItems(videoId)
                if (download?.overallStatus == DownloadItemStatus.COMPLETED) {
                    videoDownloadManager.deleteDownload(videoId)
                } else {
                    downloadController.cancel(videoId)
                }
            }
        }

        private fun deleteMusicDownload(videoId: String) {
            applicationScope.launch { musicDownloadManager.deleteDownload(videoId) }
        }

        fun pauseVideoDownload(videoId: String) {
            downloadController.pause(videoId)
        }

        fun resumeVideoDownload(videoId: String) {
            downloadController.resume(videoId)
        }

        fun retryVideoDownload(videoId: String) {
            downloadController.retry(videoId)
        }

        /** Cancels the incomplete downloads of one tab; each is stopped before its files are deleted. */
        fun removeIncompleteDownloads(audioOnly: Boolean) {
            val state = _uiState.value
            val ids = (if (audioOnly) state.incompleteMusicDownloads else state.incompleteVideoDownloads).map { it.download.videoId }
            if (ids.isEmpty()) return
            pendingDeleteIds.update { it + ids }
            ids.forEach { videoId -> downloadController.cancel(videoId) }
        }

        fun removeCollection(
            id: String,
            deleteFiles: Boolean,
        ) {
            viewModelScope.launch { collections.remove(id, deleteFiles) }
        }

        /** The system's delete prompt for files an earlier install owns, which Flow can't remove itself. */
        val deleteConsent: Flow<IntentSender> =
            videoDownloadManager.undeletable.mapNotNull { context.deleteRequestFor(it) }.flowOn(Dispatchers.IO)

        /** How many earlier downloads a scan brought back; sent when there were some, or when the viewer asked. */
        private val _recovered = MutableSharedFlow<Int>(extraBufferCapacity = 1)
        val recovered: SharedFlow<Int> = _recovered.asSharedFlow()

        fun rescan() = scan(reportNone = false)

        /** After media access is granted, which is when an earlier install's files become visible. */
        fun findEarlierDownloads() = scan(reportNone = true)

        /** On open and on return to the screen: the viewer may have granted access in system settings. */
        fun scanIfMoreIsReadable() {
            if (recoveryScanner.needsScan()) scan(reportNone = false)
        }

        private fun scan(reportNone: Boolean) {
            viewModelScope.launch {
                _uiState.update { it.copy(isScanning = true) }
                val count = recoveryScanner.scanAndRecoverDownloads()
                refreshTick.update { it + 1 }
                _uiState.update { it.copy(isScanning = false) }
                if (count > 0 || reportNone) _recovered.tryEmit(count)
            }
        }

        private data class ShownDownloads(
            val videos: List<DownloadedVideo>,
            val tracks: List<DownloadedTrack>,
            val videoCount: Int,
            val musicCount: Int,
        )
    }

data class DownloadsUiState(
    val downloadedVideos: List<DownloadedVideo> = emptyList(),
    val downloadedMusic: List<DownloadedTrack> = emptyList(),
    val totalVideoCount: Int = 0,
    val totalMusicCount: Int = 0,
    val incompleteVideoDownloads: List<DownloadWithItems> = emptyList(),
    val incompleteMusicDownloads: List<DownloadWithItems> = emptyList(),
    val videoCollections: List<DownloadCollectionSummary> = emptyList(),
    val musicCollections: List<DownloadCollectionSummary> = emptyList(),
    val progress: Map<String, DownloadProgressUpdate> = emptyMap(),
    val mergingVideoIds: Set<String> = emptySet(),
    val storage: DownloadStorage = DownloadStorage(),
    val query: String = "",
    val sort: DownloadSort = DownloadSort.NEWEST,
    val isScanning: Boolean = false,
)
