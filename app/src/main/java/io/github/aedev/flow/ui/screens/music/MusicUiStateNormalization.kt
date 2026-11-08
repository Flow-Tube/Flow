package io.github.aedev.flow.ui.screens.music

import io.github.aedev.flow.data.model.distinctByNonBlankKeyOrSelf
import io.github.aedev.flow.data.music.model.ArtistDetails
import io.github.aedev.flow.data.music.model.MusicPlaylist
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.recommendation.MusicSection
import io.github.aedev.flow.data.recommendation.music.isHiddenArtist
import io.github.aedev.flow.data.recommendation.music.isHiddenAuthor

/**
 * Applies "not interested"/"don't recommend" feedback to every recommendation
 * shelf in one place, so the filter can never miss a section. History, the
 * user's own playlists and explicitly opened artist pages stay untouched —
 * feedback shapes recommendations, it does not rewrite the user's records.
 */
internal fun MusicUiState.withHiddenArtists(hidden: Set<String>): MusicUiState {
    if (hidden.isEmpty()) return this

    fun List<MusicTrack>.dropHidden(): List<MusicTrack> {
        if (isEmpty()) return this
        val filtered = filterNot { it.isHiddenArtist(hidden) }
        return if (filtered.size == size) this else filtered
    }

    fun List<MusicSection>.dropHidden(minTracks: Int): List<MusicSection> {
        var changed = false
        val filtered =
            mapNotNull { section ->
                val tracks = section.tracks.dropHidden()
                when {
                    tracks === section.tracks -> {
                        section
                    }

                    tracks.size < minTracks -> {
                        changed = true
                        null
                    }

                    else -> {
                        changed = true
                        section.copy(tracks = tracks)
                    }
                }
            }
        return if (changed) filtered else this
    }

    val filteredDailyDiscover =
        dailyDiscover
            .filterNot { it.recommendation.isHiddenArtist(hidden) }
            .let { if (it.size == dailyDiscover.size) dailyDiscover else it }
    val filteredTopAlbums =
        topAlbums
            .filterNot { it.isHiddenAuthor(hidden) }
            .let { if (it.size == topAlbums.size) topAlbums else it }
    val filteredFavoriteAlbums =
        favoriteArtistAlbums
            .filterNot { it.isHiddenAuthor(hidden) }
            .let { if (it.size == favoriteArtistAlbums.size) favoriteArtistAlbums else it }
    val filteredCommunity =
        communityPlaylists
            .mapNotNull { item ->
                val tracks = item.tracks.dropHidden()
                when {
                    tracks === item.tracks -> item
                    tracks.isEmpty() -> null
                    else -> item.copy(tracks = tracks)
                }
            }.let { if (it == communityPlaylists) communityPlaylists else it }
    val filteredGenreTracks = genreTracks.mapValuesIfChanged { it.dropHidden() }

    val filteredOnRepeat = onRepeatTracks.dropHidden()
    val filteredRediscover = rediscoverTracks.dropHidden()
    val filteredDeepCuts = deepCutTracks.dropHidden()
    val filteredArtistsForYou =
        artistsForYou
            .filterNot { it.isHiddenArtist(hidden) }
            .let { if (it.size == artistsForYou.size) artistsForYou else it }
    val filteredRotation = rotationTracks.dropHidden()
    val filteredSpeedDial = speedDialTracks.dropHidden()
    val filteredForYou = forYouTracks.dropHidden()
    val filteredRecommended = recommendedTracks.dropHidden()
    val filteredListenAgain = listenAgain.dropHidden()
    val filteredTrending = trendingSongs.dropHidden()
    val filteredChartArtists =
        chartArtists
            .filterNot { it.isHiddenArtist(hidden) }
            .let { if (it.size == chartArtists.size) chartArtists else it }
    val filteredNewReleases = newReleases.dropHidden()
    val filteredMusicVideos = musicVideos.dropHidden()
    val filteredMusicVideosForYou = musicVideosForYou.dropHidden()
    val filteredLivePerformances = livePerformances.dropHidden()
    val filteredLongListens = longListens.dropHidden()
    val filteredDynamicSections = dynamicSections.dropHidden(minTracks = 1)
    val filteredChipSections = homeChipPage?.sections?.dropHidden(minTracks = 1)
    val filteredDailyMixSections = dailyMixSections.dropHidden(minTracks = 4)
    val filteredSimilarSections = similarToSections.dropHidden(minTracks = 1)
    val filteredOtherPerformanceSections = otherPerformanceSections.dropHidden(minTracks = 1)
    val filteredMoreFromArtistSections = moreFromArtistSections.dropHidden(minTracks = 1)

    if (
        filteredDailyDiscover === dailyDiscover &&
        filteredChipSections === homeChipPage?.sections &&
        filteredOnRepeat === onRepeatTracks &&
        filteredRediscover === rediscoverTracks &&
        filteredDeepCuts === deepCutTracks &&
        filteredArtistsForYou === artistsForYou &&
        filteredRotation === rotationTracks &&
        filteredSpeedDial === speedDialTracks &&
        filteredForYou === forYouTracks &&
        filteredRecommended === recommendedTracks &&
        filteredListenAgain === listenAgain &&
        filteredTrending === trendingSongs &&
        filteredChartArtists === chartArtists &&
        filteredNewReleases === newReleases &&
        filteredMusicVideos === musicVideos &&
        filteredMusicVideosForYou === musicVideosForYou &&
        filteredLivePerformances === livePerformances &&
        filteredLongListens === longListens &&
        filteredTopAlbums === topAlbums &&
        filteredFavoriteAlbums === favoriteArtistAlbums &&
        filteredCommunity === communityPlaylists &&
        filteredGenreTracks === genreTracks &&
        filteredDynamicSections === dynamicSections &&
        filteredDailyMixSections === dailyMixSections &&
        filteredSimilarSections === similarToSections &&
        filteredOtherPerformanceSections === otherPerformanceSections &&
        filteredMoreFromArtistSections === moreFromArtistSections
    ) {
        return this
    }

    return copy(
        homeChipPage = filteredChipSections?.let { homeChipPage?.copy(sections = it) },
        dailyDiscover = filteredDailyDiscover,
        onRepeatTracks = filteredOnRepeat,
        rediscoverTracks = filteredRediscover,
        deepCutTracks = filteredDeepCuts,
        artistsForYou = filteredArtistsForYou,
        rotationTracks = filteredRotation,
        speedDialTracks = filteredSpeedDial,
        forYouTracks = filteredForYou,
        recommendedTracks = filteredRecommended,
        listenAgain = filteredListenAgain,
        trendingSongs = filteredTrending,
        chartArtists = filteredChartArtists,
        newReleases = filteredNewReleases,
        musicVideos = filteredMusicVideos,
        musicVideosForYou = filteredMusicVideosForYou,
        livePerformances = filteredLivePerformances,
        longListens = filteredLongListens,
        topAlbums = filteredTopAlbums,
        favoriteArtistAlbums = filteredFavoriteAlbums,
        communityPlaylists = filteredCommunity,
        genreTracks = filteredGenreTracks,
        dynamicSections = filteredDynamicSections,
        dailyMixSections = filteredDailyMixSections,
        similarToSections = filteredSimilarSections,
        otherPerformanceSections = filteredOtherPerformanceSections,
        moreFromArtistSections = filteredMoreFromArtistSections,
    )
}

