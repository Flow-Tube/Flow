package io.github.aedev.flow.ui.screens.playlists

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.R
import io.github.aedev.flow.data.engagement.LikedMediaUseCase
import io.github.aedev.flow.data.local.LikedVideoInfo
import io.github.aedev.flow.data.local.LikedVideosRepository
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.data.local.PlaylistSwipes
import io.github.aedev.flow.data.local.SavedPlaylistSyncStore
import io.github.aedev.flow.data.local.WatchLaterCleanup
import io.github.aedev.flow.data.local.dao.DownloadCollectionSummary
import io.github.aedev.flow.data.migration.WatchLaterMetadataMigrator
import io.github.aedev.flow.data.model.PlaylistInfo
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.YouTubeMusicService
import io.github.aedev.flow.data.playlist.PlaylistFileCodec
import io.github.aedev.flow.data.playlist.PlaylistTransfer
import io.github.aedev.flow.data.repository.PlaylistQueueSources
import io.github.aedev.flow.data.repository.YouTubePlaylistRepository
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.data.video.BackgroundDownloadQueuer
import io.github.aedev.flow.data.video.DownloadBatch
import io.github.aedev.flow.data.video.downloader.collection.DownloadedCollections
import io.github.aedev.flow.data.video.downloader.collection.OfflineCollection
import io.github.aedev.flow.player.queue.QueuePageSource
import io.github.aedev.flow.ui.components.library.PlaylistSortOrder
import io.github.aedev.flow.ui.components.library.sortedForPlaylist
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionUndo
import io.github.aedev.flow.utils.NetworkState
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import javax.inject.Inject

private const val ENRICHMENT_STUB_LIMIT = 50
private const val ENRICHMENT_CHUNK_SIZE = 5
private const val ENRICHMENT_CHUNK_DELAY_MS = 300L
private const val SHARING_TIMEOUT_MS = 5_000L

