package io.github.aedev.flow.ui.screens.music.collection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.components.shared.mediaLengthLabel

private val SavableKinds = setOf(MusicCollectionKind.ALBUM, MusicCollectionKind.PLAYLIST, MusicCollectionKind.SAVED)

/**
 * What the header shows for this collection. An album's year arrives in its description, so it
 * joins the metadata line instead; the length is YouTube's when it gives one, else the songs' sum.
 */
@Composable
internal fun rememberCollectionHeaderState(
    state: MusicCollectionUiState,
    tracks: List<MusicTrack>,
    downloadProgress: Float?,
    isDownloaded: Boolean,
    artworkKey: String?,
): CollectionHeaderState {
    val details = requireNotNull(state.details)
    val isAlbum = state.kind == MusicCollectionKind.ALBUM
    val totalSeconds = remember(tracks) { tracks.sumOf { it.duration.coerceAtLeast(0) } }
    val length = details.durationText?.takeIf(String::isNotBlank) ?: mediaLengthLabel(totalSeconds.toLong())
    val separator = stringResource(R.string.metadata_separator)
    val metadata =
        listOfNotNull(
            details.description?.takeIf { isAlbum && it.isNotBlank() },
            pluralStringResource(R.plurals.songs_count_template, tracks.size, tracks.size),
            length.takeUnless { state.isLoadingMore },
        ).joinToString(" $separator ")
    return CollectionHeaderState(
        kindLabel = kindLabel(state.kind),
        title = details.title,
        author = details.author,
        authorId = details.authorId,
        metadata = metadata,
        description =
            details.description
                .orEmpty()
                .takeUnless { isAlbum }
                .orEmpty(),
        artworkUrl = details.thumbnailUrl.ifBlank { tracks.firstOrNull()?.thumbnailUrl.orEmpty() },
        artworkKey = artworkKey,
        isSaved = state.isSaved,
        canSave = state.kind in SavableKinds,
        canShare = state.kind != null,
        downloadProgress = downloadProgress,
        isDownloaded = isDownloaded,
    )
}

@Composable
private fun kindLabel(kind: MusicCollectionKind?): String =
    stringResource(
        when (kind) {
            MusicCollectionKind.ALBUM -> R.string.music_kind_album
            MusicCollectionKind.OWN -> R.string.playlist_type_yours
            MusicCollectionKind.SAVED -> R.string.music_kind_saved
            MusicCollectionKind.DAILY_MIX -> R.string.section_daily_mix_label
            MusicCollectionKind.LIKED -> R.string.playlist_type_builtin
            MusicCollectionKind.PLAYLIST, null -> R.string.playlist
        },
    )
