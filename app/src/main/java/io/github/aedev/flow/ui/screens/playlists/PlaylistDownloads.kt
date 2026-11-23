package io.github.aedev.flow.ui.screens.playlists

import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.dao.DownloadCollectionSummary
import io.github.aedev.flow.data.local.entity.DownloadCollectionKind
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.repository.YouTubePlaylistRepository
import io.github.aedev.flow.data.video.BackgroundDownloadQueuer
import io.github.aedev.flow.data.video.DownloadBatch
import io.github.aedev.flow.data.video.downloader.collection.CollectionSpec
import io.github.aedev.flow.data.video.downloader.collection.DownloadedCollections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A playlist page's downloads: the playlist saved as one item in its own folder, its progress, and the message once it is queued. */
internal class PlaylistDownloads(
    private val scope: CoroutineScope,
    private val playlistId: String,
    private val queuer: BackgroundDownloadQueuer,
    private val playlistRepository: YouTubePlaylistRepository,
    private val collections: DownloadedCollections,
    private val send: suspend (PlaylistUiMessage) -> Unit,
) {
    private val sharing = SharingStarted.WhileSubscribed(SHARING_TIMEOUT_MS)

    /** This playlist as a downloaded collection, or null when it was never downloaded as one. */
    val downloaded: StateFlow<DownloadCollectionSummary?> = collections.observe(playlistId).stateIn(scope, sharing, null)

    /** The running or just-finished "Download all" for this playlist. */
    val batch: StateFlow<DownloadBatch?> = queuer.batches.map { it[playlistId] }.stateIn(scope, sharing, null)

    /** Videos the whole list holds that the download has never seen; 0 until the list is whole. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun newVideos(wholeList: Flow<List<Video>?>): StateFlow<Int> =
        combine(downloaded, wholeList) { summary, videos -> summary?.let { videos } }
            .mapLatest { videos ->
                if (videos.isNullOrEmpty()) return@mapLatest 0
                val known = collections.memberIds(playlistId)
                if (known.isEmpty()) 0 else videos.count { it.id !in known }
            }.stateIn(scope, sharing, 0)

    /** Downloads what joined the playlist since its last download, leaving out what was left out then. */
    suspend fun downloadNew(state: PlaylistDetailUiState) =
        download(
            state,
            state.videos.mapTo(HashSet()) { it.id } - collections.excludedIds(playlistId),
        )

    init {
        scope.launch {
            queuer.batches
                .map { it[playlistId] }
                .filterNotNull()
                .filter { it.isFinished }
                .collect { batch ->
                    send(
                        if (batch.queued > 0) {
                            PlaylistUiMessage(stringRes = R.string.playlist_downloads_queued, args = listOf(batch.queued, batch.total))
                        } else {
                            PlaylistUiMessage(stringRes = R.string.playlist_download_queue_empty)
                        },
                    )
                    queuer.clearBatch(playlistId)
                }
        }
    }

    /**
     * The videos a download starts with unpicked: those left out last time, or none when that would
     * leave nothing of [state] picked. Null, with a message, when the playlist is empty.
     */
    suspend fun leftOutLastTime(state: PlaylistDetailUiState): Set<String>? {
        if (state.videos.isEmpty()) {
            send(PlaylistUiMessage(stringRes = R.string.ui_playlist_empty))
            return null
        }
        val leftOut = collections.excludedIds(playlistId)
        return if (state.videos.all { it.id in leftOut }) emptySet() else leftOut
    }

    /**
     * Downloads the playlist in [state] as one item in its own folder, only its [picked] videos; the
     * rest stay out of it until picked. A second download picks up only what is new. [state] must
     * have every page loaded.
     */
    suspend fun download(
        state: PlaylistDetailUiState,
        picked: Set<String>,
    ) {
        val videos = state.videos
        val count = videos.count { it.id in picked }
        if (count == 0) return
        send(PlaylistUiMessage(pluralRes = R.plurals.ui_downloading_videos, count = count, args = listOf(count)))
        val isLocal = state.isLocalPlaylist || state.isWatchLater || state.isLikes
        val spec =
            CollectionSpec(
                id = playlistId,
                kind = if (isLocal) DownloadCollectionKind.LOCAL_PLAYLIST else DownloadCollectionKind.VIDEO_PLAYLIST,
                title = state.playlistName,
                author = state.ownerName,
                thumbnailUrl = state.thumbnailUrl.ifBlank { videos.first().thumbnailUrl },
            )
        queuer.queueCollection(spec, videos, complete = isLocal || playlistRepository.cachedComplete(playlistId) != null, picked = picked)
    }

    private companion object {
        const val SHARING_TIMEOUT_MS = 5_000L
    }
}
