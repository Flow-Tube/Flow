/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/Flow-Tube/Flow).
 */

package io.github.aedev.flow.ui.components.music.section

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.DailyDiscoverItem
import io.github.aedev.flow.data.music.model.MusicItemType
import io.github.aedev.flow.data.music.model.MusicPlaylist
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.innertube.pages.HomePage
import io.github.aedev.flow.innertube.pages.MoodAndGenres
import io.github.aedev.flow.ui.components.currentGridThumbnailHeight
import io.github.aedev.flow.ui.components.music.card.MusicHeroCard
import io.github.aedev.flow.ui.components.music.common.MusicMoodButton
import io.github.aedev.flow.ui.components.music.common.MusicMoodTone
import io.github.aedev.flow.ui.components.music.common.MusicThumbnail
import io.github.aedev.flow.ui.components.music.header.MusicSectionAction
import io.github.aedev.flow.ui.components.music.header.MusicSectionHeader
import io.github.aedev.flow.ui.components.music.item.MusicCollectionCard
import io.github.aedev.flow.ui.components.music.item.MusicItemDensity
import io.github.aedev.flow.ui.components.music.item.MusicTrackItem
import io.github.aedev.flow.ui.components.shared.ChartRankBadge
import io.github.aedev.flow.ui.components.shared.FlowFilterChip
import io.github.aedev.flow.ui.components.shared.flowArtistShape
import io.github.aedev.flow.ui.components.shared.flowGridCellWidth
import io.github.aedev.flow.ui.components.shared.flowGridColumns
import io.github.aedev.flow.ui.components.shared.flowLaneItemWidth
import io.github.aedev.flow.ui.theme.Dimensions

private val ArtistPortraitSize = 84.dp
private val SeedThumbnailSize = 40.dp
private val QuickPickMaxWidth = 360.dp
private val QuickPickPeek = 48.dp
private val ChartMaxWidth = 320.dp
private val ChartPeek = 56.dp
private const val MOOD_ROWS = 3

/**
 * How a track shelf lays its items out: a lane of standard cards, or the wide hero carousel that
 * Daily Discover and the Daily Mixes use.
 */
enum class MusicLane {
    Cards,
    Hero,
}

/**
 * True when a shelf entry is really a collection wearing a track's shape — InnerTube returns albums
 * and playlists inside track lanes, and tapping one must open the collection, not start playback.
 */
val MusicTrack.isCollection: Boolean
    get() = itemType != MusicItemType.SONG

