package io.github.aedev.flow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Playlist
import io.github.aedev.flow.data.model.PlaylistInfo
import io.github.aedev.flow.ui.components.shared.MediaTextBadge
import io.github.aedev.flow.ui.components.shared.thumbnailUrlOrNull
import io.github.aedev.flow.utils.ThumbnailUrlResolver

private val RowHorizontalPadding = 12.dp

enum class PlaylistCardLayout {
    LIST,
    SHELF,
}

@Composable
fun PlaylistCard(
    playlist: PlaylistInfo,
    onClick: () -> Unit,
    onDeleteClick: (() -> Unit)? = null,
    layout: PlaylistCardLayout = PlaylistCardLayout.LIST,
    modifier: Modifier = Modifier,
    useInternalPadding: Boolean = true,
) {
    PlaylistCardContent(
        title = playlist.name,
        description = playlist.description,
        thumbnailUrl = playlist.thumbnailUrl,
        videoCount = playlist.videoCount,
        onClick = onClick,
        onDeleteClick = onDeleteClick,
        layout = layout,
        modifier = modifier,
        useInternalPadding = useInternalPadding,
    )
}

@Composable
fun PlaylistCard(
    playlist: Playlist,
    onClick: () -> Unit,
    layout: PlaylistCardLayout = PlaylistCardLayout.LIST,
    modifier: Modifier = Modifier,
    useInternalPadding: Boolean = true,
) {
    PlaylistCardContent(
        title = playlist.name,
        description = playlist.description,
        thumbnailUrl = playlist.thumbnailUrl,
        videoCount = playlist.knownVideoCount(),
        isCourse = playlist.isCourse,
        onClick = onClick,
        onDeleteClick = null,
        layout = layout,
        modifier = modifier,
        useInternalPadding = useInternalPadding,
    )
}

@Composable
private fun PlaylistCardContent(
    title: String,
    description: String,
    thumbnailUrl: String,
    videoCount: Int?,
    onClick: () -> Unit,
    onDeleteClick: (() -> Unit)?,
    layout: PlaylistCardLayout,
    modifier: Modifier,
    useInternalPadding: Boolean,
    isCourse: Boolean = false,
) {
    val metadata =
        videoCount?.let {
            pluralStringResource(
                if (isCourse) R.plurals.course_lessons_count else R.plurals.videos_count_template,
                it,
                it,
            )
        }

    if (layout == PlaylistCardLayout.SHELF) {
        Column(
            modifier =
                Modifier
                    .then(modifier)
                    .clickable(onClick = onClick)
                    .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LayeredPlaylistArtwork(
                thumbnailUrl = thumbnailUrl,
                videoCount = videoCount,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
            )
            Row(verticalAlignment = Alignment.Top) {
                PlaylistCardText(
                    title = title,
                    metadata = metadata,
                    description = "",
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
                if (onDeleteClick != null) {
                    PlaylistCardMenu(onOpen = onClick, onDelete = onDeleteClick)
                }
            }
        }
        return
    }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 4.dp)
                .then(
                    if (useInternalPadding) {
                        Modifier.padding(horizontal = RowHorizontalPadding)
                    } else {
                        Modifier
                    },
                ),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LayeredPlaylistArtwork(
            thumbnailUrl = thumbnailUrl,
            videoCount = videoCount,
            modifier =
                Modifier
                    .width(142.dp)
                    .aspectRatio(16f / 9f),
        )

        PlaylistCardText(
            title = title,
            metadata = metadata,
            description = description,
            compact = false,
            modifier = Modifier.weight(1f),
        )

        if (onDeleteClick != null) {
            PlaylistCardMenu(onOpen = onClick, onDelete = onDeleteClick)
        }
    }
}

@Composable
private fun PlaylistCardMenu(
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { showMenu = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.more_options),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.open)) },
                onClick = {
                    showMenu = false
                    onOpen()
                },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_delete)) },
                onClick = {
                    showMenu = false
                    onDelete()
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
            )
        }
    }
}

/**
 * The artwork as given; a YouTube video thumbnail that fails to load (hq720 is missing on older
 * videos) falls back to hqdefault, which every video has.
 */
@Composable
private fun PlaylistArtworkImage(
    url: String,
    modifier: Modifier,
    alpha: Float = 1f,
) {
    var model by remember(url) { mutableStateOf(url) }
    AsyncImage(
        model = model,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        alpha = alpha,
        onError = { ThumbnailUrlResolver.fallbackVideoThumbnail("", model)?.let { model = it } },
    )
}

@Composable
private fun LayeredPlaylistArtwork(
    thumbnailUrl: String,
    videoCount: Int?,
    modifier: Modifier = Modifier,
) {
    val artworkUrl = thumbnailUrlOrNull(thumbnailUrl).orEmpty()
    Box(modifier = modifier) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .offset(x = (-3).dp, y = (-3).dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (artworkUrl.isNotBlank()) {
                PlaylistArtworkImage(
                    url = artworkUrl,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .blur(10.dp),
                    alpha = 0.7f,
                )
            }
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.18f)),
            )
        }
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (artworkUrl.isNotBlank()) {
                PlaylistArtworkImage(url = artworkUrl, modifier = Modifier.fillMaxSize())
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.PlaylistPlay,
                    contentDescription = null,
                    modifier =
                        Modifier
                            .size(30.dp)
                            .align(Alignment.Center),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (videoCount != null) {
                MediaTextBadge(
                    text = videoCount.toString(),
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp),
                    leadingIcon = Icons.AutoMirrored.Filled.PlaylistPlay,
                )
            }
        }
    }
}

@Composable
private fun PlaylistCardText(
    title: String,
    metadata: String?,
    description: String,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style =
                if (compact) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.bodyLarge
                },
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (compact) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (metadata != null) {
            Text(
                text = metadata,
                style =
                    if (compact) {
                        MaterialTheme.typography.bodySmall
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (description.isNotBlank()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A YouTube playlist that gave no count, such as a Mix, has none to show; a local one really can be empty. */
internal fun Playlist.knownVideoCount(): Int? = videoCount.takeUnless { it <= 0 && !isLocal }