internal fun MusicUiState.withUniqueLazyContent(): MusicUiState {
    val uniqueDailyDiscover =
        dailyDiscover.distinctByNonBlankKeyOrSelf {
            it.recommendation.videoId
        }
    val uniqueForYou = forYouTracks.uniqueMusicTracks()
    val uniqueDeepCuts = deepCutTracks.uniqueMusicTracks()
    val uniqueArtistsForYou = artistsForYou.distinctByNonBlankKeyOrSelf(ArtistDetails::channelId)
    val uniqueRecommended = recommendedTracks.uniqueMusicTracks()
    val uniqueListenAgain = listenAgain.uniqueMusicTracks()
    val uniqueTrending = trendingSongs.uniqueMusicTracks()
    val uniqueChartPlaylists = chartPlaylists.uniqueMusicPlaylists()
    val uniqueChartArtists = chartArtists.distinctByNonBlankKeyOrSelf(ArtistDetails::channelId)
    val uniqueNewReleases = newReleases.uniqueMusicTracks()
    val uniqueMusicVideos = musicVideos.uniqueMusicTracks()
    val uniqueMusicVideosForYou = musicVideosForYou.uniqueMusicTracks()
    val uniqueLivePerformances = livePerformances.uniqueMusicTracks()
    val uniqueLongListens = longListens.uniqueMusicTracks()
    val uniqueHistory = history.uniqueMusicTracks()
    val uniqueCommunityPlaylists =
        communityPlaylists.distinctByNonBlankKeyOrSelf {
            it.playlist.id
        }
    val uniqueFeaturedPlaylists = featuredPlaylists.uniqueMusicPlaylists()
    val uniqueTopAlbums = topAlbums.uniqueMusicPlaylists()
    val uniqueFavoriteArtistAlbums = favoriteArtistAlbums.uniqueMusicPlaylists()
    val uniqueDynamicSections = dynamicSections.uniqueSectionTracks()
    val uniqueChipSections = homeChipPage?.sections?.uniqueSectionTracks()
    val uniqueDailyMixSections = dailyMixSections.uniqueSectionTracks()
    val uniqueSimilarSections = similarToSections.uniqueSectionTracks()
    val uniqueOtherPerformanceSections = otherPerformanceSections.uniqueSectionTracks()
    val uniqueMoreFromArtistSections = moreFromArtistSections.uniqueSectionTracks()
    val uniqueHomeChips = homeChips.distinctByNonBlankKeyOrSelf { it.title }
    val uniqueGenreTracks =
        genreTracks.mapValuesIfChanged { tracks ->
            tracks.uniqueMusicTracks()
        }
    val uniqueArtistDetails = artistDetails?.withUniqueLazyContent()
    val uniqueSearchArtists =
        searchResultsArtists.distinctByNonBlankKeyOrSelf {
            it.channelId
        }

    if (
        uniqueDailyDiscover === dailyDiscover &&
        uniqueForYou === forYouTracks &&
        uniqueDeepCuts === deepCutTracks &&
        uniqueArtistsForYou === artistsForYou &&
        uniqueRecommended === recommendedTracks &&
        uniqueListenAgain === listenAgain &&
        uniqueTrending === trendingSongs &&
        uniqueChartPlaylists === chartPlaylists &&
        uniqueChartArtists === chartArtists &&
        uniqueNewReleases === newReleases &&
        uniqueMusicVideos === musicVideos &&
        uniqueMusicVideosForYou === musicVideosForYou &&
        uniqueLivePerformances === livePerformances &&
        uniqueLongListens === longListens &&
        uniqueHistory === history &&
        uniqueCommunityPlaylists === communityPlaylists &&
        uniqueFeaturedPlaylists === featuredPlaylists &&
        uniqueTopAlbums === topAlbums &&
        uniqueFavoriteArtistAlbums === favoriteArtistAlbums &&
        uniqueDynamicSections === dynamicSections &&
        uniqueChipSections === homeChipPage?.sections &&
        uniqueDailyMixSections === dailyMixSections &&
        uniqueSimilarSections === similarToSections &&
        uniqueOtherPerformanceSections === otherPerformanceSections &&
        uniqueMoreFromArtistSections === moreFromArtistSections &&
        uniqueHomeChips === homeChips &&
        uniqueGenreTracks === genreTracks &&
        uniqueArtistDetails === artistDetails &&
        uniqueSearchArtists === searchResultsArtists
    ) {
        return this
    }

    return copy(
        dailyDiscover = uniqueDailyDiscover,
        forYouTracks = uniqueForYou,
        deepCutTracks = uniqueDeepCuts,
        artistsForYou = uniqueArtistsForYou,
        recommendedTracks = uniqueRecommended,
        listenAgain = uniqueListenAgain,
        trendingSongs = uniqueTrending,
        chartPlaylists = uniqueChartPlaylists,
        chartArtists = uniqueChartArtists,
        newReleases = uniqueNewReleases,
        musicVideos = uniqueMusicVideos,
        musicVideosForYou = uniqueMusicVideosForYou,
        livePerformances = uniqueLivePerformances,
        communityPlaylists = uniqueCommunityPlaylists,
        longListens = uniqueLongListens,
        history = uniqueHistory,
        genreTracks = uniqueGenreTracks,
        featuredPlaylists = uniqueFeaturedPlaylists,
        topAlbums = uniqueTopAlbums,
        favoriteArtistAlbums = uniqueFavoriteArtistAlbums,
        dynamicSections = uniqueDynamicSections,
        homeChipPage = uniqueChipSections?.let { homeChipPage?.copy(sections = it) },
        dailyMixSections = uniqueDailyMixSections,
        homeChips = uniqueHomeChips,
        artistDetails = uniqueArtistDetails,
        searchResultsArtists = uniqueSearchArtists,
        similarToSections = uniqueSimilarSections,
        otherPerformanceSections = uniqueOtherPerformanceSections,
        moreFromArtistSections = uniqueMoreFromArtistSections,
    )
}

