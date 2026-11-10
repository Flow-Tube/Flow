/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/Flow-Tube/Flow).
 */

package io.github.aedev.flow.ui.components.music.card

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.aedev.flow.R
import io.github.aedev.flow.innertube.models.AlbumItem
import io.github.aedev.flow.innertube.models.ArtistItem
import io.github.aedev.flow.innertube.models.PlaylistItem
import io.github.aedev.flow.innertube.models.SongItem
import io.github.aedev.flow.innertube.models.YTItem
import io.github.aedev.flow.ui.components.music.common.rememberMusicArtworkColors
import io.github.aedev.flow.ui.components.shared.flowArtistShape
import io.github.aedev.flow.ui.components.shared.onMenuKey

private val TopResultArtworkSize = 104.dp
private const val SUBTITLE_ALPHA = 0.8f

/**
 * The first search hit, given a card of its own in the colours of its artwork. Artists also get
 * shuffle and radio actions as a button group whose pressed member widens.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TopResultCard(
    item: YTItem,
    onClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onRadioClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onMenuClick: (() -> Unit)? = null,
) {
    val colors = rememberMusicArtworkColors(item.thumbnail)
    val buttonHeight = ButtonDefaults.MediumContainerHeight

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .then(if (onLongClick != null) Modifier.onMenuKey(onLongClick) else Modifier)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        shape = MaterialTheme.shapes.extraLarge,
        colors =
            CardDefaults.cardColors(
                containerColor = colors.container,
                contentColor = colors.onContainer,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = item.thumbnail,
                    contentDescription = null,
                    modifier =
                        Modifier
                            .size(TopResultArtworkSize)
                            .clip(if (item is ArtistItem) flowArtistShape() else MaterialTheme.shapes.large),
                    contentScale = ContentScale.Crop,
                )
                Spacer(modifier = Modifier.width(20.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.headlineSmallEmphasized,
                        color = colors.onContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.topResultSubtitle(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onContainer.copy(alpha = SUBTITLE_ALPHA),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (onMenuClick != null) {
                    IconButton(onClick = onMenuClick) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = stringResource(R.string.more_options),
                            tint = colors.onContainer,
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = colors.onContainer,
                    )
                }
            }

            if (item is ArtistItem) {
                Spacer(modifier = Modifier.height(20.dp))
                ButtonGroup(
                    overflowIndicator = {},
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    customItem({
                        val interaction = remember { MutableInteractionSource() }
                        Button(
                            onClick = onShuffleClick,
                            shapes = ButtonDefaults.shapesFor(buttonHeight),
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = colors.accent,
                                    contentColor = colors.onAccent,
                                ),
                            interactionSource = interaction,
                            contentPadding = ButtonDefaults.contentPaddingFor(buttonHeight, hasStartIcon = true),
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .heightIn(min = buttonHeight)
                                    .animateWidth(interaction),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Shuffle,
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.iconSizeFor(buttonHeight)),
                            )
                            Spacer(modifier = Modifier.width(ButtonDefaults.iconSpacingFor(buttonHeight)))
                            Text(
                                text = stringResource(R.string.shuffle),
                                style = ButtonDefaults.textStyleFor(buttonHeight),
                            )
                        }
                    }) {}
                    customItem({
                        val interaction = remember { MutableInteractionSource() }
                        Button(
                            onClick = onRadioClick,
                            shapes = ButtonDefaults.shapesFor(buttonHeight),
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = colors.tonalContainer,
                                    contentColor = colors.onContainer,
                                ),
                            interactionSource = interaction,
                            contentPadding = ButtonDefaults.contentPaddingFor(buttonHeight, hasStartIcon = true),
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .heightIn(min = buttonHeight)
                                    .animateWidth(interaction),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Radio,
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.iconSizeFor(buttonHeight)),
                            )
                            Spacer(modifier = Modifier.width(ButtonDefaults.iconSpacingFor(buttonHeight)))
                            Text(
                                text = stringResource(R.string.radio),
                                style = ButtonDefaults.textStyleFor(buttonHeight),
                            )
                        }
                    }) {}
                }
            }
        }
    }
}

@Composable
private fun YTItem.topResultSubtitle(): String =
    when (this) {
        is ArtistItem -> stringResource(R.string.subtitle_artist)
        is SongItem -> stringResource(R.string.subtitle_song_prefix, artists.joinToString { it.name })
        is AlbumItem -> stringResource(R.string.subtitle_album_template, artists?.joinToString { it.name } ?: "")
        is PlaylistItem -> stringResource(R.string.subtitle_playlist_template, author?.name ?: "")
    }
