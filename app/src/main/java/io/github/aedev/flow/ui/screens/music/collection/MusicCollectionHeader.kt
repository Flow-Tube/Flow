package io.github.aedev.flow.ui.screens.music.collection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.layout.LocalFlowBottomInsets
import io.github.aedev.flow.ui.components.shared.FlowLoadingIndicator
import io.github.aedev.flow.ui.components.shared.LocalSharedArtworkImages
import io.github.aedev.flow.ui.components.shared.connectedButtonShapes
import io.github.aedev.flow.ui.components.shared.flowHeroArtworkSize
import io.github.aedev.flow.ui.components.shared.sharedArtwork

private const val DESCRIPTION_COLLAPSED_LINES = 2
private val DownloadProgressSize: Dp = 28.dp

/** What a music page's header shows. [downloadProgress] is null while no download runs. */
@Immutable
internal data class CollectionHeaderState(
    val kindLabel: String,
    val title: String,
    val author: String,
    val authorId: String?,
    val metadata: String,
    val description: String,
    val artworkUrl: String,
    /** The id the page was opened with, which the tapped card's artwork shares. */
    val artworkKey: String?,
    val isSaved: Boolean,
    val canSave: Boolean,
    val canShare: Boolean,
    val downloadProgress: Float?,
    /** Saved as a downloaded collection; downloading again picks up only what is new. */
    val isDownloaded: Boolean = false,
)

/** One entry of the header's ⋮ menu. */
internal class CollectionMenuItem(
    val label: String,
    val icon: ImageVector,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

internal class CollectionHeaderActions(
    val onPlay: () -> Unit,
    val onShuffle: () -> Unit,
    val onSaveToggle: () -> Unit,
    val onDownload: () -> Unit,
    val onShare: () -> Unit,
    val onAuthorClick: (String) -> Unit,
    val menu: List<CollectionMenuItem>,
)

/** The header on top of the song list, on windows too narrow for a side pane: today's full-size hero. */
@Composable
internal fun MusicCollectionHero(
    state: CollectionHeaderState,
    actions: CollectionHeaderActions,
    sortChip: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CollectionArtwork(state.artworkUrl, state.artworkKey, Modifier.size(flowHeroArtworkSize()))
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            CollectionTitleBlock(state, actions, centered = true)
        }
        CollectionPrimaryActions(actions)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CollectionSecondaryActions(state, actions)
            Spacer(Modifier.weight(1f))
            sortChip()
        }
    }
}

/** The same header as a side pane beside the songs, on wide windows. */
@Composable
internal fun MusicCollectionHeaderPane(
    state: CollectionHeaderState,
    actions: CollectionHeaderActions,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier.fillMaxSize().padding(start = 16.dp, bottom = 16.dp + LocalFlowBottomInsets.current.navigationBottom),
    ) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // The cover gives way when the pane is short, as on a TV, so the actions under it stay on screen.
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CollectionArtwork(state.artworkUrl, state.artworkKey, Modifier.weight(1f, fill = false))
                CollectionTitleBlock(state, actions, centered = false)
            }
            CollectionSecondaryActions(state, actions)
            CollectionPrimaryActions(actions)
        }
    }
}

/**
 * The collection's cover. Opened from a card, it shows the card's image until its own has loaded,
 * so the artwork flying in from the card is never blank.
 */
