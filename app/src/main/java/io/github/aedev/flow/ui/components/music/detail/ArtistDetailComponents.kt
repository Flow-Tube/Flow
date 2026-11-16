/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/Flow-Tube/Flow).
 */

package io.github.aedev.flow.ui.components.music.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedToggleButton
import androidx.compose.material3.OutlinedToggleButtonDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.ArtistDetails
import io.github.aedev.flow.data.music.model.MusicTextLink
import io.github.aedev.flow.ui.components.shared.flowWindowWidth
import io.github.aedev.flow.utils.formatViewCount

private val HeroMaxHeight = 400.dp
private val StartingStrokeWidth = 2.dp
private const val COLLAPSED_BIO_LINES = 3

/** The queues the artist page fetches before playing, so the tapped button can show it is working. */
enum class ArtistQueue { SHUFFLE, RADIO }

/**
 * The artist page header: a full-bleed portrait ending in the extra-extra-large bottom corners,
 * the name below it so no text ever sits on the photo, then one row of actions, each one tap:
 * Subscribe, Radio, Shuffle and a round Play. The audience figures follow the actions. Everything
 * reads the page scheme the portrait seeded. A null [onRadioClick] leaves Radio out.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ArtistHero(
    artist: ArtistDetails,
    startingQueue: ArtistQueue?,
    onFollowClick: () -> Unit,
    onPlayClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onRadioClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val imageUrl = artist.thumbnailUrl.ifEmpty { artist.bannerUrl }
    val heroHeight = artistHeroPhotoHeight()
    val heroShape =
        MaterialTheme.shapes.extraExtraLarge.copy(
            topStart = CornerSize(0.dp),
            topEnd = CornerSize(0.dp),
        )

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(heroHeight)
                    .clip(heroShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            AsyncImage(
                model =
                    ImageRequest
                        .Builder(context)
                        .data(imageUrl)
                        .crossfade(true)
                        .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }

        Text(
            text = artist.name,
            style = MaterialTheme.typography.displaySmallEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .padding(horizontal = 24.dp)
                    .padding(top = 20.dp),
        )

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedToggleButton(
                checked = artist.isSubscribed,
                onCheckedChange = { onFollowClick() },
                shapes = ToggleButtonDefaults.shapesFor(ButtonDefaults.MinHeight),
                colors =
                    OutlinedToggleButtonDefaults.colors(
                        checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Icon(
                    imageVector = if (artist.isSubscribed) Icons.Rounded.Check else Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                Text(
                    text = stringResource(if (artist.isSubscribed) R.string.subscribed else R.string.subscribe),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                modifier = Modifier.padding(start = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val busy = startingQueue != null
                if (onRadioClick != null) {
                    ArtistQueueButton(
                        icon = Icons.Rounded.Radio,
                        label = stringResource(R.string.start_radio),
                        starting = startingQueue == ArtistQueue.RADIO,
                        onClick = { if (!busy) onRadioClick() },
                    )
                }
                ArtistQueueButton(
                    icon = Icons.Rounded.Shuffle,
                    label = stringResource(R.string.shuffle),
                    starting = startingQueue == ArtistQueue.SHUFFLE,
                    onClick = { if (!busy) onShuffleClick() },
                )
                FilledIconButton(
                    onClick = { if (!busy) onPlayClick() },
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(IconButtonDefaults.mediumContainerSize()),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(R.string.play),
                        modifier = Modifier.size(IconButtonDefaults.mediumIconSize),
                    )
                }
            }
        }

        val figures =
            listOfNotNull(
                artist.subscriberCount
                    .takeIf { it > 0 }
                    ?.let { stringResource(R.string.subscribers_count_template, formatViewCount(it)) },
                artist.monthlyListenersText,
            )
        if (figures.isNotEmpty()) {
            Text(
                text = figures.joinToString(" ${stringResource(R.string.metadata_separator)} "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 8.dp),
            )
        }
    }
}

/** The hero photo's height. The page runs under the status bar, so the photo grows by it and keeps its visible size. */
@Composable
fun artistHeroPhotoHeight(): Dp =
    flowWindowWidth().coerceAtMost(HeroMaxHeight) + WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

/**
 * Shuffle or Radio: a tonal icon button that names itself in a tooltip, and turns into a spinner
 * while the queue it fetches is on its way.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ArtistQueueButton(
    icon: ImageVector,
    label: String,
    starting: Boolean,
    onClick: () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(text = label) } },
        state = rememberTooltipState(),
    ) {
        FilledTonalIconButton(
            onClick = onClick,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(IconButtonDefaults.smallContainerSize()),
        ) {
            if (starting) {
                CircularProgressIndicator(
                    color = LocalContentColor.current,
                    strokeWidth = StartingStrokeWidth,
                    modifier = Modifier.size(IconButtonDefaults.smallIconSize),
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(IconButtonDefaults.smallIconSize),
                )
            }
        }
    }
}

/**
 * The expandable artist description. [links] become tappable, such as its Wikipedia source.
 */
@Composable
fun ArtistBio(
    description: String,
    links: List<MusicTextLink>,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val text =
        remember(description, links, linkColor) {
            buildAnnotatedString {
                append(description)
                val styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                links
                    .filter { it.start in 0 until it.end && it.end <= description.length }
                    .forEach { addLink(LinkAnnotation.Url(it.url, styles), it.start, it.end) }
            }
        }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (isExpanded) Int.MAX_VALUE else COLLAPSED_BIO_LINES,
        overflow = TextOverflow.Ellipsis,
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpanded)
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp)
                .animateContentSize(),
    )
}
