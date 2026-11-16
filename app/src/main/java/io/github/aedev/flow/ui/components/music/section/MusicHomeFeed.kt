/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/Flow-Tube/Flow).
 */

package io.github.aedev.flow.ui.components.music.section

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.MusicItemType
import io.github.aedev.flow.data.music.model.MusicPlaylist
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.recommendation.music.MusicTimeBucket
import io.github.aedev.flow.innertube.pages.HomePage
import io.github.aedev.flow.innertube.pages.MoodAndGenres
import io.github.aedev.flow.ui.components.music.sheet.MusicCollectionActionItem
import io.github.aedev.flow.ui.components.music.sheet.toCollectionActionItem
import io.github.aedev.flow.ui.components.shared.FlowFeedProgress
import io.github.aedev.flow.ui.screens.music.MusicUiState

/**
 * Everything the music home feed draws, one section component per block.
 *
 * The screen supplies already-collected state and plain callbacks — no ViewModel reaches this far,
 * so the feed cannot start work of its own and the subscription count still tracks the screen.
 */
@Suppress("LongParameterList")
fun LazyListScope.musicHomeFeed(
    uiState: MusicUiState,
    sectionOrder: List<HomeSectionType>,
    quickPickTracks: List<MusicTrack>,
    speedDialTracks: List<MusicTrack>,
    popularArtists: List<MusicTrack>,
    library: MusicHomeLibrary,
    quickPicksGridState: LazyGridState,
    onSongClick: (MusicTrack, List<MusicTrack>, String?) -> Unit,
    onVideoClick: (MusicTrack) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onMoodsClick: (MoodAndGenres.Item?) -> Unit,
    onChipToggle: (HomePage.Chip?) -> Unit,
    onTrackMenu: (MusicTrack) -> Unit,
    onCollectionMenu: (MusicCollectionActionItem) -> Unit,
    onLoadMore: () -> Unit,
    onChipRetry: () -> Unit,
    onChipLoadMore: () -> Unit,
) {
    val downloaded = uiState.downloadedTrackIds

    fun shown(shelf: MusicHomeShelf) = shelf !in library.hidden

    fun trackCollectionMenu(track: MusicTrack) =
        onCollectionMenu(
            MusicCollectionActionItem(
                id = track.videoId,
                title = track.title,
                subtitle = track.artist,
                thumbnailUrl = track.thumbnailUrl,
                isAlbum = track.itemType == MusicItemType.ALBUM,
            ),
        )

    fun collectionMenu(
        collection: MusicPlaylist,
        isAlbum: Boolean,
    ) = onCollectionMenu(collection.toCollectionActionItem(isAlbum))

    if (uiState.homeChips.isNotEmpty()) {
        item(key = "home_chips") {
            MusicHomeChipRow(
                chips = uiState.homeChips,
                selectedChipTitle = uiState.homeChipPage?.chip?.title,
                onChipToggle = onChipToggle,
            )
        }
    }

    uiState.homeChipPage?.let { page ->
        musicHomeChipPage(page, downloaded, onSongClick, onAlbumClick, onTrackMenu, ::trackCollectionMenu, onChipRetry, onChipLoadMore)
        return
    }

    yourLibrary(library, onArtistClick)

    if (shown(MusicHomeShelf.LISTEN_AGAIN) && uiState.listenAgain.isNotEmpty()) {
        item(key = "listen_again") {
            MusicTrackCardShelf(
                title = stringResource(R.string.section_listen_again),
                tracks = uiState.listenAgain,
                keyNamespace = "listen_again",
                downloadedTrackIds = downloaded,
                onTrackClick = { onSongClick(it, uiState.listenAgain, "listen_again") },
                onTrackMenu = onTrackMenu,
            )
        }
    }

    if (shown(MusicHomeShelf.ON_REPEAT) && uiState.onRepeatTracks.isNotEmpty()) {
        item(key = "on_repeat") {
            BrainShelf(
                title = stringResource(R.string.section_on_repeat),
                tracks = uiState.onRepeatTracks,
                playFrom = "on_repeat",
                onSongClick = onSongClick,
                onTrackMenu = onTrackMenu,
            )
        }
    }

    val rotationBucket = uiState.rotationBucket
    if (shown(MusicHomeShelf.ROTATION) && uiState.rotationTracks.isNotEmpty() && rotationBucket != null) {
        item(key = "rotation") {
            BrainShelf(
                title = stringResource(rotationBucket.rotationTitleRes()),
                tracks = uiState.rotationTracks,
                playFrom = "rotation",
                onSongClick = onSongClick,
                onTrackMenu = onTrackMenu,
            )
        }
    }

    if (shown(MusicHomeShelf.SPEED_DIAL) && speedDialTracks.isNotEmpty()) {
        item(key = "speed_dial") {
            SpeedDialSection(
                speedDialTracks = speedDialTracks,
                downloadedTrackIds = downloaded,
                onSongClick = onSongClick,
                onTrackMenu = onTrackMenu,
            )
        }
    }

    if (shown(MusicHomeShelf.REDISCOVER) && uiState.rediscoverTracks.isNotEmpty()) {
        item(key = "rediscover") {
            BrainShelf(
                title = stringResource(R.string.section_rediscover),
                tracks = uiState.rediscoverTracks,
                playFrom = "rediscover",
                onSongClick = onSongClick,
                onTrackMenu = onTrackMenu,
            )
        }
    }

    if (shown(MusicHomeShelf.DEEP_CUTS) && uiState.deepCutTracks.isNotEmpty()) {
        item(key = "deep_cuts") {
            BrainShelf(
                title = stringResource(R.string.section_deep_cuts),
                tracks = uiState.deepCutTracks,
                playFrom = "deep_cuts",
                onSongClick = onSongClick,
                onTrackMenu = onTrackMenu,
            )
        }
    }

    if (shown(MusicHomeShelf.ARTISTS_FOR_YOU) && uiState.artistsForYou.isNotEmpty()) {
        item(key = "artists_for_you") {
            MusicArtistShelf(
                title = stringResource(R.string.section_artists_for_you),
                artists = uiState.artistsForYou,
                key = { "artists_for_you:${it.channelId}" },
                name = { it.name },
                thumbnailUrl = { it.thumbnailUrl },
                onArtistClick = { onArtistClick(it.channelId) },
            )
        }
    }

    lastFmDiscovery(library, downloaded, onSongClick, onTrackMenu)

    sectionOrder.filter { shown(MusicHomeShelf.of(it)) }.forEach { sectionType ->
        when (sectionType) {
            HomeSectionType.DAILY_DISCOVER -> {
                dailyDiscover(uiState.dailyDiscover, downloaded, onSongClick, onTrackMenu)
            }

            HomeSectionType.QUICK_PICKS -> {
                quickPicks(quickPickTracks, downloaded, quickPicksGridState, onSongClick, onTrackMenu)
            }

            HomeSectionType.FROM_COMMUNITY -> {
                community(uiState.communityPlaylists, downloaded, onAlbumClick, onSongClick, onTrackMenu, onCollectionMenu)
            }

            HomeSectionType.RECOMMENDED -> {
                trackShelf(
                    id = "recommended",
                    titleRes = R.string.section_recommended,
                    tracks = uiState.recommendedTracks,
                    downloaded = downloaded,
                    playFrom = "recommended",
                    onSongClick = onSongClick,
                    onTrackMenu = onTrackMenu,
                )
            }

            HomeSectionType.SIMILAR_TO -> {
                similarTo(uiState, downloaded, onSongClick, onArtistClick, onAlbumClick, onTrackMenu, ::trackCollectionMenu)
            }

            HomeSectionType.LIVE_PERFORMANCES -> {
                mediaShelf(
                    id = "live_performances",
                    titleRes = R.string.section_live_performances,
                    tracks = uiState.livePerformances,
                    downloaded = downloaded,
                    onTrackClick = { onSongClick(it, uiState.livePerformances, "live_performances") },
                    onPlayAll = {
                        uiState.livePerformances.firstOrNull()?.let {
                            onSongClick(it, uiState.livePerformances, "live_performances")
                        }
                    },
                    onTrackMenu = onTrackMenu,
                )
            }

            HomeSectionType.MUSIC_VIDEOS_FOR_YOU -> {
                val videos = uiState.musicVideosForYou.ifEmpty { uiState.musicVideos }
                mediaShelf(
                    id = "music_videos_for_you",
                    titleRes = R.string.section_music_videos_for_you,
                    tracks = videos,
                    downloaded = downloaded,
                    onTrackClick = onVideoClick,
                    onPlayAll = { videos.firstOrNull()?.let(onVideoClick) },
                    onTrackMenu = onTrackMenu,
                )
            }

            HomeSectionType.MUSIC_VIDEOS -> {
                mediaShelf(
                    id = "music_videos",
                    titleRes = R.string.section_music_videos,
                    tracks = if (uiState.musicVideosForYou.isEmpty()) uiState.musicVideos else emptyList(),
                    downloaded = downloaded,
                    onTrackClick = onVideoClick,
                    onPlayAll = { uiState.musicVideos.firstOrNull()?.let(onVideoClick) },
                    onTrackMenu = onTrackMenu,
                )
            }

            HomeSectionType.GENRES -> {
                genres(uiState.genreTracks, downloaded, onSongClick, onTrackMenu)
            }

            HomeSectionType.DYNAMIC_HOME -> {
                dynamicHome(uiState, downloaded, onSongClick, onAlbumClick, onTrackMenu, ::trackCollectionMenu)
            }

            HomeSectionType.TOP_ALBUMS -> {
                collectionShelf(
                    id = "top_albums",
                    titleRes = R.string.section_top_albums,
                    collections = uiState.topAlbums,
                    isAlbum = true,
                    onAlbumClick = onAlbumClick,
                    onCollectionMenu = ::collectionMenu,
                )
            }

            HomeSectionType.FAVORITE_ARTIST_ALBUMS -> {
                collectionShelf(
                    id = "favorite_artist_albums",
                    titleRes = R.string.section_from_artists_you_love,
                    collections = uiState.favoriteArtistAlbums,
                    isAlbum = true,
                    onAlbumClick = onAlbumClick,
                    onCollectionMenu = ::collectionMenu,
                )
            }

            HomeSectionType.NEW_RELEASES -> {
                newReleases(uiState.newReleases, downloaded, onSongClick, onAlbumClick, onTrackMenu, ::trackCollectionMenu)
            }

            HomeSectionType.CHARTS -> {
                charts(uiState.trendingSongs, downloaded, onSongClick, onTrackMenu)
                chartPlaylists(uiState.chartCountryCode, uiState.chartPlaylists, onAlbumClick, ::collectionMenu)
                chartArtists(uiState.chartCountryCode, uiState.chartArtists, onArtistClick)
            }

            HomeSectionType.POPULAR_ARTISTS -> {
                if (popularArtists.isNotEmpty()) {
                    item(key = "popular_artists") {
                        MusicArtistShelf(
                            title = stringResource(R.string.section_popular_artists),
                            artists = popularArtists,
                            key = { "popular_artists:${it.videoId}" },
                            name = { it.artist },
                            thumbnailUrl = { it.thumbnailUrl },
                            onArtistClick = { onArtistClick(it.channelId) },
                        )
                    }
                }
            }

            HomeSectionType.MIXED_FOR_YOU -> {
                collectionShelf(
                    id = "mixed_for_you",
                    titleRes = R.string.section_mixed_for_you,
                    collections = uiState.featuredPlaylists,
                    isAlbum = false,
                    onAlbumClick = onAlbumClick,
                    onCollectionMenu = ::collectionMenu,
                )
            }

            HomeSectionType.MOODS_AND_GENRES -> {
                if (uiState.moodsAndGenres.isNotEmpty()) {
                    item(key = "moods_and_genres") {
                        MusicMoodsShelf(
                            moods = uiState.moodsAndGenres,
                            onMoodClick = { onMoodsClick(it) },
                            onSeeAll = { onMoodsClick(null) },
                        )
                    }
                }
            }
        }
    }

    if (uiState.homeContinuation != null) {
        item(key = "home_continuation") {
            LaunchedEffect(Unit) { onLoadMore() }
            if (uiState.isMoreLoading) FlowFeedProgress() else Box(modifier = Modifier.height(0.dp))
        }
    }
}

/** The time-of-day rotation's title, on the Music page and in the car. */
internal fun MusicTimeBucket.rotationTitleRes(): Int =
    when (this) {
        MusicTimeBucket.WEEKDAY_MORNING, MusicTimeBucket.WEEKEND_MORNING -> R.string.section_rotation_morning
        MusicTimeBucket.WEEKDAY_AFTERNOON, MusicTimeBucket.WEEKEND_AFTERNOON -> R.string.section_rotation_afternoon
        MusicTimeBucket.WEEKDAY_EVENING, MusicTimeBucket.WEEKEND_EVENING -> R.string.section_rotation_evening
        MusicTimeBucket.WEEKDAY_NIGHT, MusicTimeBucket.WEEKEND_NIGHT -> R.string.section_rotation_night
    }