@Composable
internal fun CollectionArtwork(
    url: String,
    artworkKey: String?,
    modifier: Modifier,
) {
    val shape = MaterialTheme.shapes.extraLarge
    val context = LocalContext.current
    val cardImage = LocalSharedArtworkImages.current?.get(artworkKey)
    val data = url.ifBlank { cardImage?.url.orEmpty() }
    val request =
        remember(data, cardImage) {
            ImageRequest
                .Builder(context)
                .data(data)
                .placeholderMemoryCacheKey(cardImage?.memoryCacheKey)
                .build()
        }
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.aspectRatio(1f).sharedArtwork(artworkKey, shape),
    ) {
        if (data.isNotBlank()) {
            AsyncImage(model = request, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

/** The page's shape while the collection loads: its cover in the hero's place, so the card's artwork has somewhere to land. */
@Composable
internal fun MusicCollectionLoadingHero(
    artworkKey: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CollectionArtwork(url = "", artworkKey = artworkKey, modifier = Modifier.size(flowHeroArtworkSize()))
        FlowLoadingIndicator()
    }
}

@Composable
private fun ColumnScope.CollectionTitleBlock(
    state: CollectionHeaderState,
    actions: CollectionHeaderActions,
    centered: Boolean,
) {
    val align = if (centered) TextAlign.Center else TextAlign.Start
    Text(
        text = state.kindLabel,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = state.title,
        style = MaterialTheme.typography.headlineMediumEmphasized,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = align,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    val authorId = state.authorId
    if (state.author.isNotBlank()) {
        Text(
            text = state.author,
            style = MaterialTheme.typography.titleSmall,
            color = if (authorId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = align,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .clip(MaterialTheme.shapes.small)
                    .then(if (authorId != null) Modifier.clickable { actions.onAuthorClick(authorId) } else Modifier)
                    .padding(vertical = 2.dp),
        )
    }
    if (state.metadata.isNotBlank()) {
        Text(
            text = state.metadata,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = align,
        )
    }
    if (state.description.isNotBlank()) {
        var expanded by rememberSaveable(state.description) { mutableStateOf(false) }
        Text(
            text = state.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = align,
            maxLines = if (expanded) Int.MAX_VALUE else DESCRIPTION_COLLAPSED_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clip(MaterialTheme.shapes.small).clickable { expanded = !expanded },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CollectionPrimaryActions(actions: CollectionHeaderActions) {
    val height = ButtonDefaults.MediumContainerHeight
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        val leading = connectedButtonShapes(index = 0, count = 2)
        val trailing = connectedButtonShapes(index = 1, count = 2)
        Button(
            onClick = actions.onPlay,
            shapes = ButtonShapes(leading.shape, leading.pressedShape),
            contentPadding = ButtonDefaults.contentPaddingFor(height),
            modifier = Modifier.weight(1f).heightIn(min = height),
        ) {
            ActionLabel(Icons.Rounded.PlayArrow, stringResource(R.string.play), height)
        }
        FilledTonalButton(
            onClick = actions.onShuffle,
            shapes = ButtonShapes(trailing.shape, trailing.pressedShape),
            contentPadding = ButtonDefaults.contentPaddingFor(height),
            modifier = Modifier.weight(1f).heightIn(min = height),
        ) {
            ActionLabel(Icons.Rounded.Shuffle, stringResource(R.string.shuffle), height)
        }
    }
}

@Composable
private fun ActionLabel(
    icon: ImageVector,
    label: String,
    height: Dp,
) {
    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)))
    Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(height)))
    Text(text = label, style = ButtonDefaults.textStyleFor(height), maxLines = 1)
}

@Composable
private fun CollectionSecondaryActions(
    state: CollectionHeaderState,
    actions: CollectionHeaderActions,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (state.canSave) {
            IconToggleButton(checked = state.isSaved, onCheckedChange = { actions.onSaveToggle() }) {
                Icon(
                    imageVector = if (state.isSaved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                    contentDescription =
                        stringResource(
                            if (state.isSaved) R.string.ui_remove_from_library else R.string.ui_save_to_library,
                        ),
                )
            }
        }
        val progress = state.downloadProgress
        IconButton(onClick = actions.onDownload, enabled = progress == null) {
            if (progress != null) {
                CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(DownloadProgressSize))
            } else {
                Icon(
                    imageVector = if (state.isDownloaded) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                    contentDescription = stringResource(if (state.isDownloaded) R.string.downloaded else R.string.download_all),
                )
            }
        }
        if (state.canShare) {
            IconButton(onClick = actions.onShare) {
                Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.share))
            }
        }
        if (actions.menu.isNotEmpty()) CollectionOverflowMenu(actions.menu)
    }
}

@Composable
private fun CollectionOverflowMenu(items: List<CollectionMenuItem>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more_options))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { item ->
                val color = if (item.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                DropdownMenuItem(
                    text = { Text(item.label, color = color) },
                    leadingIcon = { Icon(item.icon, contentDescription = null, tint = color) },
                    onClick = {
                        open = false
                        item.onClick()
                    },
                )
            }
        }
    }
}
