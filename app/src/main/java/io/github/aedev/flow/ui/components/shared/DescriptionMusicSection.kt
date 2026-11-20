package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.aedev.flow.R
import io.github.aedev.flow.innertube.pages.VideoDescriptionMusic
import io.github.aedev.flow.innertube.pages.VideoDescriptionSong
import io.github.aedev.flow.utils.RichTextLinkActions
import io.github.aedev.flow.utils.clickAction

/**
 * The songs the video credits. A card opens only when it names a destination Flow has; most song
 * cards carry none, only a credits dialog, so most stay plain.
 */
@Composable
internal fun DescriptionMusicSection(
    music: VideoDescriptionMusic,
    tint: MediaArtworkTint,
    actions: RichTextLinkActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(DescriptionChipSpacing)) {
        Text(
            text = music.title ?: stringResource(R.string.description_music_section),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        music.songs.forEach { song ->
            val onClick = song.target?.clickAction(actions)
            if (onClick == null) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = tint.container,
                    contentColor = tint.onContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) { SongRow(song) }
            } else {
                Surface(
                    onClick = onClick,
                    shape = MaterialTheme.shapes.large,
                    color = tint.container,
                    contentColor = tint.onContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) { SongRow(song) }
            }
        }
    }
}

@Composable
private fun SongRow(song: VideoDescriptionSong) {
    Row(
        modifier = Modifier.padding(SongPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SongPadding),
    ) {
        if (song.thumbnailUrl != null) {
            AsyncImage(
                model = song.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .size(ArtworkSize)
                        .clip(MaterialTheme.shapes.small),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            listOfNotNull(song.artist.takeIf { it.isNotBlank() }, song.album).forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val SongPadding = 12.dp
private val ArtworkSize = 56.dp
