package io.github.aedev.flow.ui.screens.player

import android.content.Context
import android.net.Uri
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.localmedia.LocalSubtitles
import io.github.aedev.flow.data.video.OfflineSubtitleStore
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.stream.ResolvedCaption
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import io.github.aedev.flow.utils.NetworkState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The subtitle files of a device file or download: the ones stored or beside it, and the ones picked for it. */
internal class PlayerSubtitleFiles(
    private val context: Context,
    private val uiState: MutableStateFlow<VideoPlayerUiState>,
    private val playerManager: EnhancedPlayerManager,
    private val localSubtitles: LocalSubtitles,
    private val offlineSubtitleStore: OfflineSubtitleStore,
    private val scope: CoroutineScope,
    private val networkDispatcher: CoroutineDispatcher,
) {
    /**
     * Adds the subtitle file at [uri] to the device file or download that is playing and remembers
     * it for that video; false when it is not a subtitle file Flow can read.
     */
    suspend fun add(uri: Uri): Boolean {
        val videoId = playerManager.playerState.value.currentVideoId ?: return false
        val caption = localSubtitles.pick(videoId, uri) ?: return false
        return withContext(Dispatchers.Main) { playerManager.addLocalCaption(caption) }
    }

    /** The folder of the device file or download that is playing, for the subtitle file picker. */
    suspend fun folder(): Uri? = uiState.value.localFilePath?.let { localSubtitles.folderDocument(it) }

    /**
     * Shifts the captions of what is playing; a device file or download remembers it for next time.
     */
    suspend fun setOffset(offsetMs: Long) {
        val videoId = playerManager.playerState.value.currentVideoId ?: return
        withContext(Dispatchers.Main) { playerManager.setSubtitleOffset(offsetMs) }
        if (uiState.value.localFilePath != null) localSubtitles.setOffsetMs(videoId, offsetMs)
    }

    /**
     * A device video's subtitle files beside it, or a download's stored caption tracks, and in
     * both cases the files picked for it.
     */
    suspend fun forFile(
        videoId: String,
        localFilePath: String,
    ): List<ResolvedCaption> {
        val picked = localSubtitles.picked(videoId)
        if (LocalMediaIds.isLocal(videoId)) return localSubtitles.beside(localFilePath) + picked
        return offlineSubtitlesFor(videoId) + picked
    }

    private suspend fun offlineSubtitlesFor(videoId: String): List<ResolvedCaption> {
        val stored = offlineSubtitleStore.load(videoId)
        if (stored.isEmpty() && !offlineSubtitleStore.isResolved(videoId) && NetworkState.isOnline(context)) {
            scope.launch(networkDispatcher) {
                offlineSubtitleStore.saveForVideo(videoId)
            }
        }
        return stored
    }
}
