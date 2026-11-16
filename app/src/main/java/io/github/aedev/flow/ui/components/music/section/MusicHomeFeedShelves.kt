/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/Flow-Tube/Flow).
 */

package io.github.aedev.flow.ui.components.music.section

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.ArtistDetails
import io.github.aedev.flow.data.music.model.CommunityMusicPlaylist
import io.github.aedev.flow.data.music.model.DailyDiscoverItem
import io.github.aedev.flow.data.music.model.MUSIC_GENRE_SOURCE_PREFIX
import io.github.aedev.flow.data.music.model.MusicItemType
import io.github.aedev.flow.data.music.model.MusicPlaylist
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.recommendation.MusicSection
import io.github.aedev.flow.ui.components.music.header.MusicSectionAction
import io.github.aedev.flow.ui.components.music.sheet.MusicCollectionActionItem
import io.github.aedev.flow.ui.components.music.sheet.toCollectionActionItem
import io.github.aedev.flow.ui.screens.music.MusicUiState
import io.github.aedev.flow.ui.screens.music.MusicViewModel
import java.util.Locale

internal fun LazyListScope.trackShelf(
    id: String,
    titleRes: Int,
    tracks: List<MusicTrack>,
    downloaded: Set<String>,
    playFrom: String,
    onSongClick: (MusicTrack, List<MusicTrack>, String?) -> Unit,
    onTrackMenu: (MusicTrack) -> Unit,
) {
    if (tracks.isEmpty()) return
    item(key = id) {
        MusicTrackCardShelf(
            title = stringResource(titleRes),
            tracks = tracks,
            keyNamespace = id,
            downloadedTrackIds = downloaded,
            onTrackClick = { onSongClick(it, tracks, playFrom) },
            onTrackMenu = onTrackMenu,
        )
    }
}

internal fun LazyListScope.collectionShelf(
    id: String,
    titleRes: Int,
    collections: List<MusicPlaylist>,
    isAlbum: Boolean,
    onAlbumClick: (String) -> Unit,
    onCollectionMenu: (MusicPlaylist, Boolean) -> Unit,
) {
    if (collections.isEmpty()) return
    item(key = id) {
        MusicCollectionShelf(
            title = stringResource(titleRes),
            collections = collections,
            keyNamespace = id,
            onCollectionClick = { onAlbumClick(it.id) },
            onCollectionMenu = { onCollectionMenu(it, isAlbum) },
        )
    }
}

internal fun LazyListScope.mediaShelf(
    id: String,
    titleRes: Int,
    tracks: List<MusicTrack>,
    downloaded: Set<String>,
    onTrackClick: (MusicTrack) -> Unit,
    onPlayAll: () -> Unit,
    onTrackMenu: (MusicTrack) -> Unit,
) {
    if (tracks.isEmpty()) return
    item(key = id) {
        MediaTrackListSection(
            title = stringResource(titleRes),
            tracks = tracks,
            downloadedTrackIds = downloaded,
            onPlayAll = onPlayAll,
            onTrackClick = onTrackClick,
            onTrackMenu = onTrackMenu,
        )
    }
}

internal fun LazyListScope.dailyDiscover(
    items: List<DailyDiscoverItem>,
    downloaded: Set<String>,
    onSongClick: (MusicTrack, List<MusicTrack>, String?) -> Unit,
    onTrackMenu: (MusicTrack) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "daily_discover") {
        val tracks = items.map { it.recommendation }
        DailyDiscoverShelf(
            items = items,
            downloadedTrackIds = downloaded,
            action =
                tracks.firstOrNull()?.let { first ->
                    MusicSectionAction.PlayAll { onSongClick(first, tracks, "daily_discover") }
                },
            onItemClick = { onSongClick(it.recommendation, tracks, "daily_discover") },
            onItemMenu = { onTrackMenu(it.recommendation) },
        )
    }
}

internal fun LazyListScope.quickPicks(
    tracks: List<MusicTrack>,
    downloaded: Set<String>,
    state: LazyGridState,
    onSongClick: (MusicTrack, List<MusicTrack>, String?) -> Unit,
    onTrackMenu: (MusicTrack) -> Unit,
) {
    if (tracks.isEmpty()) return
    item(key = "quick_picks") {
        MusicQuickPicksShelf(
            title = stringResource(R.string.section_quick_picks),
            tracks = tracks,
            downloadedTrackIds = downloaded,
            state = state,
            action =
                tracks.firstOrNull()?.let { first ->
                    MusicSectionAction.PlayAll { onSongClick(first, tracks, "quick_picks") }
                },
            onTrackClick = { onSongClick(it, tracks, "quick_picks") },
            onTrackMenu = onTrackMenu,
        )
    }
}

internal fun LazyListScope.charts(
    tracks: List<MusicTrack>,
    downloaded: Set<String>,
    onSongClick: (MusicTrack, List<MusicTrack>, String?) -> Unit,
    onTrackMenu: (MusicTrack) -> Unit,
) {
    if (tracks.isEmpty()) return
    item(key = "charts") {
        MusicChartsShelf(
            title = stringResource(R.string.trending),
            tracks = tracks,
            downloadedTrackIds = downloaded,
            onTrackClick = { onSongClick(it, tracks, "charts") },
            onTrackMenu = onTrackMenu,
        )
    }
}