/**
 * A lane of track cards. Handles the album/playlist routing that every track shelf needs, so the
 * itemType check lives here once instead of at each call site.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicTrackCardShelf(
    title: String,
    tracks: List<MusicTrack>,
    keyNamespace: String,
    onTrackClick: (MusicTrack) -> Unit,
    onTrackMenu: (MusicTrack) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    action: MusicSectionAction? = null,
    lane: MusicLane = MusicLane.Cards,
    downloadedTrackIds: Set<String> = emptySet(),
    onCollectionClick: ((MusicTrack) -> Unit)? = null,
    onCollectionMenu: ((MusicTrack) -> Unit)? = null,
    trackSubtitle: @Composable (MusicTrack) -> String = { it.artist },
) {
    val key: (MusicTrack) -> Any = { "$keyNamespace:${it.videoId}" }

    when (lane) {
        MusicLane.Cards -> {
            val thumbnailHeight = currentGridThumbnailHeight()
            val artistShape = flowArtistShape()
            MusicShelf(
                title = title,
                items = tracks,
                key = key,
                modifier = modifier,
                subtitle = subtitle,
                leading = leading,
                action = action,
            ) { track ->
                val isArtist = track.itemType == MusicItemType.ARTIST
                MusicCollectionCard(
                    title = track.title,
                    subtitle = trackSubtitle(track),
                    thumbnailUrl = track.thumbnailUrl,
                    thumbnailHeight = thumbnailHeight,
                    shape = if (isArtist) artistShape else MaterialTheme.shapes.large,
                    horizontalAlignment = if (isArtist) Alignment.CenterHorizontally else Alignment.Start,
                    mediaId = track.videoId,
                    isDownloaded = downloadedTrackIds.contains(track.videoId),
                    onClick = { track.open(onTrackClick, onCollectionClick) },
                    onLongClick = { track.open(onTrackMenu, onCollectionMenu) },
                    artworkKey = track.videoId.takeIf { track.isCollection && onCollectionClick != null },
                )
            }
        }

        MusicLane.Hero -> {
            MusicHeroLane(
                title = title,
                items = tracks,
                key = key,
                modifier = modifier,
                subtitle = subtitle,
                leading = leading,
                action = action,
            ) { track, captionAlpha ->
                MusicHeroCard(
                    title = track.title,
                    subtitle = trackSubtitle(track),
                    thumbnailUrl = track.highResThumbnailUrl,
                    artModifier = Modifier.maskClip(MaterialTheme.shapes.extraLarge),
                    mediaId = track.videoId,
                    isDownloaded = downloadedTrackIds.contains(track.videoId),
                    captionAlpha = captionAlpha,
                    onClick = { track.open(onTrackClick, onCollectionClick) },
                    onLongClick = { track.open(onTrackMenu, onCollectionMenu) },
                )
            }
        }
    }
}

private fun MusicTrack.open(
    asTrack: (MusicTrack) -> Unit,
    asCollection: ((MusicTrack) -> Unit)?,
) {
    if (isCollection && asCollection != null) asCollection(this) else asTrack(this)
}

/**
 * A lane of album or playlist cards.
 */
@Composable
fun MusicCollectionShelf(
    title: String,
    collections: List<MusicPlaylist>,
    keyNamespace: String,
    onCollectionClick: (MusicPlaylist) -> Unit,
    onCollectionMenu: (MusicPlaylist) -> Unit,
    modifier: Modifier = Modifier,
    action: MusicSectionAction? = null,
    collectionSubtitle: @Composable (MusicPlaylist) -> String? = { it.author },
) {
    val thumbnailHeight = currentGridThumbnailHeight()

    MusicShelf(
        title = title,
        items = collections,
        key = { "$keyNamespace:${it.id}" },
        modifier = modifier,
        action = action,
    ) { collection ->
        MusicCollectionCard(
            title = collection.title,
            subtitle = collectionSubtitle(collection),
            thumbnailUrl = collection.thumbnailUrl,
            thumbnailHeight = thumbnailHeight,
            onClick = { onCollectionClick(collection) },
            onLongClick = { onCollectionMenu(collection) },
            artworkKey = collection.id,
        )
    }
}

/**
 * A lane of artist portraits in the artist shape, for any model that carries a name and artwork.
 */
@Composable
fun <T> MusicArtistShelf(
    title: String,
    artists: List<T>,
    key: (T) -> Any,
    name: (T) -> String,
    thumbnailUrl: (T) -> String?,
    onArtistClick: (T) -> Unit,
    modifier: Modifier = Modifier,
    action: MusicSectionAction? = null,
    subtitle: @Composable (T) -> String? = { null },
) {
    val artistShape = flowArtistShape()

    MusicShelf(
        title = title,
        items = artists,
        key = key,
        modifier = modifier,
        action = action,
    ) { artist ->
        MusicCollectionCard(
            title = name(artist),
            subtitle = subtitle(artist),
            thumbnailUrl = thumbnailUrl(artist),
            thumbnailHeight = ArtistPortraitSize,
            shape = artistShape,
            horizontalAlignment = Alignment.CenterHorizontally,
            onClick = { onArtistClick(artist) },
        )
    }
}

/**
 * A four-row lane of grouped track rows — the Quick Picks shape. Columns fill a phone with the
 * next column peeking in and stop growing on wider windows.
 */
