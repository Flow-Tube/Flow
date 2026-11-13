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
import io.github.aedev.flow.data.local.WatchLaterCleanup
import io.github.aedev.flow.data.local.dao.DownloadCollectionSummary
import io.github.aedev.flow.data.local.entity.DownloadCollectionKind
import io.github.aedev.flow.data.migration.WatchLaterMetadataMigrator
import io.github.aedev.flow.data.model.PlaylistInfo
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.YouTubeMusicService
import io.github.aedev.flow.data.playlist.PlaylistFileCodec
import io.github.aedev.flow.data.playlist.PlaylistTransfer
import io.github.aedev.flow.data.repository.RemotePlaylistPage
import io.github.aedev.flow.data.repository.YouTubePlaylistRepository
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.data.repository.walkPlaylistPages
import io.github.aedev.flow.data.video.BackgroundDownloadQueuer
import io.github.aedev.flow.data.video.DownloadBatch
import io.github.aedev.flow.data.video.downloader.collection.CollectionSpec
import io.github.aedev.flow.data.video.downloader.collection.DownloadedCollections
import io.github.aedev.flow.ui.components.library.PlaylistSortOrder
import io.github.aedev.flow.ui.components.library.sortedForPlaylist
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionUndo
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
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

        /** This playlist as a downloaded collection, or null when it was never downloaded as one. */
        val downloadedCollection: StateFlow<DownloadCollectionSummary?> =
            collections
                .observe(
                    playlistId,
                ).stateIn(viewModelScope, sharing, null)

        /** The running or just-finished "Download all" for this playlist. */
        val downloadBatch: StateFlow<DownloadBatch?> =
            downloadQueuer.batches
                .map { it[playlistId] }
                .stateIn(viewModelScope, sharing, null)

        init {
            loadPlaylist()
            viewModelScope.launch {
                downloadQueuer.batches
                    .map { it[playlistId] }
                    .filterNotNull()
                    .filter { it.isFinished }
                    .collect { batch ->
                        _messages.send(
                            if (batch.queued > 0) {
                                PlaylistUiMessage(stringRes = R.string.playlist_downloads_queued, args = listOf(batch.queued, batch.total))
                            } else {
                                PlaylistUiMessage(stringRes = R.string.playlist_download_queue_empty)
                            },
                        )
                        downloadQueuer.clearBatch(playlistId)
                    }
            }
        }

        fun setSortOrder(order: PlaylistSortOrder) {
            viewModelScope.launch {
                playerPreferences.setPlaylistSortOrder(playlistId, order.storageValue)
            }
        }

        fun retry() {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            loadPlaylist()
        }

        fun saveToLibrary() {
            viewModelScope.launch {
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

        /**
         * Downloads the whole playlist as one item in its own folder, once every page has arrived;
         * a second download picks up only what is new.
         */
        fun downloadPlaylist() {
            viewModelScope.launch {
                val state = _uiState.first { !it.isLoading && !it.isLoadingMore }
                val videos = state.videos
                if (videos.isEmpty()) {
                    _messages.send(PlaylistUiMessage(stringRes = R.string.ui_playlist_empty))
                    return@launch
                }
                _messages.send(
                    PlaylistUiMessage(pluralRes = R.plurals.ui_downloading_videos, count = videos.size, args = listOf(videos.size)),
                )
                val isLocal = state.isLocalPlaylist || state.isWatchLater || state.isLikes
                val spec =
                    CollectionSpec(
                        id = playlistId,
                        kind = if (isLocal) DownloadCollectionKind.LOCAL_PLAYLIST else DownloadCollectionKind.VIDEO_PLAYLIST,
                        title = state.playlistName,
                        author = state.ownerName,
                        thumbnailUrl = state.thumbnailUrl.ifBlank { videos.first().thumbnailUrl },
                    )
                downloadQueuer.queueCollection(spec, videos, complete = isLocal || playlistRepository.cachedComplete(playlistId) != null)
            }
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
            val page = playlistRepository.cachedComplete(playlistId) ?: playlistRepository.firstPage(playlistId)
            if (page != null) {
                _uiState.update {
                    it.copy(
                        playlistName = page.title,
                        ownerName = page.ownerName,
                        isCourse = page.isCourse,
                        description = page.description,
                        isPrivate = false,
                        videos = page.videos,
                        thumbnailUrl = page.thumbnailUrl,
                        isLocalPlaylist = false,
                        isSaved = false,
                        isWatchLater = false,
                        isLoading = false,
                        isLoadingMore = page.continuation != null,
                        hidesUnavailable = page.continuation == null && page.hidesUnavailable(),
                        errorMessage = null,
                    )
                }
                page.continuation?.let { loadRemainingPages(page, it) }
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

            collections.offline(playlistId)?.let { offline ->
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
                return
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    errorMessage = context.getString(R.string.playlist_load_failed),
                )
            }
        }

        /** Appends the pages after the first as they arrive, so the list is usable while it grows. */
        private suspend fun loadRemainingPages(
            first: RemotePlaylistPage,
            firstToken: String,
        ) {
            val walk =
                walkPlaylistPages(
                    firstVideos = first.videos,
                    firstToken = firstToken,
                    maxPages = YouTubePlaylistRepository.PAGE_LIMIT,
                    nextPage = { playlistRepository.nextPage(playlistId, it) },
                    onPage = { videos -> _uiState.update { it.copy(videos = videos) } },
                )
            _uiState.update {
                it.copy(isLoadingMore = false, hidesUnavailable = walk.finished && first.hidesUnavailable(walk.videos.size))
            }
            if (walk.finished) playlistRepository.rememberComplete(playlistId, first.copy(videos = walk.videos))
        }

        private fun refreshSavedPlaylist() {
            viewModelScope.launch {
                val details = playlistRepository.complete(playlistId) ?: return@launch
                repository.syncSavedPlaylistVideos(playlistId, details.videos)
                _uiState.update { state ->
                    state.copy(
                        playlistName = details.title.ifBlank { state.playlistName },
                        ownerName = details.ownerName ?: state.ownerName,
                        description = details.description.ifBlank { state.description },
                        thumbnailUrl = details.thumbnailUrl.ifBlank { state.thumbnailUrl },
                        hidesUnavailable = details.hidesUnavailable(),
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