private fun List<MusicTrack>.uniqueMusicTracks(): List<MusicTrack> = distinctByNonBlankKeyOrSelf(MusicTrack::videoId)

private fun List<MusicPlaylist>.uniqueMusicPlaylists(): List<MusicPlaylist> = distinctByNonBlankKeyOrSelf(MusicPlaylist::id)

private fun List<MusicSection>.uniqueSectionTracks(): List<MusicSection> {
    var changed = false
    val normalized =
        map { section ->
            val uniqueTracks = section.tracks.uniqueMusicTracks()
            if (uniqueTracks === section.tracks) {
                section
            } else {
                changed = true
                section.copy(tracks = uniqueTracks)
            }
        }
    return if (changed) normalized else this
}

private fun ArtistDetails.withUniqueLazyContent(): ArtistDetails {
    val uniqueTopTracks = topTracks.uniqueMusicTracks()
    val uniqueAlbums = albums.uniqueMusicPlaylists()
    val uniqueSingles = singles.uniqueMusicPlaylists()
    val uniqueVideos = videos.uniqueMusicTracks()
    val uniqueRelatedArtists = relatedArtists.distinctByNonBlankKeyOrSelf(ArtistDetails::channelId)
    val uniqueFeaturedOn = featuredOn.uniqueMusicPlaylists()
    return if (
        uniqueTopTracks === topTracks &&
        uniqueAlbums === albums &&
        uniqueSingles === singles &&
        uniqueVideos === videos &&
        uniqueRelatedArtists === relatedArtists &&
        uniqueFeaturedOn === featuredOn
    ) {
        this
    } else {
        copy(
            topTracks = uniqueTopTracks,
            albums = uniqueAlbums,
            singles = uniqueSingles,
            videos = uniqueVideos,
            relatedArtists = uniqueRelatedArtists,
            featuredOn = uniqueFeaturedOn,
        )
    }
}

private fun Map<String, List<MusicTrack>>.mapValuesIfChanged(
    transform: (List<MusicTrack>) -> List<MusicTrack>,
): Map<String, List<MusicTrack>> {
    var changed = false
    val normalized =
        mapValues { (_, tracks) ->
            transform(tracks).also { if (it !== tracks) changed = true }
        }
    return if (changed) normalized else this
}