@Composable
fun MusicQuickPicksShelf(
    title: String,
    tracks: List<MusicTrack>,
    onTrackClick: (MusicTrack) -> Unit,
    onTrackMenu: (MusicTrack) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: MusicSectionAction? = null,
    downloadedTrackIds: Set<String> = emptySet(),
    state: LazyGridState = rememberLazyGridState(),
) {
    val rowWidth = flowLaneItemWidth(maxWidth = QuickPickMaxWidth, peek = QuickPickPeek)

    MusicTrackShelf(
        title = title,
        items = tracks,
        key = { "quick_picks:${it.videoId}" },
        modifier = modifier,
        subtitle = subtitle,
        action = action,
        state = state,
    ) { track, shape ->
        MusicTrackItem(
            track = track,
            density = MusicItemDensity.Compact,
            isDownloaded = downloadedTrackIds.contains(track.videoId),
            showMenu = false,
            shape = shape,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onClick = { onTrackClick(track) },
            onLongClick = { onTrackMenu(track) },
            modifier = Modifier.width(rowWidth),
        )
    }
}

/**
 * A four-row lane of ranked, grouped track rows — the Charts shape.
 */
@Composable
fun MusicChartsShelf(
    title: String,
    tracks: List<MusicTrack>,
    onTrackClick: (MusicTrack) -> Unit,
    onTrackMenu: (MusicTrack) -> Unit,
    modifier: Modifier = Modifier,
    downloadedTrackIds: Set<String> = emptySet(),
) {
    val ranked = remember(tracks) { tracks.take(20).mapIndexed { index, track -> index + 1 to track } }
    val rowWidth = flowLaneItemWidth(maxWidth = ChartMaxWidth, peek = ChartPeek)

    MusicTrackShelf(
        title = title,
        items = ranked,
        key = { (_, track) -> "charts:${track.videoId}" },
        modifier = modifier,
    ) { (rank, track), shape ->
        MusicTrackItem(
            track = track,
            density = MusicItemDensity.Compact,
            leadingContent = { ChartRankBadge(rank) },
            showMenu = false,
            shape = shape,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            isDownloaded = downloadedTrackIds.contains(track.videoId),
            onClick = { onTrackClick(track) },
            onLongClick = { onTrackMenu(track) },
            modifier = Modifier.width(rowWidth),
        )
    }
}

/**
 * The Daily Discover hero lane: one recommendation in focus, the next ones peeking in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyDiscoverShelf(
    items: List<DailyDiscoverItem>,
    onItemClick: (DailyDiscoverItem) -> Unit,
    onItemMenu: (DailyDiscoverItem) -> Unit,
    modifier: Modifier = Modifier,
    action: MusicSectionAction? = null,
    downloadedTrackIds: Set<String> = emptySet(),
) {
    MusicHeroLane(
        title = stringResource(R.string.section_daily_discover),
        subtitle = stringResource(R.string.daily_discover_subtitle),
        items = items,
        key = { "daily_discover:${it.recommendation.videoId}" },
        modifier = modifier,
        action = action,
    ) { item, captionAlpha ->
        val track = item.recommendation
        MusicHeroCard(
            title = track.title,
            subtitle = track.artist,
            thumbnailUrl = track.highResThumbnailUrl,
            artModifier = Modifier.maskClip(MaterialTheme.shapes.extraLarge),
            mediaId = track.videoId,
            isDownloaded = downloadedTrackIds.contains(track.videoId),
            captionAlpha = captionAlpha,
            onClick = { onItemClick(item) },
            onLongClick = { onItemMenu(item) },
        )
    }
}

/**
 * The mood and genre tile grid shown on the home feed.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MusicMoodsShelf(
    moods: List<MoodAndGenres>,
    onMoodClick: (MoodAndGenres.Item) -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (moods.isEmpty()) return

    val moodItems = remember(moods) { moods.flatMap { it.items }.distinctBy { it.title } }
    val columns = flowGridColumns(compact = 2, medium = 3, expanded = 4)
    val buttonWidth = flowGridCellWidth(columns = columns)
    val gridHeight = ButtonDefaults.MediumContainerHeight * MOOD_ROWS + Dimensions.ItemSpacing * (MOOD_ROWS - 1)

    Column(modifier = modifier.fillMaxWidth()) {
        MusicSectionHeader(
            title = stringResource(R.string.section_mood_and_genres),
            action = MusicSectionAction.Navigate(onSeeAll),
        )
        LazyHorizontalGrid(
            rows = GridCells.Fixed(MOOD_ROWS),
            contentPadding = PaddingValues(horizontal = Dimensions.ContentPaddingHorizontal),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.ItemSpacing),
            verticalArrangement = Arrangement.spacedBy(Dimensions.ItemSpacing),
            modifier =
                Modifier
                    .height(gridHeight)
                    .fillMaxWidth(),
        ) {
            itemsIndexed(items = moodItems, key = { _, item -> item.title }) { index, item ->
                MusicMoodButton(
                    title = item.title,
                    onClick = { onMoodClick(item) },
                    tone = MusicMoodTone.forIndex(index),
                    modifier = Modifier.width(buttonWidth),
                )
            }
        }
    }
}

/**
 * The home feed's filter chips.
 */
