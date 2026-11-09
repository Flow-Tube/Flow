package io.github.aedev.flow.ui.screens.search

import io.github.aedev.flow.data.local.ContentType
import io.github.aedev.flow.data.local.Duration
import io.github.aedev.flow.data.local.SearchFeature
import io.github.aedev.flow.data.local.SearchFilter
import io.github.aedev.flow.data.local.SortType
import io.github.aedev.flow.data.local.UploadDate
import io.github.aedev.flow.data.local.WatchState
import java.net.URLEncoder

internal const val SEARCH_QUERY_ARG = "query"
internal const val SEARCH_FILTERS_ARG = "filters"
internal const val SEARCH_DEPTH_ARG = "depth"

/** Search as a page: each search submitted from results gets its own, so Back returns to the one before. */
internal const val SEARCH_ROUTE_PATTERN =
    "search?$SEARCH_QUERY_ARG={$SEARCH_QUERY_ARG}" +
        "&$SEARCH_FILTERS_ARG={$SEARCH_FILTERS_ARG}" +
        "&$SEARCH_DEPTH_ARG={$SEARCH_DEPTH_ARG}"

/** The most search pages kept at once; past it a new search replaces the newest instead of piling up. */
internal const val MAX_SEARCH_DEPTH = 10

/** The page for a search of [query] with [filters], [depth] searches deep. */
internal fun searchRoute(
    query: String,
    filters: SearchFilter,
    depth: Int,
): String =
    "search?$SEARCH_QUERY_ARG=${URLEncoder.encode(query, Charsets.UTF_8.name())}" +
        "&$SEARCH_FILTERS_ARG=${URLEncoder.encode(filters.toRouteArg(), Charsets.UTF_8.name())}" +
        "&$SEARCH_DEPTH_ARG=$depth"

private const val FIELD_SEPARATOR = "|"
private const val FEATURE_SEPARATOR = ","

internal fun SearchFilter.toRouteArg(): String =
    listOf(
        contentType.name,
        duration.name,
        uploadDate.name,
        sortType.name,
        features.joinToString(FEATURE_SEPARATOR) { it.name },
        watchState.name,
    ).joinToString(FIELD_SEPARATOR)

/** The filters a search page was opened with; anything unreadable falls back to the default. */
internal fun searchFilterFromRouteArg(arg: String?): SearchFilter {
    val parts = arg?.split(FIELD_SEPARATOR) ?: return SearchFilter.DEFAULT
    if (parts.size != FIELD_COUNT) return SearchFilter.DEFAULT
    return SearchFilter(
        contentType = enumOrNull<ContentType>(parts[0]) ?: ContentType.ALL,
        duration = enumOrNull<Duration>(parts[1]) ?: Duration.ANY,
        uploadDate = enumOrNull<UploadDate>(parts[2]) ?: UploadDate.ANY,
        sortType = enumOrNull<SortType>(parts[3]) ?: SortType.RELEVANCE,
        features =
            parts[4]
                .split(FEATURE_SEPARATOR)
                .mapNotNull { enumOrNull<SearchFeature>(it) }
                .toSet(),
        watchState = enumOrNull<WatchState>(parts[5]) ?: WatchState.ANY,
    )
}

private const val FIELD_COUNT = 6

private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? = enumValues<T>().firstOrNull { it.name == name }
