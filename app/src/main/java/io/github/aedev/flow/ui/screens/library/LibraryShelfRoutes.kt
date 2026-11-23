package io.github.aedev.flow.ui.screens.library

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.PlaylistInfo
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.DownloadedTrack
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.video.DownloadedVideo
import io.github.aedev.flow.ui.components.PlaylistCard
import io.github.aedev.flow.ui.components.PlaylistCardLayout
import io.github.aedev.flow.ui.components.library.LibraryAlbumCard
import io.github.aedev.flow.ui.components.library.LibraryEmptyShelf
import io.github.aedev.flow.ui.components.library.LibraryMediaItem
import io.github.aedev.flow.ui.components.library.LibraryMediaShelf
import io.github.aedev.flow.ui.components.library.LibrarySection
import io.github.aedev.flow.ui.components.library.LibraryShelf
import io.github.aedev.flow.ui.components.library.LibraryShelfPlaceholder
import io.github.aedev.flow.ui.components.library.LibraryShelfVideoCard
import io.github.aedev.flow.ui.components.library.LibraryShortsShelf
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun LibraryMediaShelfRoute(
    section: LibrarySection,
    itemsFlow: StateFlow<List<LibraryMediaItem>?>,
    onTitleClick: () -> Unit,
    onVideoClick: (Video) -> Unit,
    onMusicClick: (MusicTrack, List<MusicTrack>, String) -> Unit,
    onDownloadedVideoClick: (List<DownloadedVideo>, Int) -> Unit,
    onDownloadedMusicClick: (List<DownloadedTrack>, Int) -> Unit,
    emptyMessage: String? = null,
) {
    val items by itemsFlow.collectAsStateWithLifecycle()
    val title = section.title
    when {
        items == null -> {
            LibraryShelfPlaceholder(title = title, icon = section.icon)
        }

        items.isNullOrEmpty() && emptyMessage != null -> {
            LibraryEmptyShelf(title = title, icon = section.icon, message = emptyMessage, onTitleClick = onTitleClick)
        }

        items.isNullOrEmpty() -> {
            Unit
        }

        else -> {
            LibraryMediaShelf(
                title = title,
                icon = section.icon,
                items = items.orEmpty(),
                sourceName = title,
                onTitleClick = onTitleClick,
                onVideoClick = onVideoClick,
                onMusicClick = onMusicClick,
                onDownloadedVideoClick = onDownloadedVideoClick,
                onDownloadedMusicClick = onDownloadedMusicClick,
            )
        }
    }
}

@Composable
internal fun LibraryPlaylistsShelf(
    section: LibrarySection,
    videoPlaylistsFlow: StateFlow<List<PlaylistInfo>?>?,
    musicPlaylistsFlow: StateFlow<List<PlaylistInfo>?>?,
    onTitleClick: () -> Unit,
    onVideoPlaylistClick: (String) -> Unit,
    onMusicPlaylistClick: (String) -> Unit,
) {
    val videoPlaylists = videoPlaylistsFlow?.collectAsStateWithLifecycle()?.value
    val musicPlaylists = musicPlaylistsFlow?.collectAsStateWithLifecycle()?.value
    val title = section.title

    if (videoPlaylists == null && musicPlaylists == null) {
        LibraryShelfPlaceholder(title = title, icon = section.icon)
        return
    }
    if (videoPlaylists.isNullOrEmpty() && musicPlaylists.isNullOrEmpty()) return

    LibraryShelf(
        title = title,
        icon = section.icon,
        onTitleClick = onTitleClick,
    ) { cardWidth ->
        items(
            items = videoPlaylists.orEmpty(),
            key = { "video-${it.id}" },
            contentType = { "video-playlist" },
        ) { playlist ->
            PlaylistCard(
                playlist = playlist,
                onClick = { onVideoPlaylistClick(playlist.id) },
                layout = PlaylistCardLayout.SHELF,
                modifier = Modifier.width(cardWidth),
            )
        }
        items(
            items = musicPlaylists.orEmpty(),
            key = { "music-${it.id}" },
            contentType = { "music-playlist" },
        ) { playlist ->
            LibraryAlbumCard(
                title = playlist.name,
                subtitle = stringResource(R.string.tracks_count_template, playlist.videoCount),
                thumbnailUrl = playlist.thumbnailUrl,
                onClick = { onMusicPlaylistClick(playlist.id) },
                artworkSize = cardWidth * 9f / 16f,
            )
        }
    }
}

@Composable
internal fun LibraryVideoShelf(
    section: LibrarySection,
    videosFlow: StateFlow<List<Video>?>,
    onTitleClick: () -> Unit,
    onVideoClick: (Video) -> Unit,
) {
    val videos by videosFlow.collectAsStateWithLifecycle()
    val title = section.title
    when {
        videos == null -> {
            LibraryShelfPlaceholder(title = title, icon = section.icon)
        }

        videos.isNullOrEmpty() -> {
            Unit
        }

        else -> {
            LibraryShelf(
                title = title,
                icon = section.icon,
                onTitleClick = onTitleClick,
            ) { cardWidth ->
                items(
                    items = videos.orEmpty(),
                    key = Video::id,
                    contentType = { "video" },
                ) { video ->
                    LibraryShelfVideoCard(
                        video = video,
                        onClick = { onVideoClick(video) },
                        width = cardWidth,
                    )
                }
            }
        }
    }
}

@Composable
internal fun LibraryShortsShelfRoute(
    section: LibrarySection,
    shortsFlow: StateFlow<List<Video>?>,
    onTitleClick: () -> Unit,
    onShortClick: (Video) -> Unit,
) {
    val shorts by shortsFlow.collectAsStateWithLifecycle()
    val title = section.title
    when {
        shorts == null -> {
            LibraryShelfPlaceholder(title = title, icon = section.icon, portrait = true)
        }

        shorts.isNullOrEmpty() -> {
            Unit
        }

        else -> {
            LibraryShortsShelf(
                title = title,
                icon = section.icon,
                shorts = shorts.orEmpty(),
                onTitleClick = onTitleClick,
                onShortClick = onShortClick,
            )
        }
    }
}
