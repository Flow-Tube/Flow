package io.github.aedev.flow.service.auto

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.recommendation.music.MusicTimeBucket
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.musicAudioUri
import io.github.aedev.flow.player.musicMediaItem
import io.github.aedev.flow.ui.components.music.section.rotationTitleRes
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The car's side of the music session: the browse tree, search, and turning what the car picked
 * into playable items. Items the app's own controller sends already carry their stream and pass
 * through untouched, so nothing here changes how the app plays music.
 */
internal class AutoLibraryBrowser(
    private val context: Context,
    private val scope: CoroutineScope,
    private val library: AutoLibrary,
) {
    private var rootLimit = AutoBrowseTree.DEFAULT_ROOT_LIMIT

    fun root(
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        if (browser.packageName != MediaSession.ControllerInfo.LEGACY_CONTROLLER_PACKAGE_NAME) {
            AutoArtworkProvider.grantTo(context, browser.packageName)
        }
        rootLimit = params?.extras?.getInt(MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT, AutoBrowseTree.DEFAULT_ROOT_LIMIT)
            ?: AutoBrowseTree.DEFAULT_ROOT_LIMIT
        val rootParams =
            LibraryParams
                .Builder()
                .setExtras(
                    Bundle().apply {
                        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
                    },
                ).build()
        return Futures.immediateFuture(LibraryResult.ofItem(folder(AutoBrowseTree.ROOT), rootParams))
    }

    fun children(
        parentId: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        load(params) {
            when (parentId) {
                AutoBrowseTree.ROOT -> AutoBrowseTree.rootTabs(rootLimit).map(::folder)
                AutoBrowseTree.FOR_YOU -> AutoBrowseTree.mixes.map(::folder)
                AutoBrowseTree.LIBRARY -> AutoBrowseTree.libraryLists.map(::folder)
                AutoBrowseTree.PLAYLISTS -> playlistFolders()
                else -> library.tracks(parentId)?.map { songItem(parentId, it) }
            }
        }

    fun item(mediaId: String): ListenableFuture<LibraryResult<MediaItem>> {
        val future = SettableFuture.create<LibraryResult<MediaItem>>()
        scope.launch {
            val item =
                runCatching {
                    titleOf(mediaId)?.let { folder(mediaId) }
                        ?: AutoBrowseTree.songOf(mediaId)?.let { (parentId, videoId) ->
                            library.tracks(parentId)?.firstOrNull { it.videoId == videoId }?.let { songItem(parentId, it) }
                        }
                }.getOrNull()
            future.set(item?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
        }
        return future
    }

    /** Runs the search and tells [browser] when its results are ready to be read. */
    fun search(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> {
        scope.launch {
            val count = runCatching { withContext(PerformanceDispatcher.networkIO) { library.search(query) }.size }.getOrDefault(0)
            session.notifySearchResultChanged(browser, query, count, params)
        }
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    fun searchResult(
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        load(params, PerformanceDispatcher.networkIO) {
            val parentId = AutoBrowseTree.search(query)
            val group = context.getString(R.string.filter_songs)
            library.tracks(parentId)?.map { songItem(parentId, it, group) }
        }

    /**
     * What the car asked to play, as the app's own items. A song from a list brings the whole list
     * with it, starting at that song; a spoken request plays its search results from the top.
     */
    fun setMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition>? {
        if (mediaItems.all { it.localConfiguration != null }) return null
        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        scope.launch {
            try {
                val single = mediaItems.singleOrNull()
                val picked =
                    if (single != null) {
                        expand(single)
                    } else {
                        Picked(resolveEach(mediaItems), startIndex.coerceAtLeast(0), fromVideoList = false, startPositionMs)
                    }
                if (picked.tracks.isEmpty()) {
                    future.setException(IllegalArgumentException("Nothing to play for ${mediaItems.map { it.mediaId }}"))
                } else {
                    future.set(
                        MediaSession.MediaItemsWithStartPosition(
                            picked.tracks.map { playable(it, picked.fromVideoList) },
                            picked.index.coerceIn(0, picked.tracks.lastIndex),
                            picked.startPositionMs,
                        ),
                    )
                }
            } catch (e: CancellationException) {
                future.cancel(false)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not resolve what the car asked to play", e)
                future.setException(e)
            }
        }
        return future
    }

    /** The items the car added, one each; null when they need nothing from here. */
    fun addMediaItems(mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>>? {
        if (mediaItems.all { it.localConfiguration != null }) return null
        val future = SettableFuture.create<List<MediaItem>>()
        scope.launch {
            runCatching {
                mediaItems.mapNotNull { item ->
                    val fromVideoList = AutoBrowseTree.songOf(item.mediaId)?.first?.let(AutoBrowseTree::isVideoList) == true
                    resolveEach(listOf(item)).firstOrNull()?.let { playable(it, fromVideoList) }
                }
            }.onSuccess { future.set(it) }
                .onFailure { future.setException(it) }
        }
        return future
    }

    /** What a pick plays: the list, where in it to start, and whether it is a list of videos. */
    private class Picked(
        val tracks: List<MusicTrack>,
        val index: Int,
        val fromVideoList: Boolean,
        val startPositionMs: Long = 0L,
    )

    private suspend fun expand(item: MediaItem): Picked {
        val query = item.requestMetadata.searchQuery
        if (query != null && item.mediaId.isBlank()) {
            // "Play music on Flow" arrives with no words, and plays what the user has on repeat.
            val tracks =
                if (query.isBlank()) {
                    withContext(PerformanceDispatcher.diskIO) {
                        library.tracks(AutoBrowseTree.ON_REPEAT).orEmpty().ifEmpty { library.tracks(AutoBrowseTree.RECENT).orEmpty() }
                    }
                } else {
                    withContext(PerformanceDispatcher.networkIO) { library.search(query) }
                }
            return Picked(tracks, 0, fromVideoList = false)
        }
        val (parentId, videoId) =
            AutoBrowseTree.songOf(item.mediaId) ?: return Picked(resolveEach(listOf(item)), 0, fromVideoList = false)
        val tracks = withContext(PerformanceDispatcher.diskIO) { library.tracks(parentId) }.orEmpty()
        val index = tracks.indexOfFirst { it.videoId == videoId }
        if (index < 0) return Picked(resolveEach(listOf(item)), 0, fromVideoList = false)
        if (!AutoBrowseTree.isVideoList(parentId)) return Picked(tracks, index, fromVideoList = false)
        val resumeMs = withContext(PerformanceDispatcher.diskIO) { library.resumePositionMs(videoId) }
        return Picked(tracks, index, fromVideoList = true, startPositionMs = resumeMs)
    }

    private suspend fun resolveEach(items: List<MediaItem>): List<MusicTrack> =
        items.mapNotNull { item ->
            val videoId = AutoBrowseTree.songOf(item.mediaId)?.second ?: item.mediaId
            knownTrack(videoId) ?: item.asTrack(videoId)
        }

    private fun knownTrack(videoId: String): MusicTrack? =
        EnhancedMusicPlayerManager.queue.value.firstOrNull { it.videoId == videoId }
            ?: EnhancedMusicPlayerManager.currentTrack.value?.takeIf { it.videoId == videoId }

    private fun MediaItem.asTrack(videoId: String): MusicTrack? {
        if (videoId.isBlank()) return null
        return MusicTrack(
            videoId = videoId,
            title = mediaMetadata.title?.toString().orEmpty(),
            artist = mediaMetadata.artist?.toString().orEmpty(),
            thumbnailUrl = "",
            duration = 0,
        )
    }

    private fun playable(
        track: MusicTrack,
        fromVideoList: Boolean,
    ): MediaItem = musicMediaItem(track, musicAudioUri(track.videoId), fromVideoList = fromVideoList)

    /** Music playlists, then video playlists (#1657), each under its own heading when both are there. */
    private suspend fun playlistFolders(): List<MediaItem> {
        val music = library.playlists()
        val videos = library.videoPlaylists()
        val grouped = music.isNotEmpty() && videos.isNotEmpty()
        val musicGroup = context.getString(R.string.auto_group_music_playlists).takeIf { grouped }
        val videoGroup = context.getString(R.string.auto_group_video_playlists).takeIf { grouped }
        return music.map { playlistFolder(AutoBrowseTree.playlist(it.id), it.name, it.thumbnailUrl, musicGroup) } +
            videos.map { playlistFolder(AutoBrowseTree.videoPlaylist(it.id), it.name, it.thumbnailUrl, videoGroup) }
    }

    private fun load(
        params: LibraryParams?,
        dispatcher: CoroutineDispatcher = PerformanceDispatcher.diskIO,
        block: suspend () -> List<MediaItem>?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
        scope.launch {
            val items =
                try {
                    withContext(dispatcher) { block() }
                } catch (e: CancellationException) {
                    future.cancel(false)
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Could not load a car list", e)
                    null
                }
            future.set(
                items?.let { LibraryResult.ofItemList(ImmutableList.copyOf(it), params) }
                    ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE),
            )
        }
        return future
    }

    private fun folder(mediaId: String): MediaItem {
        val style =
            when (mediaId) {
                AutoBrowseTree.FOR_YOU, AutoBrowseTree.PLAYLISTS -> MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
                else -> MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
            }
        return browsable(mediaId, titleOf(mediaId).orEmpty(), artwork = null, childStyle = style)
    }

    private fun playlistFolder(
        mediaId: String,
        name: String,
        thumbnailUrl: String,
        groupTitle: String?,
    ): MediaItem =
        browsable(
            mediaId = mediaId,
            title = name,
            artwork = thumbnailUrl,
            childStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            groupTitle = groupTitle,
        )

    private fun browsable(
        mediaId: String,
        title: String,
        artwork: String?,
        childStyle: Int,
        groupTitle: String? = null,
    ): MediaItem =
        MediaItem
            .Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(title)
                    .setArtworkUri(artwork?.let { AutoArtworkProvider.uriFor(it) })
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setExtras(
                        Bundle().apply {
                            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, childStyle)
                            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
                            groupTitle?.let { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) }
                        },
                    ).build(),
            ).build()

    private fun songItem(
        parentId: String,
        track: MusicTrack,
        groupTitle: String? = null,
    ): MediaItem =
        MediaItem
            .Builder()
            .setMediaId(AutoBrowseTree.song(parentId, track.videoId))
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setArtworkUri(AutoArtworkProvider.uriFor(track.listThumbnailUrl))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .apply {
                        groupTitle?.let { title ->
                            setExtras(Bundle().apply { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, title) })
                        }
                    }.build(),
            ).build()

    private fun titleOf(mediaId: String): String? =
        when (mediaId) {
            AutoBrowseTree.ROOT -> context.getString(R.string.app_name)
            AutoBrowseTree.FOR_YOU -> context.getString(R.string.auto_tab_for_you)
            AutoBrowseTree.LIBRARY -> context.getString(R.string.library)
            AutoBrowseTree.PLAYLISTS -> context.getString(R.string.library_playlists_label)
            AutoBrowseTree.QUEUE -> context.getString(R.string.auto_tab_queue)
            AutoBrowseTree.ON_REPEAT -> context.getString(R.string.section_on_repeat)
            AutoBrowseTree.RIGHT_NOW -> context.getString(MusicTimeBucket.fromTimestamp(System.currentTimeMillis()).rotationTitleRes())
            AutoBrowseTree.REDISCOVER -> context.getString(R.string.section_rediscover)
            AutoBrowseTree.LIKED -> context.getString(R.string.liked_music_playlist)
            AutoBrowseTree.DOWNLOADS -> context.getString(R.string.downloads_title)
            AutoBrowseTree.RECENT -> context.getString(R.string.section_recently_played)
            AutoBrowseTree.WATCH_LATER -> context.getString(R.string.watch_later)
            else -> null
        }

    private companion object {
        const val TAG = "AutoLibrary"
    }
}