internal fun LazyListScope.chartPlaylists(
    countryCode: String?,
    playlists: List<MusicPlaylist>,
    onPlaylistClick: (String) -> Unit,
    onCollectionMenu: (MusicPlaylist, Boolean) -> Unit,
) {
    if (playlists.isEmpty()) return
    item(key = "chart_playlists") {
        MusicCollectionShelf(
            title = chartShelfTitle(countryCode, R.string.section_charts_in, R.string.section_charts_global),
            collections = playlists,
            keyNamespace = "chart_playlists",
            onCollectionClick = { onPlaylistClick(it.id) },
            onCollectionMenu = { onCollectionMenu(it, false) },
        )
    }
}

internal fun LazyListScope.chartArtists(
    countryCode: String?,
    artists: List<ArtistDetails>,
    onArtistClick: (String) -> Unit,
) {
    if (artists.isEmpty()) return
    item(key = "chart_artists") {
        MusicArtistShelf(
            title = chartShelfTitle(countryCode, R.string.section_top_artists_in, R.string.section_top_artists_global),
            artists = artists,
            key = { "chart_artists:${it.channelId}" },
            name = { it.name },
            thumbnailUrl = { it.thumbnailUrl },
            onArtistClick = { onArtistClick(it.channelId) },
        )
    }
}

@Composable
internal fun chartShelfTitle(
    countryCode: String?,
    countryTitleRes: Int,
    globalTitleRes: Int,
): String =
    countryCode
        ?.let {
            stringResource(
                countryTitleRes,
                Locale
                    .Builder()
                    .setRegion(it)
                    .build()
                    .displayCountry,
            )
        }
        ?: stringResource(globalTitleRes)

internal fun LazyListScope.community(
    playlists: List<CommunityMusicPlaylist>,
    downloaded: Set<String>,
    onAlbumClick: (String) -> Unit,
    onSongClick: (MusicTrack, List<MusicTrack>, String?) -> Unit,
    onTrackMenu: (MusicTrack) -> Unit,
    onCollectionMenu: (MusicCollectionActionItem) -> Unit,
) {
    if (playlists.isEmpty()) return
    item(key = "from_the_community") {
        CommunityPlaylistsSection(
            playlists = playlists,
            downloadedTrackIds = downloaded,
            onPlaylistClick = { onAlbumClick(it.playlist.id) },
            onPlaylistAction = { onCollectionMenu(it.playlist.toCollectionActionItem(isAlbum = false)) },
            onTrackClick = { track, tracks -> onSongClick(track, tracks, "from_the_community") },
            onTrackMenu = onTrackMenu,
        )
    }
}

internal fun LazyListScope.genres(
    genreTracks: Map<String, List<MusicTrack>>,
    downloaded: Set<String>,
    onSongClick: (MusicTrack, List<MusicTrack>, String?) -> Unit,
    onTrackMenu: (MusicTrack) -> Unit,
) {
    genreTracks.entries.take(3).forEachIndexed { index, (genre, tracks) ->
        item(key = "genre:$index:$genre") {
            MusicTrackCardShelf(
                title = stringResource(R.string.genre_mix_template, genre),
                tracks = tracks,
                keyNamespace = "genre_$genre",
                downloadedTrackIds = downloaded,
                onTrackClick = { onSongClick(it, tracks, MUSIC_GENRE_SOURCE_PREFIX + genre) },
                onTrackMenu = onTrackMenu,
            )
        }
    }
}

