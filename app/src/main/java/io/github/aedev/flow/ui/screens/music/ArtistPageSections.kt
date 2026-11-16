package io.github.aedev.flow.ui.screens.music

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.MusicPlaylist
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.recommendation.music.MusicArtistInsights
import io.github.aedev.flow.ui.components.music.header.MusicSectionAction
import io.github.aedev.flow.ui.components.music.item.MusicTrackItem
import io.github.aedev.flow.ui.components.shared.FlowSegmentedGap
import io.github.aedev.flow.ui.components.shared.flowSegmentShape
import io.github.aedev.flow.ui.theme.Dimensions
import io.github.aedev.flow.utils.formatViewCount

internal fun LazyListScope.segmentedTracks(
    id: String,
    tracks: List<MusicTrack>,
    queue: List<MusicTrack>,
    downloadedTrackIds: Set<String>,
    onTrackClick: (MusicTrack, List<MusicTrack>) -> Unit,
    onTrackMenu: (MusicTrack) -> Unit,
) {
    itemsIndexed(tracks, key = { index, track -> "$id:$index:${track.videoId}" }) { index, track ->
        MusicTrackItem(
            track = track,
            index = index + 1,
            shape = flowSegmentShape(index = index, count = tracks.size),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            isDownloaded = downloadedTrackIds.contains(track.videoId),
            onClick = { onTrackClick(track, queue) },
            onLongClick = { onTrackMenu(track) },
            onMenuClick = { onTrackMenu(track) },
            modifier =
                Modifier
                    .padding(horizontal = Dimensions.ContentPaddingHorizontal)
                    .padding(bottom = FlowSegmentedGap),
        )
    }
}

internal fun seeAllAction(
    browseId: String?,
    params: String?,
    onSeeAllClick: (String, String?) -> Unit,
): MusicSectionAction? = browseId?.let { MusicSectionAction.SeeAll { onSeeAllClick(it, params) } }

@Composable
internal fun MusicArtistInsights.summaryLine(): String {
    val plays = stringResource(R.string.artist_insights_played_times, this.plays)
    return if (liked) {
        "$plays ${stringResource(R.string.metadata_separator)} ${stringResource(R.string.artist_insights_liked)}"
    } else {
        plays
    }
}

@Composable
internal fun MusicPlaylist.collectionSubtitle(showAuthor: Boolean): String =
    when {
        showAuthor -> stringResource(R.string.subtitle_playlist_template, author)
        trackCount > 0 -> stringResource(R.string.tracks_count_template, trackCount)
        else -> stringResource(R.string.album_label)
    }

@Composable
internal fun MusicTrack.videoSubtitle(): String {
    val viewsText = if (views > 0) formatViewCount(views) else null
    return if (viewsText != null) stringResource(R.string.artist_views_template, artist, viewsText) else artist
}