@HiltViewModel
class PlaylistDetailViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val repository: PlaylistRepository,
        private val youTubeRepository: YouTubeRepository,
        private val playlistRepository: YouTubePlaylistRepository,
        private val playerPreferences: PlayerPreferences,
        private val downloadQueuer: BackgroundDownloadQueuer,
        private val watchLaterMetadataMigrator: WatchLaterMetadataMigrator,
        private val watchLaterCleanup: WatchLaterCleanup,
        private val transfer: PlaylistTransfer,
        private val likedVideos: LikedVideosRepository,
        private val likedMedia: LikedMediaUseCase,
        private val collections: DownloadedCollections,
        savedPlaylistChecks: SavedPlaylistSyncStore,
        private val queueSources: PlaylistQueueSources,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        val playlistId: String = checkNotNull(savedStateHandle["playlistId"])
        private val sharing = SharingStarted.WhileSubscribed(stopTimeoutMillis = SHARING_TIMEOUT_MS)
        private val attemptedEnrichment = HashSet<String>()
        private val enrichSemaphore = Semaphore(1)

        private val _uiState = MutableStateFlow(PlaylistDetailUiState())
        val uiState: StateFlow<PlaylistDetailUiState> = _uiState.asStateFlow()

        private val _messages = Channel<PlaylistUiMessage>(Channel.BUFFERED)
        val messages: Flow<PlaylistUiMessage> = _messages.receiveAsFlow()

        val sortOrder: StateFlow<PlaylistSortOrder> =
            combine(
                playerPreferences.playlistSortOrder(playlistId),
                _uiState.map { it.isLocalPlaylist to it.isLikes }.distinctUntilChanged(),
            ) { stored, (isLocal, isLikes) ->
                PlaylistSortOrder
                    .fromStorageValue(stored)
                    .takeIf { it in PlaylistSortOrder.availableFor(isLocal, isLikes) } ?: PlaylistSortOrder.defaultFor(isLikes)
            }.stateIn(viewModelScope, sharing, PlaylistSortOrder.MANUAL)

        val playlistSwipes: StateFlow<PlaylistSwipes> =
            playerPreferences.playlistSwipeActions.stateIn(
                viewModelScope,
                sharing,
                PlaylistSwipes(),
            )

        val sortedVideos: StateFlow<List<Video>> =
            combine(_uiState.map { it.videos }, sortOrder) { videos, order ->
                videos.sortedForPlaylist(order)
            }.flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, sharing, emptyList())

        val userCreatedPlaylists: StateFlow<List<PlaylistInfo>> =
            repository
                .getUserCreatedVideoPlaylistsFlow()
                .stateIn(viewModelScope, sharing, emptyList())

        private val remote = RemotePlaylistSession(viewModelScope, playlistId, playlistRepository, _uiState)

        private val downloads =
            PlaylistDownloads(viewModelScope, playlistId, downloadQueuer, playlistRepository, collections) { _messages.send(it) }

        private val savedRefresh = SavedPlaylistRefresh(playlistId, repository, playlistRepository, savedPlaylistChecks)

        /** This playlist as a downloaded collection, or null when it was never downloaded as one. */
        val downloadedCollection: StateFlow<DownloadCollectionSummary?> get() = downloads.downloaded

        /** The running or just-finished "Download all" for this playlist. */
        val downloadBatch: StateFlow<DownloadBatch?> get() = downloads.batch

        /** Videos added to this downloaded playlist since it was last downloaded. */
        val newSinceDownload: StateFlow<Int> =
            downloads.newVideos(
                _uiState
                    .map { state ->
                        state.videos.takeUnless { state.isLoading || state.hasMorePages }
                    }.distinctUntilChanged(),
            )

        fun downloadNew() {
            viewModelScope.launch { downloads.downloadNew(loadedState()) }
        }

        init {
            loadPlaylist()
        }

        fun setSortOrder(order: PlaylistSortOrder) {
            if (order != PlaylistSortOrder.MANUAL) remote.loadAll()
            viewModelScope.launch {
                playerPreferences.setPlaylistSortOrder(playlistId, order.storageValue)
            }
        }

        fun retry() {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            loadPlaylist()
        }

        /** Loads the next page of a YouTube playlist as its list nears the end. */
        fun loadMore() = remote.loadMore()

        /** Loads every page of a YouTube playlist, for a feature that needs the whole list. */
        fun loadAll() {
            remote.loadAll()
        }

        fun saveToLibrary() {
            viewModelScope.launch {
                remote.awaitAll()
                val state = _uiState.value
                val thumbnailUrl = state.thumbnailUrl.ifEmpty { state.videos.firstOrNull()?.thumbnailUrl ?: "" }
                if (state.isFromMusic) {
                    repository.saveExternalMusicPlaylist(playlistId, state.playlistName, state.description, thumbnailUrl)
                } else {
                    repository.saveExternalVideoPlaylist(playlistId, state.playlistName, state.description, thumbnailUrl)
                }
                repository.addVideosToPlaylist(playlistId, state.videos)
                _uiState.update { it.copy(isLocalPlaylist = true, isSaved = true, isInMusicLibrary = state.isFromMusic) }
                val saved = if (state.isFromMusic) R.string.toast_saved_playlist_to_music_library else R.string.ui_playlist_saved_to_library
                _messages.send(PlaylistUiMessage(stringRes = saved))
            }
        }

        /** Moves this stored playlist between the video and music libraries. */
        fun moveToOtherLibrary() {
            val toMusic = _uiState.value.isInMusicLibrary?.not() ?: return
            viewModelScope.launch {
                repository.moveToLibrary(playlistId, isMusic = toMusic)
                _uiState.update { it.copy(isInMusicLibrary = toMusic) }
                _messages.send(
                    PlaylistUiMessage(stringRes = if (toMusic) R.string.playlist_moved_to_music else R.string.playlist_moved_to_videos),
                )
            }
        }

        fun unsaveFromLibrary() {
            viewModelScope.launch {
                repository.unsaveExternalPlaylist(playlistId)
                _uiState.update { it.copy(isLocalPlaylist = false, isSaved = false, isInMusicLibrary = null) }
                _messages.send(PlaylistUiMessage(stringRes = R.string.ui_playlist_removed_from_library))
            }
        }

        fun removeVideo(videoId: String) = removeVideos(setOf(videoId))

        fun removeVideos(videoIds: Set<String>) {
            if (videoIds.isEmpty()) return
            if (_uiState.value.isLikes) {
                unlikeVideos(videoIds)
                return
            }
            viewModelScope.launch {
                val removed = repository.takeVideosFromPlaylist(playlistId, videoIds)
                if (removed.isEmpty()) return@launch
                _messages.send(
                    PlaylistUiMessage(
                        pluralRes = R.plurals.playlist_videos_removed,
                        count = removed.size,
                        args = listOf(removed.size),
                        undo = QuickActionUndo.PlaylistRemoval(removed),
                    ),
                )
            }
        }

        private fun unlikeVideos(videoIds: Set<String>) {
            viewModelScope.launch {
                val removed = likedMedia.unlike(videoIds)
                if (removed.isEmpty()) return@launch
                _messages.send(
                    PlaylistUiMessage(
                        pluralRes = R.plurals.liked_videos_removed,
                        count = removed.size,
                        args = listOf(removed.size),
                        undo = QuickActionUndo.Unlike(removed),
                    ),
                )
            }
        }

        fun reorderVideos(orderedVideoIds: List<String>) {
            viewModelScope.launch {
                repository.reorderVideosInPlaylist(playlistId, orderedVideoIds)
            }
        }

        fun updatePlaylist(
            name: String,
            description: String,
        ) {
            viewModelScope.launch {
                repository.updatePlaylistMetadata(playlistId, name, description, _uiState.value.isPrivate)
                _uiState.update { it.copy(playlistName = name, description = description) }
            }
        }

        fun deletePlaylist() {
            viewModelScope.launch {
                repository.deletePlaylist(playlistId)
            }
        }

        fun mergeIntoPlaylist(targetPlaylistId: String) {
            viewModelScope.launch {
                _messages.send(mergeVideosIntoPlaylist(repository, _uiState.value.videos, targetPlaylistId))
            }
        }

        /** Makes a video playlist and copies every video here onto it. */
        fun createAndMerge(
            name: String,
            description: String,
        ) {
            viewModelScope.launch {
                _messages.send(createVideoPlaylistAndMerge(repository, _uiState.value.videos, name, description))
            }
        }

        /** The name offered when the viewer saves this playlist as a file. */
        val exportFileName: String get() = PlaylistFileCodec.fileName(_uiState.value.playlistName)

        fun exportTo(target: Uri) {
            viewModelScope.launch {
                val state = _uiState.value
                val saved = transfer.writeTo(target, state.playlistName, state.description, state.videos)
                _messages.send(PlaylistUiMessage(stringRes = if (saved) R.string.playlist_exported else R.string.playlist_export_failed))
            }
        }

        /** This playlist as a file another app can read, or null when it could not be written. */
        suspend fun shareableFile(): Uri? {
            val state = _uiState.value
            return transfer.shareableCopy(state.playlistName, state.description, state.videos)
        }

        /** The videos a "Download all" starts with unpicked, once the first page is in; null for an empty playlist. */
        suspend fun downloadLeftOut(): Set<String>? {
            remote.loadAll()
            return downloads.leftOutLastTime(_uiState.first { !it.isLoading })
        }

        /** Downloads every video but [leftOut], including pages still loading. */
        fun downloadAllExcept(leftOut: Set<String>) {
            viewModelScope.launch {
                val state = loadedState()
                downloads.download(state, state.videos.mapTo(HashSet()) { it.id } - leftOut)
            }
        }

        /** Downloads the [picked] videos as the playlist; see [PlaylistDownloads.download]. */
        fun downloadVideos(picked: Set<String>) {
            viewModelScope.launch { downloads.download(loadedState(), picked) }
        }

        /**
         * Where a queue started from the first [loadedCount] videos finds the rest, or null when it
         * already holds the whole playlist.
         */
        fun queueSource(loadedCount: Int): QueuePageSource? =
            remote.tokenAfter(loadedCount)?.let { queueSources.playlistPages(playlistId, it) }

        private suspend fun loadedState(): PlaylistDetailUiState {
            _uiState.first { !it.isLoading }
            remote.awaitAll()
            return _uiState.value
        }

        private fun loadPlaylist() {
            viewModelScope.launch {
                if (playlistId == PlaylistRepository.WATCH_LATER_ID) {
                    loadWatchLater()
                    return@launch
                }
                if (playlistId == PlaylistRepository.LIKED_VIDEOS_ID) {
                    loadLikedVideos()
                    return@launch
                }

                val localInfo = repository.getPlaylistInfo(playlistId)
                if (localInfo != null) {
                    loadLocalPlaylist(localInfo)
                } else {
                    loadRemotePlaylist()
                }
            }
        }

        private suspend fun loadWatchLater() {
            _uiState.update {
                it.copy(
                    playlistName = context.getString(R.string.watch_later),
                    description = "",
                    isPrivate = true,
                    isLocalPlaylist = true,
                    isSaved = false,
                    isWatchLater = true,
                    isLoading = false,
                    errorMessage = null,
                )
            }
            var migrationStarted = false
            repository.getVideoOnlyWatchLaterFlow().collect { videos ->
                _uiState.update {
                    it.copy(
                        videos = videos,
                        thumbnailUrl = videos.firstOrNull()?.thumbnailUrl.orEmpty(),
                    )
                }
                if (!migrationStarted && videos.isNotEmpty()) {
                    migrationStarted = true
                    viewModelScope.launch { watchLaterMetadataMigrator.migrate(videos) }
                    viewModelScope.launch { sweepWatched(videos) }
                }
                enrichStubs(videos)
            }
        }

        private suspend fun loadLikedVideos() {
            _uiState.update {
                it.copy(
                    playlistName = context.getString(R.string.liked_videos_playlist),
                    description = "",
                    isPrivate = true,
                    isLocalPlaylist = true,
                    isSaved = false,
                    isLikes = true,
                    isLoading = false,
                    errorMessage = null,
                )
            }
            likedVideos.getLikedVideosFlow().collect { likes ->
                val videos = likes.map { it.toPlaylistVideo() }
                _uiState.update { it.copy(videos = videos, thumbnailUrl = videos.firstOrNull()?.thumbnailUrl.orEmpty()) }
                completeLikeDetails(likes)
            }
        }

        /**
         * Likes saved before they carried a channel and length get them once, so rows show a
         * duration and the channel opens; a like already filled in is never fetched again.
         */
        private fun completeLikeDetails(likes: List<LikedVideoInfo>) {
            val sparse = likes.filter { it.channelId.isNullOrBlank() && it.videoId !in attemptedEnrichment }.take(ENRICHMENT_STUB_LIMIT)
            if (sparse.isEmpty() || !enrichSemaphore.tryAcquire()) return
            attemptedEnrichment.addAll(sparse.map { it.videoId })
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                try {
                    sparse.chunked(ENRICHMENT_CHUNK_SIZE).forEach { chunk ->
                        chunk.forEach { like ->
                            val video = runCatching { youTubeRepository.getVideo(like.videoId) }.getOrNull() ?: return@forEach
                            likedVideos.updateDetails(like.withDetailsOf(video))
                        }
                        kotlinx.coroutines.delay(ENRICHMENT_CHUNK_DELAY_MS)
                    }
                } finally {
                    enrichSemaphore.release()
                }
            }
        }

        private suspend fun sweepWatched(videos: List<Video>) {
            val removed = watchLaterCleanup.sweep(videos)
            if (removed.isEmpty()) return
            _messages.send(
                PlaylistUiMessage(
                    pluralRes = R.plurals.watch_later_watched_removed,
                    count = removed.size,
                    args = listOf(removed.size),
                    undo = QuickActionUndo.PlaylistRemoval(removed),
                ),
            )
        }

        private suspend fun loadLocalPlaylist(localInfo: PlaylistInfo) {
            val isSaved = repository.isExternalPlaylistSaved(playlistId)
            val isMusic = repository.getPlaylistEntity(playlistId)?.isMusic
            _uiState.update {
                it.copy(
                    playlistName = localInfo.name,
                    description = localInfo.description,
                    isPrivate = localInfo.isPrivate,
                    thumbnailUrl = localInfo.thumbnailUrl,
                    isLocalPlaylist = true,
                    isSaved = isSaved,
                    isInMusicLibrary = isMusic.takeIf { playlistId != PlaylistRepository.SAVED_SHORTS_ID },
                    isWatchLater = false,
                    isLoading = false,
                    errorMessage = null,
                )
            }
            if (isSaved) {
                refreshSavedPlaylist()
            }
            repository.getPlaylistVideosWithAddedAtFlow(playlistId).collect { videos ->
                _uiState.update { it.copy(videos = videos) }
                enrichStubs(videos)
            }
        }

        private suspend fun loadRemotePlaylist() {
            // A downloaded playlist shows from the device at once, and stays that way without a network.
            val offline = collections.offline(playlistId)?.takeIf { it.videos.isNotEmpty() }
            if (offline != null) {
                showOffline(offline)
                // The live list replaces the download only once it is whole, so the list never shrinks.
                if (NetworkState.isOnline(context)) playlistRepository.complete(playlistId)?.let(remote::start)
                return
            }
            val page = playlistRepository.cachedComplete(playlistId) ?: playlistRepository.firstPage(playlistId)
            if (page != null) {
                remote.start(page)
                val sorted =
                    PlaylistSortOrder.fromStorageValue(playerPreferences.playlistSortOrder(playlistId).first()) != PlaylistSortOrder.MANUAL
                if (sorted || collections.collection(playlistId) != null) remote.loadAll()
                return
            }

            val musicDetails = runCatching { YouTubeMusicService.fetchPlaylistDetails(playlistId) }.getOrNull()
            if (musicDetails != null) {
                _uiState.update {
                    it.copy(
                        playlistName = musicDetails.title,
                        ownerName = musicDetails.author.takeIf(String::isNotBlank),
                        description = musicDetails.description.orEmpty(),
                        isPrivate = false,
                        videos = musicDetails.tracks.map { track -> track.toPlaylistVideo() },
                        thumbnailUrl = musicDetails.thumbnailUrl,
                        isLocalPlaylist = false,
                        isSaved = false,
                        isFromMusic = true,
                        isWatchLater = false,
                        isLoading = false,
                        errorMessage = null,
                    )
                }
                return
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    errorMessage = context.getString(R.string.playlist_load_failed),
                )
            }
        }

        private fun showOffline(offline: OfflineCollection) {
            _uiState.update {
                it.copy(
                    playlistName = offline.collection.title,
                    ownerName = offline.collection.author.takeIf(String::isNotBlank),
                    videos = offline.videos,
                    thumbnailUrl = offline.collection.thumbnailUrl,
                    isLoading = false,
                    errorMessage = null,
                )
            }
        }

        private fun refreshSavedPlaylist() {
            viewModelScope.launch {
                val header = savedRefresh.run() ?: return@launch
                _uiState.update { state ->
                    state.copy(
                        playlistName = header.title.ifBlank { state.playlistName },
                        ownerName = header.ownerName ?: state.ownerName,
                        description = header.description.ifBlank { state.description },
                        thumbnailUrl = header.thumbnailUrl.ifBlank { state.thumbnailUrl },
                        hidesUnavailable = header.hidesUnavailable,
                    )
                }
            }
        }

        private fun enrichStubs(videos: List<Video>) {
            val stubs =
                videos
                    .asSequence()
                    .filter { it.title.isEmpty() && it.id !in attemptedEnrichment }
                    .take(ENRICHMENT_STUB_LIMIT)
                    .toList()
            if (stubs.isEmpty()) return
            if (!enrichSemaphore.tryAcquire()) return
            attemptedEnrichment.addAll(stubs.map { it.id })
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                try {
                    stubs.chunked(ENRICHMENT_CHUNK_SIZE).forEach { chunk ->
                        chunk.forEach { video ->
                            try {
                                val refreshed = youTubeRepository.getVideo(video.id) ?: return@forEach
                                repository.updateVideoMetadata(refreshed)
                            } catch (_: Exception) {
                            }
                        }
                        kotlinx.coroutines.delay(ENRICHMENT_CHUNK_DELAY_MS)
                    }
                } finally {
                    enrichSemaphore.release()
                }
            }
        }
    }

private fun io.github.aedev.flow.data.music.model.MusicTrack.toPlaylistVideo(): Video =
    Video(
        id = videoId,
        title = title,
        channelName = artist,
        channelId = channelId,
        thumbnailUrl = thumbnailUrl,
        duration = duration,
        viewCount = views,
        uploadDate = "",
        isMusic = true,
    )