internal fun LazyListScope.similarTo(
    uiState: MusicUiState,
    downloaded: Set<String>,
    onSongClick: (MusicTrack, List<MusicTrack>, String?) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onTrackMenu: (MusicTrack) -> Unit,
    onCollectionMenu: (MusicTrack) -> Unit,
) {
    val dailyMixes = uiState.dailyMixSections
    val moreFromArtist = uiState.moreFromArtistSections
    val emittedKeys = mutableSetOf<String>()

    fun moreFromShelf(section: MusicSection) {
        val key = "more_from:${section.seedId}"
        if (!emittedKeys.add(key)) return
        item(key = key) {
            MusicTrackCardShelf(
                title = section.title,
                tracks = section.tracks,
                keyNamespace = "more_from_${section.seedId}",
                subtitle = section.label,
                action = section.seedId?.let { seedId -> MusicSectionAction.Navigate { onArtistClick(seedId) } },
                onTrackClick = { onSongClick(it, section.tracks.filterNot { track -> track.isCollection }, section.title) },
                onTrackMenu = onTrackMenu,
                onCollectionClick = { onAlbumClick(it.videoId) },
                onCollectionMenu = onCollectionMenu,
            )
        }
    }

    (dailyMixes + uiState.similarToSections).forEachIndexed { index, section ->
        item(key = "similar_to:$index:${section.title}") {
            MusicTrackCardShelf(
                title = section.title,
                tracks = section.tracks,
                lane = if (index < dailyMixes.size) MusicLane.Hero else MusicLane.Cards,
                keyNamespace = "similar_${index}_${section.title}",
                subtitle = section.label ?: section.subtitle,
                leading =
                    section.thumbnailUrl?.let { url ->
                        { MusicSeedThumbnail(url = url, isArtist = section.isArtistSeed) }
                    },
                action =
                    section.seedId?.takeIf { it.isNotBlank() }?.let { seedId ->
                        MusicSectionAction.Navigate {
                            if (section.isArtistSeed) {
                                onArtistClick(seedId)
                            } else if (seedId.startsWith(MusicViewModel.DAILY_MIX_ID_PREFIX)) {
                                onAlbumClick(seedId)
                            }
                        }
                    },
                downloadedTrackIds = downloaded,
                onTrackClick = { onSongClick(it, section.tracks.filterNot { track -> track.isCollection }, section.title) },
                onTrackMenu = onTrackMenu,
                onCollectionClick = { if (it.itemType == MusicItemType.ARTIST) onArtistClick(it.videoId) else onAlbumClick(it.videoId) },
                onCollectionMenu = { if (it.itemType != MusicItemType.ARTIST) onCollectionMenu(it) },
                trackSubtitle = { if (it.itemType == MusicItemType.ARTIST) stringResource(R.string.artist) else it.artist },
            )
        }
        val seedId = section.seedId ?: return@forEachIndexed
        val performances = uiState.otherPerformanceSections.firstOrNull { it.seedId == seedId }
        if (performances != null && emittedKeys.add("other_performances:$seedId")) {
            item(key = "other_performances:$seedId") {
                MusicQuickPicksShelf(
                    title = performances.title,
                    subtitle = performances.label,
                    tracks = performances.tracks,
                    downloadedTrackIds = downloaded,
                    action =
                        performances.tracks.firstOrNull()?.let { first ->
                            MusicSectionAction.PlayAll { onSongClick(first, performances.tracks, performances.title) }
                        },
                    onTrackClick = { onSongClick(it, performances.tracks, performances.title) },
                    onTrackMenu = onTrackMenu,
                )
            }
        }
        if (section.isArtistSeed) moreFromArtist.firstOrNull { it.seedId == seedId }?.let(::moreFromShelf)
    }
    moreFromArtist
        .filter { more -> uiState.similarToSections.none { it.isArtistSeed && it.seedId == more.seedId } }
        .forEach(::moreFromShelf)
}

internal fun LazyListScope.dynamicHome(
    uiState: MusicUiState,
    downloaded: Set<String>,
    onSongClick: (MusicTrack, List<MusicTrack>, String?) -> Unit,
    onAlbumClick: (String) -> Unit,
    onTrackMenu: (MusicTrack) -> Unit,
    onCollectionMenu: (MusicTrack) -> Unit,
) {
    uiState.dynamicSections
        .filterNot { it.title.isDuplicateOfADedicatedShelf() }
        .forEachIndexed { index, section ->
            item(key = "dynamic:$index:${section.title}") {
                MusicTrackCardShelf(
                    title = section.title,
                    tracks = section.tracks,
                    keyNamespace = "dynamic_${index}_${section.title}",
                    downloadedTrackIds = downloaded,
                    onTrackClick = { onSongClick(it, section.tracks, section.title) },
                    onTrackMenu = onTrackMenu,
                    onCollectionClick = { onAlbumClick(it.videoId) },
                    onCollectionMenu = onCollectionMenu,
                )
            }
        }
}

internal fun LazyListScope.newReleases(
    tracks: List<MusicTrack>,
    downloaded: Set<String>,
    onSongClick: (MusicTrack, List<MusicTrack>, String?) -> Unit,
    onAlbumClick: (String) -> Unit,
    onTrackMenu: (MusicTrack) -> Unit,
    onCollectionMenu: (MusicTrack) -> Unit,
) {
    if (tracks.isEmpty()) return
    item(key = "new_releases") {
        MusicTrackCardShelf(
            title = stringResource(R.string.section_new_releases),
            tracks = tracks.take(10),
            keyNamespace = "new_releases",
            downloadedTrackIds = downloaded,
            trackSubtitle = { stringResource(R.string.subtitle_single_template, it.artist) },
            onTrackClick = { onSongClick(it, tracks, "new_releases") },
            onTrackMenu = onTrackMenu,
            onCollectionClick = { onAlbumClick(it.videoId) },
            onCollectionMenu = onCollectionMenu,
        )
    }
}

/**
 * The titles InnerTube's dynamic home shares with shelves Flow already renders itself.
 */
internal fun String.isDuplicateOfADedicatedShelf(): Boolean =
    listOf(
        "Quick picks",
        "Music videos",
        "Music videos for you",
        "Live performances",
        "Long listens",
        "Mixed for you",
        "Recommended",
        "Listen again",
    ).any { contains(it, ignoreCase = true) }
