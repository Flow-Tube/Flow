package io.github.aedev.flow.ui.screens.music

import io.github.aedev.flow.data.contentfilter.aislist.AiListMatcher
import io.github.aedev.flow.innertube.models.AlbumItem
import io.github.aedev.flow.innertube.models.PlaylistItem
import io.github.aedev.flow.innertube.models.SongItem
import io.github.aedev.flow.innertube.models.YTItem

/**
 * Leaves out search results the AI filter hides: songs, videos and albums by their credited
 * artists, playlists by their author. Artist cards stay, as channel cards do in video search, so a
 * listed artist can still be found and opened on purpose.
 */
internal fun MusicSearchUiState.withoutAiMusic(ai: AiListMatcher): MusicSearchUiState {
    if (!ai.isMusicActive) return this
    return copy(
        recommendedItems = recommendedItems.withoutAiMusic(ai),
        searchSummary =
            searchSummary?.let { page ->
                page.copy(
                    summaries =
                        page.summaries.mapNotNull { summary ->
                            val items = summary.items.withoutAiMusic(ai)
                            when {
                                items.size == summary.items.size -> summary
                                items.isEmpty() -> null
                                else -> summary.copy(items = items)
                            }
                        },
                )
            },
        filteredResults = filteredResults.withoutAiMusic(ai),
    )
}

internal fun List<YTItem>.withoutAiMusic(ai: AiListMatcher): List<YTItem> {
    val filtered = filterNot { item -> ai.hidesMusic(item.aiCreditedIds()) }
    return if (filtered.size == size) this else filtered
}

/** The IDs an item credits, which the AI filter checks: artists of songs and albums, a playlist's author. */
internal fun YTItem.aiCreditedIds(): List<String> =
    when (this) {
        is SongItem -> artists.mapNotNull { it.id }
        is AlbumItem -> artists.orEmpty().mapNotNull { it.id }
        is PlaylistItem -> listOfNotNull(author?.id)
        else -> emptyList()
    }
