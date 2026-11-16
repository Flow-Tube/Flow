package io.github.aedev.flow.ui.components.music.section

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.MUSIC_GENRE_SOURCE_PREFIX
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.components.music.header.MusicSectionAction
import io.github.aedev.flow.ui.components.shared.FlowEmptyState
import io.github.aedev.flow.ui.components.shared.FlowErrorState
import io.github.aedev.flow.ui.components.shared.FlowFeedProgress
import io.github.aedev.flow.ui.screens.music.MusicHomeChipPage
import io.github.aedev.flow.ui.screens.music.MusicHomeChipStatus
import io.github.aedev.flow.ui.screens.music.isSongShelf

private const val STATE_HEIGHT_FRACTION = 0.6f

/** A selected chip's page: its own shelves in YouTube's order, or the state of getting them. */
@Suppress("LongParameterList")
internal fun LazyListScope.musicHomeChipPage(
    page: MusicHomeChipPage,
    downloaded: Set<String>,
    onSongClick: (MusicTrack, List<MusicTrack>, String?) -> Unit,
    onAlbumClick: (String) -> Unit,
    onTrackMenu: (MusicTrack) -> Unit,
    onCollectionMenu: (MusicTrack) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
) {
    when {
        page.status == MusicHomeChipStatus.LOADING -> {
            item(key = "chip_loading") { FlowFeedProgress() }
            return
        }

        page.status == MusicHomeChipStatus.FAILED -> {
            item(key = "chip_failed") {
                FlowErrorState(
                    error = stringResource(R.string.music_chip_load_failed, page.chip.title),
                    onRetry = onRetry,
                    modifier = Modifier.fillParentMaxHeight(STATE_HEIGHT_FRACTION),
                )
            }
            return
        }

        page.sections.isEmpty() && page.continuation == null -> {
            item(key = "chip_empty") {
                FlowEmptyState(
                    title = stringResource(R.string.music_chip_empty),
                    modifier = Modifier.fillParentMaxHeight(STATE_HEIGHT_FRACTION),
                )
            }
            return
        }
    }

    val playFrom = MUSIC_GENRE_SOURCE_PREFIX + page.chip.title
    itemsIndexed(page.sections, key = { index, section -> "chip:$index:${section.title}" }) { index, section ->
        if (section.isSongShelf) {
            MusicQuickPicksShelf(
                title = section.title,
                tracks = section.tracks,
                downloadedTrackIds = downloaded,
                action = MusicSectionAction.PlayAll { onSongClick(section.tracks.first(), section.tracks, playFrom) },
                onTrackClick = { onSongClick(it, section.tracks, playFrom) },
                onTrackMenu = onTrackMenu,
            )
        } else {
            MusicTrackCardShelf(
                title = section.title,
                tracks = section.tracks,
                keyNamespace = "chip_${index}_${section.title}",
                downloadedTrackIds = downloaded,
                onTrackClick = { track -> onSongClick(track, section.tracks.filterNot { it.isCollection }, playFrom) },
                onTrackMenu = onTrackMenu,
                onCollectionClick = { onAlbumClick(it.videoId) },
                onCollectionMenu = onCollectionMenu,
            )
        }
    }

    page.continuation?.let { continuation ->
        item(key = "chip_continuation") {
            LaunchedEffect(continuation) { onLoadMore() }
            if (page.isMoreLoading) FlowFeedProgress() else Box(modifier = Modifier.height(0.dp))
        }
    }
}