@Composable
fun MusicHomeChipRow(
    chips: List<HomePage.Chip>,
    selectedChipTitle: String?,
    onChipToggle: (HomePage.Chip?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (chips.isEmpty()) return

    LazyRow(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = Dimensions.ContentPaddingHorizontal),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = chips.distinctBy { it.title }, key = { it.title }) { chip ->
            val isSelected = selectedChipTitle == chip.title
            FlowFilterChip(
                label = chip.title,
                selected = isSelected,
                onClick = { onChipToggle(if (isSelected) null else chip) },
            )
        }
    }
}

/**
 * The zero-network brain shelves (On Repeat, the time-of-day rotation, Rediscover).
 */
@Composable
fun BrainShelf(
    title: String,
    tracks: List<MusicTrack>,
    playFrom: String,
    onSongClick: (MusicTrack, List<MusicTrack>, String?) -> Unit,
    onTrackMenu: (MusicTrack) -> Unit,
    modifier: Modifier = Modifier,
) {
    MusicTrackCardShelf(
        title = title,
        tracks = tracks,
        keyNamespace = playFrom,
        modifier = modifier,
        onTrackClick = { onSongClick(it, tracks, playFrom) },
        onTrackMenu = onTrackMenu,
    )
}

/**
 * The artwork of the seed a "similar to" shelf was built from.
 */
@Composable
fun MusicSeedThumbnail(
    url: String,
    isArtist: Boolean,
    modifier: Modifier = Modifier,
) {
    MusicThumbnail(
        thumbnailUrl = url,
        size = SeedThumbnailSize,
        shape = if (isArtist) flowArtistShape() else MaterialTheme.shapes.small,
        modifier = modifier,
    )
}

/**
 * A named group of mood/genre tiles laid out in rows of [itemsPerRow] — the moods page shape.
 */
@Composable
fun MoodCategorySection(
    title: String,
    items: List<MoodAndGenres.Item>,
    itemsPerRow: Int,
    onMoodClick: (MoodAndGenres.Item) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 6.dp)) {
        MusicSectionHeader(title = title)
        items.chunked(itemsPerRow).forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(Dimensions.ItemSpacing),
            ) {
                row.forEachIndexed { columnIndex, item ->
                    MusicMoodButton(
                        title = item.title,
                        onClick = { onMoodClick(item) },
                        tone = MusicMoodTone.forIndex(rowIndex * itemsPerRow + columnIndex),
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(itemsPerRow - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
