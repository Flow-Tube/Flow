package io.github.aedev.flow.data.paging

import io.github.aedev.flow.data.local.ContentType
import io.github.aedev.flow.data.local.Duration
import io.github.aedev.flow.data.local.SearchFeature
import io.github.aedev.flow.data.local.SearchFilter
import io.github.aedev.flow.data.local.SortType
import io.github.aedev.flow.data.local.UploadDate
import io.github.aedev.flow.innertube.YouTubeSearchParams

/**
 * Turns the user's choices into the one `params` token the request carries.
 *
 * Live is a feature flag rather than a type in YouTube's model, and Shorts is a type rather than a
 * separate endpoint; both are folded in here so the paging source only ever sends one request.
 */
internal fun SearchFilter.toSearchParams(exactQuery: Boolean = false): String? =
    YouTubeSearchParams.build(
        exactQuery = exactQuery,
        sortBy =
            when (sortType) {
                SortType.RELEVANCE -> YouTubeSearchParams.SortBy.RELEVANCE
                SortType.VIEW_COUNT -> YouTubeSearchParams.SortBy.VIEW_COUNT
            },
        contentType =
            when (contentType) {
                ContentType.ALL -> null
                ContentType.VIDEOS, ContentType.LIVE -> YouTubeSearchParams.ContentType.VIDEO
                ContentType.SHORTS -> YouTubeSearchParams.ContentType.SHORTS
                ContentType.CHANNELS -> YouTubeSearchParams.ContentType.CHANNEL
                ContentType.PLAYLISTS -> YouTubeSearchParams.ContentType.PLAYLIST
                ContentType.MOVIES -> YouTubeSearchParams.ContentType.MOVIE
            },
        duration =
            when {
                !contentType.supportsVideoFilters() -> null
                duration == Duration.ANY -> null
                duration == Duration.UNDER_3_MINUTES -> YouTubeSearchParams.Duration.UNDER_3_MINUTES
                duration == Duration.THREE_TO_20_MINUTES -> YouTubeSearchParams.Duration.THREE_TO_20_MINUTES
                else -> YouTubeSearchParams.Duration.OVER_20_MINUTES
            },
        uploadDate =
            when {
                !contentType.supportsVideoFilters() -> null
                uploadDate == UploadDate.ANY -> null
                uploadDate == UploadDate.LAST_HOUR -> YouTubeSearchParams.UploadDate.LAST_HOUR
                uploadDate == UploadDate.TODAY -> YouTubeSearchParams.UploadDate.TODAY
                uploadDate == UploadDate.THIS_WEEK -> YouTubeSearchParams.UploadDate.THIS_WEEK
                uploadDate == UploadDate.THIS_MONTH -> YouTubeSearchParams.UploadDate.THIS_MONTH
                else -> YouTubeSearchParams.UploadDate.THIS_YEAR
            },
        features =
            buildSet {
                if (contentType == ContentType.LIVE) add(YouTubeSearchParams.Feature.LIVE)
                features.mapTo(this) { it.toParam() }
            },
    )

private fun SearchFeature.toParam(): YouTubeSearchParams.Feature =
    when (this) {
        SearchFeature.HD -> YouTubeSearchParams.Feature.HD
        SearchFeature.FOUR_K -> YouTubeSearchParams.Feature.FOUR_K
        SearchFeature.HDR -> YouTubeSearchParams.Feature.HDR
        SearchFeature.SUBTITLES -> YouTubeSearchParams.Feature.SUBTITLES
        SearchFeature.CREATIVE_COMMONS -> YouTubeSearchParams.Feature.CREATIVE_COMMONS
        SearchFeature.THREE_SIXTY -> YouTubeSearchParams.Feature.THREE_SIXTY
        SearchFeature.VR180 -> YouTubeSearchParams.Feature.VR180
        SearchFeature.THREE_D -> YouTubeSearchParams.Feature.THREE_D
        SearchFeature.LOCATION -> YouTubeSearchParams.Feature.LOCATION
        SearchFeature.PURCHASED -> YouTubeSearchParams.Feature.PURCHASED
    }

internal fun ContentType.supportsVideoFilters(): Boolean = this != ContentType.CHANNELS && this != ContentType.PLAYLISTS
